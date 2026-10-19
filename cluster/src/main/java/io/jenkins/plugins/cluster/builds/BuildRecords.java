package io.jenkins.plugins.cluster.builds;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.ExtensionPoint;
import hudson.FilePath;
import hudson.Util;
import hudson.XmlFile;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.RunMap;
import hudson.model.Saveable;
import hudson.model.TaskListener;
import hudson.model.listeners.ItemListener;
import hudson.model.listeners.RunListener;
import hudson.model.listeners.SaveableListener;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.util.DirScanner;
import hudson.util.FileVisitor;
import io.jenkins.plugins.cluster.PerItemCache;
import io.jenkins.plugins.cluster.spi.ClusterBlobStore;
import io.jenkins.plugins.cluster.spi.ClusterStore;
import io.jenkins.plugins.cluster.spi.Keys;
import io.jenkins.plugins.cluster.spi.LeaseLostException;
import io.jenkins.plugins.cluster.spi.Resync;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import jenkins.model.ItemUuids;
import jenkins.model.Jenkins;
import jenkins.model.lazy.LazyBuildMixIn;
import jenkins.persistence.ConflictException;
import jenkins.persistence.PersistenceStore;
import jenkins.persistence.PersistenceStores;
import jenkins.util.ClusterSingleton;
import jenkins.util.ThrowingCallable;
import jenkins.util.Timer;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Shares completed builds between controllers.
 *
 * <p>When a build completes, the controller which ran it publishes a snapshot of its build directory to the
 * {@link ClusterBlobStore} ({@link Keys#snapshotBlob}: {@code items/<uuid>/builds/<N>/files.tgz}) and then its
 * {@code build.xml} to the store ({@link Keys#buildRecord}: {@code _items/<uuid>/builds/<N>/build.xml}). The record is
 * written last, so a record in the store means a complete build. Other controllers list stored builds alongside local
 * ones ({@link ClusterBuildRecordSource}), and extract the snapshot into the local build directory the first time the
 * build is loaded, so that every reader of the build directory works unchanged.
 *
 * <p>Everything is keyed by the job's {@linkplain Item#getItemUuid identity}: a rename or move changes nothing here, and a
 * job made again under a deleted job's name is another job, with none of its builds.
 */
@Restricted(NoExternalUse.class)
public final class BuildRecords {

    private static final Logger LOGGER = Logger.getLogger(BuildRecords.class.getName());

    static final String RECORD = "build.xml";
    /** The stand-in snapshot of a build closed as lost: its own name, so that it never replaces a real snapshot. */
    static final String LOST_SNAPSHOT = "lost.tgz";

    /**
     * Never part of a snapshot: the record itself, artifacts (use an external {@link jenkins.model.ArtifactManager}),
     * what this class keeps in the build directory, and temporary files.
     */
    static final List<String> DEFAULT_EXCLUDES =
            List.of(RECORD, "archive/**", "**/*.tmp", Manifest.NAME, RecordVersion.NAME, SnapshotFiles.NAME);

    /** Set while this thread loads a build, so that saves made while loading are not republished. */
    private static final ThreadLocal<Boolean> LOADING = new ThreadLocal<>();

    private BuildRecords() {}

    /**
     * Leaves files out of build snapshots, for example because a plugin stores them elsewhere.
     */
    public interface Exclusion extends ExtensionPoint {
        /**
         * @return Ant-style patterns relative to the build directory
         */
        @NonNull
        Collection<String> excludes(@NonNull Run<?, ?> run);
    }

    /** State a Pipeline needs only while it runs, which the cluster keeps elsewhere (save points), or never needs again. */
    @Extension
    public static final class PipelineState implements Exclusion {
        @NonNull
        @Override
        public Collection<String> excludes(@NonNull Run<?, ?> run) {
            return List.of("program.dat", "workflow/**", "stashes/**");
        }
    }

    /** The cluster store, if builds are shared through one. */
    @CheckForNull
    static ClusterStore store() {
        return ClusterStore.get();
    }

    /**
     * The prefix below which the store keeps a job's builds, or null if its builds are not shared (no cluster store,
     * or a job with no identity).
     */
    @CheckForNull
    static String prefix(@NonNull Job<?, ?> job) {
        return store() == null || job.getItemUuid() == null ? null : Keys.item(job);
    }

    /** The prefix below which the blob store keeps a job's builds' files, or null (see {@link #prefix}). */
    @CheckForNull
    static String blobPrefix(@NonNull Job<?, ?> job) {
        return prefix(job) == null ? null : Keys.blobs(job);
    }

    /** The blob of a build's files, below {@link Keys#snapshotBlob} with this name. */
    static String blob(String blobPrefix, int number, String name) {
        String snapshot = Keys.snapshotBlob(blobPrefix, number);
        return snapshot.substring(0, snapshot.lastIndexOf('/') + 1) + name;
    }

    /**
     * Where the blob store keeps a build's files ({@code items/<uuid>/builds/<N>}: snapshot, log, save points), or null if
     * the job's builds are not shared.
     */
    @CheckForNull
    public static String buildBlobs(@NonNull Job<?, ?> job, int number) {
        String blobPrefix = blobPrefix(job);
        return blobPrefix == null ? null : blobDir(blobPrefix, number);
    }

    /** The directory of a build's blobs. */
    static String blobDir(String blobPrefix, int number) {
        String snapshot = Keys.snapshotBlob(blobPrefix, number);
        return snapshot.substring(0, snapshot.lastIndexOf('/'));
    }

    /**
     * Whether builds of this job are shared, so that build numbers taken by other controllers may appear at any time.
     */
    public static boolean isShared(@NonNull Job<?, ?> job) {
        return prefix(job) != null;
    }

    /**
     * Numbers of the builds of a job recorded in the store, for callers that must tell "none" from "unknown", such as
     * an import deciding what to publish: publishing overwrites what is stored.
     *
     * @throws IOException if the store cannot list them
     */
    @NonNull
    public static Set<Integer> listStoredNumbers(@NonNull Job<?, ?> job) throws IOException {
        Set<Integer> numbers = new TreeSet<>();
        String prefix = prefix(job);
        if (prefix == null) {
            return numbers;
        }
        ClusterStore store = store();
        if (store == null) {
            return numbers;
        }
        for (String name : store.children(Keys.builds(prefix))) {
            try {
                numbers.add(Integer.parseInt(name));
            } catch (NumberFormatException e) {
                // not a build (the permalinks)
            }
        }
        return numbers;
    }

    /**
     * {@link #listStoredNumbers}, or none if they cannot be listed (logged): the job's build index is loaded with its
     * local builds only, and those stored are listed again a little later ({@link #listAgain}), rather than missing
     * from it until the job is loaded again.
     */
    @NonNull
    static Set<Integer> storedNumbers(@NonNull Job<?, ?> job) {
        String id = job.getItemUuid();
        if (id != null) {
            indexed.add(id);
        }
        try {
            return listStoredNumbers(job);
        } catch (IOException e) {
            LOGGER.log(
                    Level.WARNING,
                    "Could not list stored builds of " + job.getFullName() + "; listing them again shortly",
                    e);
            String uuid = job.getItemUuid();
            if (uuid != null) {
                unlisted.add(uuid);
            }
            return new TreeSet<>();
        }
    }

    /** Jobs whose stored builds could not be listed as their build index was loaded, by identity. */
    private static final Set<String> unlisted = ConcurrentHashMap.newKeySet();

    /** Jobs whose build index was loaded with the stored builds, by identity: what {@link #catchUp} lists again. */
    private static final Set<String> indexed = ConcurrentHashMap.newKeySet();

    /** Adds to the build indexes of jobs whose stored builds could not be listed the builds they lack. */
    static void listAgain() {
        for (String uuid : List.copyOf(unlisted)) {
            Job<?, ?> job = ItemEvents.job(uuid);
            RunMap<?> runs = job == null ? null : runsOf(job);
            if (runs == null || !isShared(job)) {
                unlisted.remove(uuid); // gone, or no longer shared
                continue;
            }
            try {
                listAgain(job, runs);
            } catch (IOException e) {
                LOGGER.log(Level.FINE, "Still cannot list stored builds of " + job.getFullName(), e);
                continue;
            }
            unlisted.remove(uuid);
            LOGGER.info(() -> "Listed the stored builds of " + job.getFullName() + " again");
        }
    }

    /**
     * Adds to a job's build index the stored builds it lacks.
     *
     * @return the stored numbers
     */
    private static Set<Integer> listAgain(Job<?, ?> job, RunMap<?> runs) throws IOException {
        Set<Integer> numbers = listStoredNumbers(job);
        for (int number : numbers) {
            File dir = new File(job.getBuildDir(), String.valueOf(number));
            try {
                synchronized (runs) {
                    if (!new File(dir, RECORD).isFile()) { // else local, and indexed
                        Files.createDirectories(dir.toPath());
                        runs.recognizeNumber(number); // materialized when first loaded
                    }
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Could not add " + job.getFullName() + " #" + number, e);
            }
            followNumber(job, number);
        }
        StoredPermalinks.changedElsewhere(job);
        return numbers;
    }

    /**
     * Lists again the stored builds of every job whose build index is loaded, and applies what differs, as if their
     * change events had just arrived (they may have been lost): a build stored but not indexed here is added; a copy
     * fetched or published here whose record is no longer stored is forgotten; a loaded copy whose record changed is
     * refreshed. (A copy not loaded is compared when its record next changes, or when the job is loaded again.) Each
     * job is caught up on the thread its changes are applied on, in order with them.
     */
    static void catchUp() {
        for (String uuid : List.copyOf(indexed)) {
            Job<?, ?> job = ItemEvents.job(uuid);
            if (job == null || runsOf(job) == null || !isShared(job)) {
                indexed.remove(uuid); // gone, or no longer shared; added again when its index is loaded again
                continue;
            }
            ItemEvents.submit(uuid, "the stored builds of " + job.getFullName(), () -> catchUp(job));
        }
    }

    private static void catchUp(Job<?, ?> job) throws IOException {
        RunMap<?> runs = runsOf(job);
        if (runs == null) {
            return;
        }
        Set<Integer> stored = listAgain(job, runs);
        unlisted.remove(job.getItemUuid());
        File[] dirs = job.getBuildDir().listFiles();
        for (File dir : dirs == null ? new File[0] : dirs) {
            int number;
            try {
                number = Integer.parseInt(dir.getName());
            } catch (NumberFormatException e) {
                continue;
            }
            // only a copy that was stored (published or fetched here): one completed here may not be published yet
            if (!stored.contains(number)
                    && new File(dir, RECORD).isFile()
                    && RecordVersion.read(dir) != PersistenceStore.ANY) {
                deletedElsewhere(job, number); // checks the store again
            }
        }
        for (Map.Entry<Integer, ? extends Run<?, ?>> e :
                Map.copyOf(runs.getLoadedBuilds()).entrySet()) {
            if (stored.contains(e.getKey())
                    && !e.getValue().isBuilding()
                    && RecordVersion.read(e.getValue().getRootDir()) != PersistenceStore.ANY) {
                writtenElsewhere(job, e.getKey()); // nothing if this copy has the stored version
            }
        }
    }

    /** Catches up with the store's build records of the jobs whose build index is loaded here. */
    @Extension
    @Restricted(NoExternalUse.class)
    public static final class CatchUp extends Resync {
        @Override
        public void catchUp() {
            BuildRecords.catchUp();
        }
    }

    /**
     * Publishes a completed build.
     */
    public static void publish(@NonNull Run<?, ?> run) throws IOException {
        publish(run, false, false);
    }

    /**
     * Publishes a completed build again after files of it changed elsewhere than in its record (for example the rest
     * of a log shipped late), so that other controllers fetch it anew.
     */
    public static void republishFiles(@NonNull Run<?, ?> run) throws IOException {
        publish(run, true, false);
    }

    /**
     * @param filesChanged its files changed since it was published: other controllers are to fetch them again
     * @param recordChanged its record was saved here too (not only files added): this controller's record is written,
     *     as a republish does, rather than the stored one marked
     */
    private static void publish(@NonNull Run<?, ?> run, boolean filesChanged, boolean recordChanged)
            throws IOException {
        Job<?, ?> job = run.getParent();
        String prefix = prefix(job);
        if (prefix == null) {
            return;
        }
        String recordKey = Keys.buildRecord(prefix, run.getNumber());
        synchronized (deleted) {
            publishing.merge(recordKey, 1, Integer::sum);
        }
        try {
            publish(run, prefix, recordKey, filesChanged, recordChanged);
        } finally {
            synchronized (deleted) {
                publishing.computeIfPresent(recordKey, (k, n) -> n == 1 ? null : n - 1);
            }
        }
    }

    private static void publish(
            Run<?, ?> run, String prefix, String recordKey, boolean filesChanged, boolean recordChanged)
            throws IOException {
        Job<?, ?> job = run.getParent();
        File dir = run.getRootDir();
        Path record = new File(dir, RECORD).toPath();
        if (!Files.isRegularFile(record)) {
            LOGGER.warning(() -> "Not publishing " + run + ": no " + record);
            return;
        }
        if (gone(job)) {
            // deleted (here, or elsewhere and applied here) while it completed: its builds were removed from the store
            LOGGER.fine(() -> "Not publishing " + run + ": its job is gone");
            return;
        }
        ClusterStore store = store();
        if (store == null) {
            return; // no cluster store (any more): nothing to publish to
        }
        // held while it ran here (none when publishing a build for another controller): what makes the publication its
        // owner's, checked as the snapshot goes into place and as the record is written
        RunningBuilds.Placeholder lease = RunningBuilds.ownedPlaceholder(run);
        String blobPrefix = Keys.blobs(job);
        String snapshotKey = Keys.snapshotBlob(blobPrefix, run.getNumber());
        if (filesChanged) {
            // files added to the build elsewhere since this copy was last in sync are kept in what it publishes,
            // whichever
            // way it is published (a save here before the change elsewhere is applied, late files)
            addFilesAddedElsewhere(snapshotKey, dir);
        }
        // before the snapshot: a file added while it is made and uploaded then differs from this, and is published
        // later
        String signature = signature(dir);
        ClusterBlobStore blobs = ClusterBlobStore.get();
        Set<String> tarred = null;
        if (blobs != null) {
            tarred = new TreeSet<>();
            List<String> excludes = new ArrayList<>(DEFAULT_EXCLUDES);
            for (Exclusion exclusion : ExtensionList.lookup(Exclusion.class)) {
                excludes.addAll(exclusion.excludes(run));
            }
            // staged next to the build directory, not in the temporary directory (often a small tmpfs in containers)
            Path snapshot =
                    Files.createTempFile(dir.getParentFile().toPath(), "." + dir.getName() + "-snapshot", ".tmp");
            try {
                try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(snapshot))) {
                    new FilePath(dir)
                            .tar(
                                    out,
                                    new OwnFiles(new DirScanner.Glob("**", String.join(",", excludes), false), tarred));
                } catch (InterruptedException e) {
                    throw (IOException) new InterruptedIOException().initCause(e);
                }
                if (lease == null) {
                    try (InputStream in = Files.newInputStream(snapshot)) {
                        blobs.put(snapshotKey, in, Files.size(snapshot));
                    }
                } else {
                    // uploaded under a name of its own first, which a controller that lost the build cannot put over
                    // the
                    // owner's; the upload may take long, so the lease is checked once it is done, and only the copy
                    // into
                    // place remains for it to be lost in
                    String upload = blob(blobPrefix, run.getNumber(), "files.tgz." + lease.getEpoch());
                    try {
                        try (InputStream in = Files.newInputStream(snapshot)) {
                            blobs.put(upload, in, Files.size(snapshot));
                        }
                        if (!store.leases().holds(lease.getLeaseKey(), lease.getEpoch())) {
                            throw new LeaseLostException(lease.getLeaseKey(), lease.getEpoch());
                        }
                        try (InputStream in = Files.newInputStream(snapshot)) {
                            blobs.put(snapshotKey, in, Files.size(snapshot));
                        }
                    } finally {
                        blobs.delete(upload);
                    }
                }
            } finally {
                Files.deleteIfExists(snapshot);
            }
        }
        if (isDeleted(recordKey, run)) {
            discardSnapshot(job, run.getNumber());
            LOGGER.fine(() -> "Not publishing " + run + ": deleted while being published");
            return;
        }
        try {
            byte[] published = Files.readAllBytes(record);
            PersistenceStore.Entry stored = store.read(recordKey);
            if (filesChanged && stored == null) {
                // deleted elsewhere, which this controller has not applied yet: not brought back for files added here
                // (nor for its record saved here with them)
                discardSnapshot(job, run.getNumber());
                recentlyPublished.remove(recordKey);
                LOGGER.fine(() -> "Not publishing files added to " + run + ": deleted elsewhere");
                return;
            }
            if (!filesChanged
                    && stored != null
                    && stored.version() != RecordVersion.read(dir)
                    && !Arrays.equals(stored.content(), published)) {
                // published already (a retry: the placeholder's removal failed, or the answer to the write was lost),
                // and changed elsewhere since: that change stands rather than this copy's record, and the copy here is
                // refreshed
                LOGGER.info(() -> run + " was changed on another controller since it was published; keeping that");
                refreshLater(job, run.getNumber());
                return;
            }
            if (filesChanged && stored != null && !recordChanged) {
                // only the files changed: mark the stored record (telling other controllers to fetch the snapshot
                // again),
                // rather than writing this controller's copy over an edit made elsewhere since
                byte[] marked = (new String(stored.content(), StandardCharsets.UTF_8)
                                        .replaceAll("\n<!-- files updated \\d+ -->$", "")
                                + "\n<!-- files updated " + System.currentTimeMillis() + " -->")
                        .getBytes(StandardCharsets.UTF_8);
                long version;
                try {
                    version = store.write(recordKey, marked, stored.version());
                } catch (ConflictException e) {
                    LOGGER.fine(() -> run + " changed meanwhile; its files are published with the next sweep");
                    return;
                }
                recentlyPublished.put(
                        recordKey, new Published(new WeakReference<>(run), signature, System.currentTimeMillis()));
                Manifest.write(dir, signature);
                SnapshotFiles.write(dir, tarred);
                RecordVersion.write(dir, version);
                LOGGER.fine(() -> "Published files added to " + run);
                return;
            }
            byte[] content = published;
            if (filesChanged) {
                // the record saved here, marked so that other controllers fetch the files too even if it did not change
                content = (new String(content, StandardCharsets.UTF_8) + "\n<!-- files updated "
                                + System.currentTimeMillis() + " -->")
                        .getBytes(StandardCharsets.UTF_8);
            }
            // over the record read above (or a later edit of it), when there is one: a build deleted elsewhere since is
            // not brought back; else created, never written over one stored meanwhile (retried, as above, if it was)
            Long version = stored != null
                    ? writeOver(store, lease, recordKey, content, stored.version())
                    : Long.valueOf(publishRecord(store, lease, recordKey, content, PersistenceStore.ABSENT));
            if (version == null) {
                discardSnapshot(job, run.getNumber());
                recentlyPublished.remove(recordKey);
                LOGGER.fine(() -> "Not publishing the record of " + run + " saved here: deleted elsewhere");
                return;
            }
            recentlyPublished.put(
                    recordKey, new Published(new WeakReference<>(run), signature, System.currentTimeMillis()));
            Manifest.write(dir, signature);
            SnapshotFiles.write(dir, tarred);
            // a save made while publishing found no record yet, so republish left it to this: include it
            for (int i = 0; i < 10; i++) {
                byte[] now = Files.readAllBytes(record);
                if (Arrays.equals(now, published)) {
                    break;
                }
                version = writeOver(store, lease, recordKey, now, version);
                if (version == null) {
                    discardSnapshot(job, run.getNumber());
                    recentlyPublished.remove(recordKey);
                    LOGGER.fine(() -> "Not publishing " + run + " further: deleted elsewhere");
                    return;
                }
                published = now;
            }
            RecordVersion.write(dir, version);
        } catch (NoSuchFileException e) {
            if (!isDeleted(recordKey, run)) {
                throw e;
            } // else its directory went with it: unpublished below
        }
        // checked once every write is done: deleted while we wrote it (a build discarder runs as soon as builds
        // complete), its deletion removed what was written before, and this removes what was written after
        if (isDeleted(recordKey, run) || gone(job)) {
            store.delete(recordKey, PersistenceStore.ANY);
            discardSnapshot(job, run.getNumber());
            LOGGER.fine(() -> "Unpublished " + run + ": deleted while being published");
            return;
        }
        LOGGER.fine(() -> "Published " + run);
    }

    /**
     * Whether a job was deleted: it is no longer in its parent here. Its builds were (or are being) removed from the
     * stores, and a publication must not bring any back.
     */
    static boolean gone(@NonNull Job<?, ?> job) {
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) { // whether it exists, whoever asks
            return job.getParent().getItem(job.getName()) != job;
        }
    }

    /**
     * Writes a build record, while holding the build's lease if it has one (the store checks it with the write).
     *
     * @return its new version
     */
    private static long publishRecord(
            ClusterStore store,
            @CheckForNull RunningBuilds.Placeholder lease,
            String key,
            byte[] content,
            long expected)
            throws IOException {
        return lease == null
                ? store.write(key, content, expected)
                : store.leases().writeIfHeld(lease.getLeaseKey(), lease.getEpoch(), key, content, expected);
    }

    /**
     * Writes a build record over the version expected, or over a later one if it changed meanwhile (the build's own
     * completion racing a late save of it here), but never brings it back once deleted.
     *
     * @return its new version, or null if it was deleted (elsewhere: this controller has not applied that yet)
     */
    @CheckForNull
    private static Long writeOver(
            ClusterStore store,
            @CheckForNull RunningBuilds.Placeholder lease,
            String key,
            byte[] content,
            long expected)
            throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                return publishRecord(store, lease, key, content, expected);
            } catch (ConflictException x) {
                if (attempt == 5) {
                    throw x;
                }
                PersistenceStore.Entry e = store.read(key);
                if (e == null) {
                    return null;
                }
                expected = e.version();
            }
        }
    }

    /** A build published here recently, and the signature of its directory when it was. */
    private record Published(WeakReference<Run<?, ?>> run, String signature, long at) {}

    /** By record key. */
    static final PerItemCache<Published> recentlyPublished = PerItemCache.create();

    /** How long after publishing a build files added to its directory are still published. */
    static final long LATE_FILES_MILLIS = TimeUnit.HOURS.toMillis(1);

    /** Paths, sizes and modification times of what a snapshot would hold. */
    static String signature(File dir) {
        StringBuilder b = new StringBuilder();
        Path root = dir.toPath();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(Files::isRegularFile).sorted().forEach(f -> {
                String rel = root.relativize(f).toString().replace(File.separatorChar, '/');
                if (rel.equals(RECORD)
                        || rel.startsWith("archive/")
                        || rel.startsWith("workflow/")
                        || rel.startsWith("stashes/")
                        || rel.equals("program.dat")
                        || rel.endsWith(".tmp")
                        || rel.equals(Manifest.NAME)
                        || rel.equals(RecordVersion.NAME)
                        || rel.equals(SnapshotFiles.NAME)) {
                    return;
                }
                b.append(rel)
                        .append(' ')
                        .append(f.toFile().length())
                        .append(' ')
                        .append(f.toFile().lastModified())
                        .append('\n');
            });
        } catch (IOException | java.io.UncheckedIOException e) {
            return "";
        }
        return b.toString();
    }

    /**
     * Republishes recently completed builds whose directories changed without their record being saved, as when a
     * plugin adds a file to a completed build: their snapshots would otherwise miss it on other controllers.
     */
    @Extension
    public static final class LateFiles extends AsyncPeriodicWork {
        public LateFiles() {
            super("Files added to completed builds");
        }

        @Override
        public long getRecurrencePeriod() {
            return TimeUnit.SECONDS.toMillis(
                    Long.getLong(BuildRecords.class.getName() + ".lateFilesPeriodSeconds", 60));
        }

        /** Builds looked at by the broader scan per run, at most; the next run carries on. */
        static final int SCAN_LIMIT = Integer.getInteger(BuildRecords.class.getName() + ".lateFilesScanLimit", 1000);

        static final long SCAN_MILLIS = TimeUnit.MINUTES.toMillis(10);
        private long lastScan;
        private int scanFrom;

        @Override
        protected void execute(TaskListener listener) {
            if (store() == null) {
                return;
            }
            long now = System.currentTimeMillis();
            if (now - lastScan > SCAN_MILLIS) {
                lastScan = now;
                scanLoaded();
            }
            for (String key : recentlyPublished.keys()) {
                Published p = recentlyPublished.get(key);
                if (p == null) {
                    continue;
                }
                Run<?, ?> run = p.run().get();
                if (run == null
                        || now - p.at() > LATE_FILES_MILLIS
                        || isDeleted(key, run)
                        || !new File(run.getRootDir(), RECORD).isFile()) { // e.g. its job was deleted
                    recentlyPublished.remove(key);
                    continue;
                }
                if (!signature(run.getRootDir()).equals(p.signature())) {
                    try {
                        LOGGER.fine(() -> "Republishing " + run + ": files were added to it after it was published");
                        republishFiles(run);
                    } catch (IOException | RuntimeException x) {
                        LOGGER.log(Level.WARNING, "Could not republish " + run, x);
                    }
                }
            }
        }

        /**
         * Builds held in memory here, whichever controller published them and however long ago, whose directories
         * gained files since (compared with their {@link Manifest}): plugins add files through a loaded build.
         */
        private void scanLoaded() {
            List<Run<?, ?>> loaded = new ArrayList<>();
            for (Job<?, ?> job : Jenkins.get().allItems(Job.class)) {
                RunMap<?> runs = runsOf(job);
                if (runs != null && isShared(job)) {
                    loaded.addAll(runs.getLoadedBuilds().values());
                }
            }
            int n = loaded.size();
            int from = n == 0 ? 0 : scanFrom % n;
            int count = Math.min(n, SCAN_LIMIT);
            for (int i = 0; i < count; i++) {
                Run<?, ?> run = loaded.get((from + i) % n);
                if (run.isBuilding() || !Manifest.filesAdded(run.getRootDir())) {
                    continue;
                }
                try {
                    String key = recordKeyOf(run);
                    ClusterStore store = store();
                    if (key != null && store != null && store.read(key) != null && !isDeleted(key, run)) {
                        LOGGER.fine(() -> "Republishing " + run + ": files were added to it");
                        republishFiles(run);
                    }
                } catch (IOException | RuntimeException x) {
                    LOGGER.log(Level.WARNING, "Could not republish " + run, x);
                }
            }
            scanFrom = from + count;
        }

        @Override
        protected Level getNormalLoggingLevel() {
            return Level.FINE;
        }
    }

    @CheckForNull
    private static String recordKeyOf(Run<?, ?> run) {
        String prefix = prefix(run.getParent());
        return prefix == null ? null : Keys.buildRecord(prefix, run.getNumber());
    }

    /**
     * What a build directory held when it was last published or fetched here: {@link #signature} of it, in a file the
     * snapshot leaves out. Files added later (by a plugin, possibly long after the build) are told apart from it, also
     * after a restart and on a controller which did not publish the build.
     */
    static final class Manifest {
        static final String NAME = ".cluster-published";

        static void write(File dir, String signature) {
            try {
                Files.writeString(new File(dir, NAME).toPath(), signature, StandardCharsets.UTF_8);
            } catch (IOException e) {
                LOGGER.log(Level.FINE, "Could not record what " + dir + " holds", e);
            }
        }

        /** The files the directory held when the manifest was written (empty if there is none). */
        static Set<String> paths(File dir) {
            Set<String> paths = new HashSet<>();
            try {
                for (String line : Files.readString(new File(dir, NAME).toPath(), StandardCharsets.UTF_8)
                        .split("\n")) {
                    // path size modified
                    int modified = line.lastIndexOf(' ');
                    int size = modified < 0 ? -1 : line.lastIndexOf(' ', modified - 1);
                    if (size > 0) {
                        paths.add(line.substring(0, size));
                    }
                }
            } catch (IOException e) {
                // none
            }
            return paths;
        }

        /** Whether files were added or changed since the manifest was written (false if there is none). */
        static boolean filesAdded(File dir) {
            Path manifest = new File(dir, NAME).toPath();
            if (!Files.isRegularFile(manifest)) {
                return false;
            }
            try {
                return !Files.readString(manifest, StandardCharsets.UTF_8).equals(signature(dir));
            } catch (IOException e) {
                return false;
            }
        }
    }

    /**
     * The version of the stored record this controller's copy of a build is in sync with, kept in the build directory
     * (left out of the snapshot): a later save of the record here is written over that version only, never over an edit
     * made elsewhere since.
     */
    static final class RecordVersion {
        static final String NAME = ".cluster-record";

        static void write(File dir, long version) {
            try {
                Files.writeString(new File(dir, NAME).toPath(), Long.toString(version), StandardCharsets.UTF_8);
            } catch (IOException e) {
                LOGGER.log(Level.FINE, "Could not record the version of " + dir, e);
            }
        }

        /** The version, or {@link PersistenceStore#ANY} if unknown. */
        static long read(File dir) {
            try {
                return Long.parseLong(Files.readString(new File(dir, NAME).toPath(), StandardCharsets.UTF_8)
                        .trim());
            } catch (IOException | NumberFormatException e) {
                return PersistenceStore.ANY;
            }
        }
    }

    /**
     * The files of a build directory its snapshot holds, as last published or fetched here, in a file the snapshot
     * leaves out: when a later snapshot replaces them, only these are this controller's to remove. Everything else in
     * the directory (artifacts, what an {@link Exclusion} keeps out of snapshots, files added here since) is the only
     * copy there is, and is left alone.
     */
    static final class SnapshotFiles {
        static final String NAME = ".cluster-snapshot";

        static void write(File dir, @CheckForNull Set<String> paths) {
            if (paths == null) {
                return; // no snapshot (no blob store): nothing is known to have come from one
            }
            try {
                Files.writeString(new File(dir, NAME).toPath(), String.join("\n", paths), StandardCharsets.UTF_8);
            } catch (IOException e) {
                LOGGER.log(Level.FINE, "Could not record what the snapshot of " + dir + " holds", e);
            }
        }

        /** The paths, relative to the directory, or none if unknown. */
        static Set<String> read(File dir) {
            Set<String> paths = new TreeSet<>();
            try {
                for (String line : Files.readString(new File(dir, NAME).toPath(), StandardCharsets.UTF_8)
                        .split("\n")) {
                    if (!line.isEmpty()) {
                        paths.add(line);
                    }
                }
            } catch (IOException e) {
                // none
            }
            return paths;
        }
    }

    /**
     * Publishes a build which can no longer run anywhere (its controller died, nothing could resume it) as aborted, so
     * that it stays in the history with a console saying what happened, rather than vanishing.
     */
    static void publishLost(@NonNull Job<?, ?> job, int number, long startTime, @NonNull String reason)
            throws IOException {
        publishLost(job, number, startTime, reason, Result.ABORTED);
    }

    /**
     * As {@link #publishLost(Job, int, long, String)}, with the result the build had when it completed, for one whose
     * controller was gone before publishing its record.
     */
    static void publishLost(
            @NonNull Job<?, ?> job, int number, long startTime, @NonNull String reason, @NonNull Result result)
            throws IOException {
        String prefix = prefix(job);
        if (prefix == null) {
            return;
        }
        String recordKey = Keys.buildRecord(prefix, number);
        ClusterStore store = store();
        if (store == null) {
            return;
        }
        if (store.read(recordKey) != null) {
            return; // published meanwhile (e.g. it completed after all)
        }
        Class<?> buildClass = buildClassOf(job);
        if (buildClass == null) {
            LOGGER.warning(() -> "Cannot tell the build class of " + job + "; not closing #" + number);
            return;
        }
        String tag = Run.XSTREAM2.getMapper().serializedClass(buildClass);
        String record = "<?xml version='1.1' encoding='UTF-8'?>\n<" + tag + ">\n  <actions/>\n  <timestamp>" + startTime
                + "</timestamp>\n"
                + "  <startTime>" + startTime + "</startTime>\n  <result>" + result
                + "</result>\n  <duration>0</duration>\n"
                + "  <charset>UTF-8</charset>\n  <keepLog>false</keepLog>\n  <completed>true</completed>\n</" + tag
                + ">";
        ClusterBlobStore blobs = ClusterBlobStore.get();
        String lostSnapshot = blob(Keys.blobs(job), number, LOST_SNAPSHOT);
        if (blobs != null) {
            File builds = job.getBuildDir();
            Files.createDirectories(builds.toPath());
            Path dir = Files.createTempDirectory(builds.toPath(), ".lost-" + number);
            Path snapshot = Files.createTempFile(builds.toPath(), ".lost-" + number, ".tmp");
            try {
                // what the build had printed, if its log was shipped while it ran, then why it ended
                try (OutputStream log = Files.newOutputStream(dir.resolve("log"))) {
                    for (LostLog l : ExtensionList.lookup(LostLog.class)) {
                        try (InputStream shipped = l.shippedLog(job, number)) {
                            if (shipped != null) {
                                shipped.transferTo(log);
                                log.write('\n');
                                break;
                            }
                        } catch (IOException | RuntimeException x) {
                            LOGGER.log(
                                    Level.WARNING,
                                    "Could not read the shipped log of " + job.getFullName() + " #" + number,
                                    x);
                        }
                    }
                    log.write((reason + "\nFinished: " + result + "\n").getBytes(StandardCharsets.UTF_8));
                }
                try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(snapshot))) {
                    new FilePath(dir.toFile()).tar(out, new DirScanner.Glob("**", null, false));
                } catch (InterruptedException e) {
                    throw (IOException) new InterruptedIOException().initCause(e);
                }
                try (InputStream in = Files.newInputStream(snapshot)) {
                    blobs.put(lostSnapshot, in, Files.size(snapshot));
                }
            } finally {
                Files.deleteIfExists(snapshot);
                Util.deleteRecursive(dir.toFile());
            }
        }
        try {
            store.write(recordKey, record.getBytes(StandardCharsets.UTF_8), PersistenceStore.ABSENT);
            LOGGER.info(() -> "Closed " + job.getFullName() + " #" + number + " (" + result + "): " + reason);
            writtenElsewhere(job, number); // this controller's own write raises no event here
            // published here for the controller which ran it: update the shared permalinks, as it would have
            Run<?, ?> run = job.getBuildByNumber(number);
            if (run != null) {
                StoredPermalinks.completed(run);
            }
        } catch (ConflictException e) {
            LOGGER.fine(() -> job.getFullName() + " #" + number + " was published meanwhile");
            if (blobs != null && hasBlob(blobs, Keys.snapshotBlob(Keys.blobs(job), number))) {
                blobs.delete(lostSnapshot); // its owner's real record and snapshot stand
            } // else closed by another controller at the same time: its record goes with this stand-in (the same)
        }
    }

    private static boolean hasBlob(ClusterBlobStore blobs, String key) throws IOException {
        try (InputStream in = blobs.get(key)) {
            return in != null;
        }
    }

    /**
     * The log a build printed while it ran, kept outside its controller: when that controller dies and the build cannot
     * be resumed, the build is closed as aborted with this output in its console, not just the reason.
     */
    public interface LostLog extends ExtensionPoint {
        /**
         * @return the shipped log, or null if this extension kept none
         */
        @CheckForNull
        InputStream shippedLog(@NonNull Job<?, ?> job, int number) throws IOException;
    }

    @CheckForNull
    private static Class<?> buildClassOf(Job<?, ?> job) {
        for (Class<?> c = job.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Method m = c.getDeclaredMethod("getBuildClass");
                m.setAccessible(true);
                return (Class<?>) m.invoke(job);
            } catch (NoSuchMethodException e) {
                // look further up
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
        if (job instanceof LazyBuildMixIn.LazyLoadingJob<?, ?> lazy) {
            try {
                java.lang.reflect.Method m = LazyBuildMixIn.class.getDeclaredMethod("getBuildClass");
                m.setAccessible(true);
                return (Class<?>) m.invoke(lazy.getLazyBuildMixIn());
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Builds deleted here, by record key, with when, so that a publication still in progress does not bring them back.
     * Kept for {@link #DELETED_MILLIS} rather than up to a count: however many builds are deleted meanwhile (a discarder
     * sweeping a big job), a publication stalled in its upload still finds its build's marker. The marker of a build
     * being published is kept however old, until a later deletion after the publication.
     */
    private static final LinkedHashMap<String, Long> deleted = new LinkedHashMap<>();

    /** Publications in progress, by record key: how many. Guarded by {@link #deleted}. */
    private static final Map<String, Integer> publishing = new HashMap<>();

    static /* non-final for tests */ long DELETED_MILLIS = TimeUnit.HOURS.toMillis(1);

    private static boolean isDeleted(String recordKey, Run<?, ?> run) {
        synchronized (deleted) {
            Long marked = deleted.get(recordKey);
            return marked != null && marked >= run.getTimeInMillis();
        }
    }

    private static void markDeleted(String recordKey) {
        long now = System.currentTimeMillis();
        synchronized (deleted) {
            deleted.remove(recordKey);
            deleted.put(recordKey, now);
            for (var it = deleted.entrySet().iterator(); it.hasNext(); ) {
                var marker = it.next();
                if (marker.getValue() >= now - DELETED_MILLIS) {
                    break; // in the order marked: the rest are newer
                }
                if (!publishing.containsKey(marker.getKey())) {
                    it.remove();
                }
            }
        }
    }

    private static void discardSnapshot(Job<?, ?> job, int number) throws IOException {
        ClusterBlobStore blobs = ClusterBlobStore.get();
        if (blobs != null) {
            blobs.delete(Keys.snapshotBlob(Keys.blobs(job), number));
        }
    }

    /**
     * Republishes the record of a build already published, after it was modified (for example its description): over
     * the version this controller's copy is in sync with. If the record changed elsewhere since, the save is refused,
     * never written over that change, and the copy here is refreshed from the store.
     */
    static void republish(@NonNull Run<?, ?> run) throws IOException {
        String key = recordKeyOf(run);
        ClusterStore store = store();
        if (key == null || store == null) {
            return;
        }
        File dir = run.getRootDir();
        byte[] content = Files.readAllBytes(new File(dir, RECORD).toPath());
        PersistenceStore.Entry e = store.read(key);
        if (e == null) {
            return; // not published yet (publish will include this change), or deleted meanwhile: not brought back
        }
        long expected = RecordVersion.read(dir);
        RunMap<?> runs = runsOf(run.getParent());
        Run<?, ?> current = runs == null ? null : runs.getLoadedBuilds().get(run.getNumber());
        if (current != null && current != run) {
            // a build object from before this copy was refreshed (after a change made elsewhere), saved over the
            // current copy's record: not published, and the record the current copy holds is put back
            LOGGER.warning(() -> run + " was changed on another controller meanwhile; this change of it, made to an"
                    + " outdated copy, is not saved");
            if (expected == e.version()) {
                writeRecord(dir, e.content());
            }
            return;
        }
        try {
            if (expected == PersistenceStore.ANY) {
                // in sync with no stored version: a copy whose directory was refreshed since it was loaded, after a
                // change made elsewhere (every copy written or fetched here records its version)
                throw new ConflictException(key, expected, e.version());
            }
            RecordVersion.write(dir, store.write(key, content, expected));
            LOGGER.fine(() -> "Republished " + run);
        } catch (ConflictException x) {
            // changed elsewhere since this copy was in sync: that change stands (no last writer wins), and the copy
            // here, holding the refused change, is refreshed
            LOGGER.warning(() ->
                    run + " was changed on another controller meanwhile; this change of it is not saved, and it is"
                            + " reloaded from the store");
            Job<?, ?> job = run.getParent();
            int number = run.getNumber();
            Timer.get().submit(() -> {
                try {
                    PersistenceStores.applyingRemoteChange(() -> {
                        writtenElsewhere(job, number);
                        return null;
                    });
                } catch (IOException | RuntimeException y) {
                    LOGGER.log(Level.WARNING, "Could not refresh " + job.getFullName() + " #" + number, y);
                }
            });
        }
    }

    /** Refreshes this controller's copy of a build from the store, off the calling thread. */
    private static void refreshLater(Job<?, ?> job, int number) {
        Timer.get().submit(() -> {
            try {
                PersistenceStores.applyingRemoteChange(() -> {
                    writtenElsewhere(job, number);
                    return null;
                });
            } catch (IOException | RuntimeException y) {
                LOGGER.log(Level.WARNING, "Could not refresh " + job.getFullName() + " #" + number, y);
            }
        });
    }

    /**
     * Removes a build from the stores. Not a stale local copy of a build another controller took over: deleting that
     * would remove the new owner's build.
     */
    static void delete(@NonNull Run<?, ?> run) throws IOException {
        String key = recordKeyOf(run);
        if (key == null || RunningBuilds.wasLost(run) || RunningBuilds.wasDiscarded(run)) {
            return;
        }
        ClusterStore store = store();
        if (store == null) {
            return;
        }
        markDeleted(key);
        store.delete(key, PersistenceStore.ANY);
        ClusterBlobStore blobs = ClusterBlobStore.get();
        if (blobs != null) {
            blobs.delete(blobDir(Keys.blobs(run.getParent()), run.getNumber()));
        }
        LOGGER.fine(() -> "Deleted stored " + run);
    }

    /**
     * Creates the local directory of a build which so far exists only in the stores ({@link BuildFetches}: never read
     * under the build index lock, nor waited for under the queue's lock).
     *
     * @param dir the build directory, named after the build number
     * @param wait false to only start the fetch, for a caller which loads the build later
     * @return whether {@code dir} now holds the build's {@code build.xml}, and how
     */
    @NonNull
    static BuildFetches.Outcome materialize(@NonNull Job<?, ?> job, @NonNull File dir, boolean wait)
            throws IOException {
        String prefix = prefix(job);
        int number;
        try {
            number = Integer.parseInt(dir.getName());
        } catch (NumberFormatException e) {
            return BuildFetches.Outcome.NOT_FETCHED;
        }
        if (prefix == null || store() == null) {
            return BuildFetches.Outcome.NOT_FETCHED;
        }
        return BuildFetches.fetch(job, prefix, number, dir, wait);
    }

    /** Whether a path below a directory goes through a link there: never created, written or deleted through. */
    static boolean throughLink(Path root, Path target) {
        for (Path p = target.getParent(); p != null && !p.equals(root); p = p.getParent()) {
            if (Files.isSymbolicLink(p)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Adds to a build's directory the files of its shared snapshot that it lacks, and did not remove since it was last in
     * sync (its {@link Manifest}): files added to the build on another controller, which publishing this directory with
     * the files added here would otherwise drop. A file in both is kept as it is here.
     */
    private static void addFilesAddedElsewhere(String snapshotKey, File dir) throws IOException {
        ClusterBlobStore blobs = ClusterBlobStore.get();
        if (blobs == null) {
            return;
        }
        Set<String> before = Manifest.paths(dir);
        Path root = dir.toPath().toRealPath();
        Path shared = Files.createTempDirectory(dir.getParentFile().toPath(), "." + dir.getName() + "-shared");
        try {
            try (InputStream in = blobs.get(snapshotKey)) {
                if (in == null) {
                    return;
                }
                new FilePath(shared.toFile()).untarFrom(in, FilePath.TarCompression.GZIP);
            } catch (InterruptedException e) {
                throw (IOException) new InterruptedIOException().initCause(e);
            }
            removeLinks(shared);
            List<Path> files;
            try (Stream<Path> walk = Files.walk(shared)) {
                files = walk.filter(f -> Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS))
                        .toList();
            }
            for (Path f : files) {
                String rel = shared.relativize(f).toString().replace(File.separatorChar, '/');
                Path target = root.resolve(rel).normalize();
                if (rel.equals(RECORD)
                        || rel.equals(Manifest.NAME)
                        || rel.equals(RecordVersion.NAME)
                        || before.contains(rel)
                        || !target.startsWith(root)
                        || Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    continue; // the record, removed here since, or here too (kept as it is here)
                }
                Path parent = target.getParent();
                if (parent == null) {
                    continue; // the root itself: not a file
                }
                Files.createDirectories(parent);
                if (!parent.toRealPath().startsWith(root)) {
                    continue; // never through a link
                }
                Files.copy(f, target);
                LOGGER.fine(() -> "Keeping " + rel + " of " + dir + ", added elsewhere");
            }
        } finally {
            Util.deleteRecursive(shared.toFile());
        }
    }

    static void writeRecord(File dir, byte[] content) throws IOException {
        Path tmp = Files.createTempFile(dir.toPath(), RECORD, ".tmp");
        Files.write(tmp, content);
        try {
            Files.move(
                    tmp,
                    new File(dir, RECORD).toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(
                    tmp,
                    new File(dir, RECORD).toPath(),
                    StandardCopyOption.REPLACE_EXISTING); // as AtomicFileWriter does
        }
    }

    @CheckForNull
    static RunMap<?> runsOf(Job<?, ?> job) {
        return job instanceof LazyBuildMixIn.LazyLoadingJob<?, ?> lazy
                ? lazy.getLazyBuildMixIn()._getRuns()
                : null;
    }

    /**
     * Brings this controller up to date after another controller published or republished a build record.
     * A new build is only added to the index; it is materialized when first loaded.
     */
    static void writtenElsewhere(@NonNull Job<?, ?> job, int number) throws IOException {
        RunMap<?> runs = runsOf(job);
        String prefix = prefix(job);
        ClusterStore store = store();
        if (runs == null || prefix == null || store == null) {
            return;
        }
        BuildFetches.changed(Keys.buildRecord(prefix, number)); // a fetch of it in progress fetches it again
        Run<?, ?> running = runs.getLoadedBuilds().get(number);
        if (running != null && running.isBuilding()) {
            // a record of a build running here is stored only by a controller which took it over (this one cut off
            // meanwhile): never put over the running build, which is stopped as lost; its copy here is then discarded,
            // and the record recognised
            if (!RunningBuilds.wasLost(running)) {
                RunningBuilds.lost(running);
                RunningBuilds.discard(running);
            }
            return;
        }
        File dir = new File(job.getBuildDir(), String.valueOf(number));
        // under the lock materialization holds (RunMap.retrieve): one in progress is waited for and then refreshed,
        // rather than taken for not there yet and left with the record it read before this change
        Run<?, ?> toPublish = null;
        synchronized (runs) {
            if (new File(dir, RECORD).isFile()) {
                PersistenceStore.Entry record = store.read(Keys.buildRecord(prefix, number));
                if (record == null) {
                    return; // deleted since; the deletion event follows
                }
                if (record.version() == RecordVersion.read(dir)) {
                    return; // what this copy holds already (a repeated event, or this controller's own write)
                }
                if (Manifest.filesAdded(dir)) {
                    // files were added here since this copy was published or fetched: publish them (with the record as
                    // written elsewhere) rather than dropping them with the local copy
                    Run<?, ?> loaded = runs.getLoadedBuilds().get(number);
                    if (loaded != null) {
                        LOGGER.info(() -> job.getFullName() + " #" + number
                                + " changed elsewhere while files were added to it here; publishing them");
                        writeRecord(dir, record.content());
                        RecordVersion.write(dir, record.version());
                        toPublish = loaded;
                    }
                }
                if (toPublish == null) {
                    LOGGER.fine(() -> "Refreshing " + job.getFullName() + " #" + number + " changed elsewhere");
                    // its files may have changed too (a later snapshot): materialised again when next needed, over
                    // the directory, which keeps what no snapshot holds (artifacts, above all: their only copy)
                    runs.unload(number);
                    Files.deleteIfExists(new File(dir, RECORD).toPath());
                    Files.deleteIfExists(new File(dir, RecordVersion.NAME).toPath());
                    runs.recognizeNumber(number);
                }
            } else {
                LOGGER.fine(() -> "Recognizing " + job.getFullName() + " #" + number + " completed elsewhere");
                Files.createDirectories(dir.toPath());
                // a reference left unloadable (its materialization failed, or found no snapshot yet) is retried
                runs.unload(number);
                runs.recognizeNumber(number);
            }
        }
        if (toPublish != null) {
            // not under the lock: it downloads and uploads (keeping the files of the snapshot made elsewhere)
            republishFiles(toPublish);
            runs.unload(number); // loaded again with the record as written elsewhere
        }
        followNumber(job, number);
        StoredPermalinks.changedElsewhere(job);
    }

    /**
     * Moves the job's next build number past a build numbered elsewhere (completed, or adopted here), as the controller
     * which numbered it reports it. Numbers are drawn from the sequence in the store either way.
     */
    static void followNumber(@NonNull Job<?, ?> job, int number) {
        synchronized (job) {
            if (job.getNextBuildNumber() <= number) {
                job.fastUpdateNextBuildNumber(number + 1);
            }
        }
    }

    /** Whether a record of the build is stored. */
    static boolean isStored(@NonNull Job<?, ?> job, int number) throws IOException {
        String prefix = prefix(job);
        ClusterStore store = store();
        return prefix != null && store != null && store.read(Keys.buildRecord(prefix, number)) != null;
    }

    /**
     * Brings this controller up to date after another controller deleted a build.
     * Listeners are not notified: cleanup of external data happened where the build was deleted.
     */
    static void deletedElsewhere(@NonNull Job<?, ?> job, int number) throws IOException {
        RunMap<?> runs = runsOf(job);
        String prefix = prefix(job);
        ClusterStore store = store();
        if (runs == null || prefix == null || store == null) {
            return;
        }
        BuildFetches.changed(Keys.buildRecord(prefix, number));
        Run<?, ?> loaded = runs.getLoadedBuilds().get(number);
        if (loaded != null && loaded.isBuilding()) {
            return; // running here, so not what was deleted elsewhere (its record was never published)
        }
        if (store.read(Keys.buildRecord(prefix, number)) != null) {
            return; // stored again since: the event is stale, or the record was published again as it was deleted
        }
        LOGGER.fine(() -> "Forgetting " + job.getFullName() + " #" + number + " deleted elsewhere");
        forgetLocalCopy(job, number);
    }

    /** This controller's copy of a build, if loaded. */
    @CheckForNull
    static Run<?, ?> loadedCopy(@NonNull Job<?, ?> job, int number) {
        RunMap<?> runs = runsOf(job);
        return runs == null ? null : runs.getLoadedBuilds().get(number);
    }

    /** Drops this controller's copy of a build: the loaded object, and its directory. */
    static void forgetLocalCopy(@NonNull Job<?, ?> job, int number) throws IOException {
        RunMap<?> runs = runsOf(job);
        if (runs == null) {
            return;
        }
        runs.forget(number);
        Util.deleteRecursive(new File(job.getBuildDir(), String.valueOf(number)));
        StoredPermalinks.changedElsewhere(job);
    }

    /**
     * Drops this controller's copy of a build which ran here but is another controller's now: the loaded object, and
     * its directory. Artifacts archived here by that copy are in no snapshot, so their only copy is here: a directory
     * holding any is moved aside ({@code .<N>-discarded-<time>} next to it, which nothing loads) rather than deleted.
     */
    static void forgetLostCopy(@NonNull Job<?, ?> job, int number) throws IOException {
        RunMap<?> runs = runsOf(job);
        if (runs == null) {
            return;
        }
        runs.forget(number);
        File dir = new File(job.getBuildDir(), String.valueOf(number));
        if (holdsArtifacts(dir)) {
            Path aside = dir.toPath().resolveSibling("." + number + "-discarded-" + System.currentTimeMillis());
            Files.move(dir.toPath(), aside);
            LOGGER.warning(() -> job.getFullName() + " #" + number + " is another controller's now; the artifacts"
                    + " archived by its copy here are kept in " + aside);
        } else {
            Util.deleteRecursive(dir);
        }
        StoredPermalinks.changedElsewhere(job);
    }

    /** Whether a build directory holds archived artifacts (with Jenkins' default artifact manager). */
    static boolean holdsArtifacts(File dir) throws IOException {
        Path archive = new File(dir, "archive").toPath();
        if (!Files.isDirectory(archive, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try (Stream<Path> files = Files.walk(archive)) {
            return files.anyMatch(f -> !Files.isDirectory(f, LinkOption.NOFOLLOW_LINKS));
        }
    }

    /**
     * Scans a build directory for its own files only: symbolic links, and anything outside the directory, are left
     * out, so that a snapshot never carries what a link points to elsewhere on this controller, nor a link to recreate
     * on another one.
     */
    static final class OwnFiles extends DirScanner {
        private static final long serialVersionUID = 1L;
        private final DirScanner scanner;
        /** Where the relative paths of the files scanned are added, if anywhere. */
        @CheckForNull
        private final transient Set<String> scanned;

        OwnFiles(DirScanner scanner) {
            this(scanner, null);
        }

        OwnFiles(DirScanner scanner, @CheckForNull Set<String> scanned) {
            this.scanner = scanner;
            this.scanned = scanned;
        }

        @Override
        public void scan(File dir, FileVisitor visitor) throws IOException {
            Path root = dir.toPath().toRealPath();
            scanner.scan(dir, new FileVisitor() {
                @Override
                public void visit(File f, String relativePath) throws IOException {
                    Path path = f.toPath();
                    if (!Files.isSymbolicLink(path) && path.toRealPath().startsWith(root)) {
                        visitor.visit(f, relativePath);
                        if (scanned != null && f.isFile()) {
                            scanned.add(relativePath.replace(File.separatorChar, '/'));
                        }
                    } else {
                        LOGGER.fine(() -> "Leaving " + f + " out of the build snapshot: a link, or outside " + root);
                    }
                }

                @Override
                public boolean understandsSymlink() {
                    return true;
                }

                @Override
                public void visitSymlink(File link, String target, String relativePath) {
                    LOGGER.fine(() -> "Leaving link " + link + " out of the build snapshot");
                }
            });
        }
    }

    /** Deletes any symbolic links below a directory just materialized: a build's files are never links. */
    static void removeLinks(Path dir) throws IOException {
        List<Path> links;
        try (Stream<Path> paths = Files.walk(dir)) {
            links = paths.filter(Files::isSymbolicLink).toList();
        }
        for (Path link : links) {
            LOGGER.warning(() -> "Removing link " + link + " from a materialized build snapshot");
            Files.delete(link);
        }
    }

    /**
     * Loads a build; saves made meanwhile (for example data migrations) are not republished.
     */
    static <V, T extends Throwable> V loading(ThrowingCallable<V, T> body) throws T {
        Boolean old = LOADING.get();
        LOADING.set(Boolean.TRUE);
        try {
            return body.call();
        } finally {
            if (old == null) {
                LOADING.remove();
            }
        }
    }

    /** A build of a job, by the job's identity: what a retry finds the build by, whatever the job is called by then. */
    private record BuildId(String uuid, int number) {
        static BuildId of(Run<?, ?> run) {
            return new BuildId(run.getParent().getItemUuid(), run.getNumber());
        }

        @CheckForNull
        Run<?, ?> resolve() {
            Job<?, ?> job;
            try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
                job = ItemUuids.get(uuid) instanceof Job<?, ?> j ? j : null;
            }
            return job == null ? null : job.getBuildByNumber(number);
        }
    }

    /**
     * Completed builds whose record or placeholder removal failed (e.g. the store was unreachable): retried until both
     * are done. Until then the build stays "running" for the other controllers, rather than vanishing.
     */
    private static final Set<BuildId> unpublished = ConcurrentHashMap.newKeySet();

    /** Publishes a completed build's record, then removes its running placeholder; queues a retry if either fails. */
    static void complete(@NonNull Run<?, ?> run) {
        if (!isShared(run.getParent())) {
            return;
        }
        BuildId id = BuildId.of(run);
        // as on finalizing: a build lost to another controller meanwhile (a retry or a later completion of one whose
        // registration raced its completion) is that owner's to publish, and its placeholder that owner's
        if (RunningBuilds.wasLost(run) || RunningBuilds.wasDiscarded(run)) {
            unpublished.remove(id); // being discarded, or discarded already
            return;
        }
        if (!RunningBuilds.owns(run)) {
            LOGGER.warning(() -> "Not publishing " + run + ": it is owned by another controller");
            unpublished.remove(id);
            RunningBuilds.lost(run);
            RunningBuilds.discard(run);
            return;
        }
        boolean published;
        try {
            // a retry may find its directory refreshed from the store since (changed elsewhere once published): to be
            // materialized again, so without a record for now
            published = !new File(run.getRootDir(), RECORD).isFile() && isStored(run.getParent(), run.getNumber());
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Failed to publish " + run + "; will retry", e);
            unpublished.add(id);
            return;
        }
        if (!published && !new File(run.getRootDir(), RECORD).isFile()) {
            // nothing to publish: keep its placeholder rather than let it vanish everywhere without a record (it is
            // closed as aborted elsewhere once this controller is gone)
            LOGGER.warning(
                    () -> run + " completed without a " + RECORD + "; not publishing it, and keeping it as running");
            return;
        }
        try {
            if (!published) {
                publish(run);
            }
        } catch (LeaseLostException e) {
            // taken over while it was being published: the new owner publishes it; nothing of it is left from here
            LOGGER.warning(() -> "Not publishing " + run + ": " + e.getMessage());
            unpublished.remove(id);
            RunningBuilds.lost(run);
            RunningBuilds.discard(run);
            return;
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Failed to publish " + run + "; will retry", e);
            unpublished.add(id);
            return;
        }
        // after the record exists, so other controllers always see one or the other
        try {
            RunningBuilds.finished(run);
            unpublished.remove(id);
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Failed to remove running placeholder of " + run + "; will retry", e);
            unpublished.add(id);
        }
    }

    /**
     * Whether a build of a job completed here and is not published yet (nor its placeholder removed): for the other
     * controllers it is still running, so the job is building.
     */
    static boolean hasUnpublished(@NonNull Job<?, ?> job) {
        if (unpublished.isEmpty()) {
            return false;
        }
        String uuid = job.getItemUuid();
        return uuid != null && unpublished.stream().anyMatch(id -> id.uuid().equals(uuid));
    }

    /**
     * Completes a build again a little later: one registered as running as it completed, so that its completion here
     * (publication, then the placeholder's removal) may have been under way without that placeholder. Idempotent.
     */
    static void completeLater(@NonNull Run<?, ?> run) {
        Timer.get()
                .schedule(
                        () -> {
                            try (ACLContext ctx = ACL.as2(ACL.SYSTEM2)) {
                                complete(run);
                            }
                        },
                        COMPLETE_AGAIN_SECONDS,
                        TimeUnit.SECONDS);
    }

    static /* non-final for tests */ long COMPLETE_AGAIN_SECONDS = 15;

    /** Placeholders of builds deleted before they were published whose removal failed: retried with the publications. */
    private static final Set<RunningBuilds.Placeholder> unfinished = ConcurrentHashMap.newKeySet();

    /** Retries {@link #complete} for builds whose publication failed. */
    @Extension
    public static final class Unpublished extends AsyncPeriodicWork {
        public Unpublished() {
            super("Completed builds not yet published");
        }

        @Override
        public long getRecurrencePeriod() {
            return TimeUnit.SECONDS.toMillis(15);
        }

        @Override
        protected void execute(TaskListener listener) {
            listAgain();
            for (RunningBuilds.Placeholder p : List.copyOf(unfinished)) {
                try {
                    RunningBuilds.finished(p);
                    unfinished.remove(p);
                } catch (IOException | RuntimeException x) {
                    LOGGER.log(
                            Level.FINE, "Still cannot remove the placeholder " + p.getKey() + " of a deleted build", x);
                }
            }
            for (BuildId id : List.copyOf(unpublished)) {
                Run<?, ?> run = id.resolve();
                if (run == null || RunningBuilds.wasLost(run)) {
                    unpublished.remove(id);
                    continue;
                }
                LOGGER.info(() -> "Retrying the publication of " + run);
                complete(run);
            }
        }

        @Override
        protected Level getNormalLoggingLevel() {
            return Level.FINE;
        }
    }

    static int unpublishedCount() {
        return unpublished.size();
    }

    @Extension
    public static final class Publisher extends RunListener<Run<?, ?>> {

        @Override
        public void onFinalized(Run<?, ?> run) {
            if (PersistenceStores.isApplyingRemoteChange() || !isShared(run.getParent())) {
                return;
            }
            if (RunningBuilds.wasLost(run) || !RunningBuilds.owns(run)) {
                // another controller has taken it over and will publish it
                LOGGER.warning(() -> "Not publishing " + run + ": it is owned by another controller");
                RunningBuilds.lost(run); // fenced until discarded: a save of this copy meanwhile is not republished
                RunningBuilds.discard(run);
                return;
            }
            complete(run);
        }

        @Override
        public void onDeleted(Run<?, ?> run) {
            if (PersistenceStores.isApplyingRemoteChange() || !isShared(run.getParent())) {
                return;
            }
            try {
                delete(run);
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.WARNING, "Failed to delete stored " + run, e);
            }
            // completed here and not published yet (the store was unreachable): no retry publishes it now, so its
            // placeholder goes too, or it would show as running elsewhere, and be closed into a record once this
            // controller is gone
            unpublished.remove(BuildId.of(run));
            RunningBuilds.Placeholder placeholder = RunningBuilds.ownedPlaceholder(run);
            if (placeholder != null && !run.isLogUpdated()) {
                try {
                    RunningBuilds.finished(placeholder);
                } catch (IOException | RuntimeException e) {
                    LOGGER.log(Level.WARNING, "Failed to remove the placeholder of deleted " + run + "; will retry", e);
                    unfinished.add(placeholder);
                }
            }
        }
    }

    /**
     * Removes everything stored about a job deleted here (not when applying a deletion made elsewhere: the controller
     * making it does this): its build records, permalinks, number sequence, placeholders and blobs. A job made again with
     * the name has another identity, and nothing of this. Done off the deleting thread; what fails is left to
     * {@link Orphans}.
     */
    @Extension
    public static final class ItemDeleted extends ItemListener {
        @Override
        public void onDeleted(Item item) {
            // folders delete their children one by one, so each job gets here
            if (!(item instanceof Job<?, ?> job) || PersistenceStores.isApplyingRemoteChange() || !isShared(job)) {
                return;
            }
            String prefix = Keys.item(job);
            String blobPrefix = Keys.blobs(job);
            String name = job.getFullName();
            Timer.get().submit(() -> deleteStored(prefix, blobPrefix, name));
        }
    }

    /** Items whose stored data is being deleted: written before, removed once all of it is gone. */
    static final String DELETING = "_deleting";

    static void deleteStored(String prefix, String blobPrefix, String name) {
        ClusterStore store = store();
        if (store == null) {
            return;
        }
        String uuid = prefix.substring(prefix.lastIndexOf('/') + 1);
        String marker = DELETING + "/" + uuid;
        try {
            store.write(
                    marker, (prefix + "\n" + blobPrefix + "\n").getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
            store.deleteAll(prefix);
            ClusterBlobStore blobs = ClusterBlobStore.get();
            if (blobs != null) {
                blobs.delete(blobPrefix);
            }
            store.delete(marker, PersistenceStore.ANY);
            LOGGER.fine(() -> "Deleted what was stored about " + name);
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Failed to delete what was stored about " + name + "; it is retried later", e);
        } finally {
            PerItemCache.forgetAll(prefix); // again: anything cached while it was deleted
        }
    }

    /**
     * Finishes deleting what is stored about deleted items whose cleanup failed, or whose controller died during it
     * (their {@link #DELETING} marker is still there), once per cluster, every few minutes. And, less often, deletes
     * what is stored about items that no longer exist anywhere although nothing marked them deleted (their deletion
     * failed before the marker was written, or something was written for them as they were deleted): see
     * {@link #sweepUnknown}.
     */
    @ClusterSingleton
    @Extension
    public static final class Orphans extends AsyncPeriodicWork {
        public Orphans() {
            super("Stored data of deleted items");
        }

        @Override
        public long getRecurrencePeriod() {
            return TimeUnit.MINUTES.toMillis(5);
        }

        private long lastUnknownSweep;

        @Override
        protected void execute(TaskListener listener) throws IOException {
            ClusterStore store = store();
            if (store == null) {
                return;
            }
            for (String uuid : store.children(DELETING)) {
                PersistenceStore.Entry e = store.read(DELETING + "/" + uuid);
                String[] lines =
                        e == null ? new String[0] : new String(e.content(), StandardCharsets.UTF_8).split("\n");
                if (lines.length >= 2 && lines[0].equals(Keys.itemByUuid(uuid))) {
                    LOGGER.info(() -> "Finishing the deletion of what was stored about item " + uuid);
                    deleteStored(lines[0], lines[1], uuid);
                }
            }
            long now = System.currentTimeMillis();
            if (now - lastUnknownSweep >= UNKNOWN_SWEEP_MILLIS) {
                lastUnknownSweep = now;
                sweepUnknown(store, now);
            }
        }

        @Override
        protected Level getNormalLoggingLevel() {
            return Level.FINE;
        }
    }

    /** How often items unknown everywhere are looked for. */
    static /* non-final for tests */ long UNKNOWN_SWEEP_MILLIS =
            Long.getLong(BuildRecords.class.getName() + ".unknownItemsSweepMinutes", 60) * 60_000;

    /**
     * How long what is stored about an item must be seen to belong to no item, unchanged, before it is deleted: an item
     * being created, or moved, is loaded everywhere well within that.
     */
    static /* non-final for tests */ long UNKNOWN_GRACE_MILLIS =
            Long.getLong(BuildRecords.class.getName() + ".unknownItemsGraceMinutes", 60) * 60_000;

    /** What a sweep saw of an item's stored data that belonged to no item: when, and what it held then. */
    private record Unknown(long since, String fingerprint) {}

    /** By identity; kept by the controller sweeping (lost with it: the next one starts the grace period again). */
    private static final Map<String, Unknown> unknown = new ConcurrentHashMap<>();

    /**
     * Deletes what is stored below {@code _items/<uuid>} (and the blobs of that item) for identities no item has, here
     * nor in the store, in two sweeps at least {@link #UNKNOWN_GRACE_MILLIS} apart, during which nothing of it changed.
     * Never anything of an item that exists: one loaded here is known by its identity; one stored but not loaded here
     * (it failed to load, or is being created or moved) has its stored configuration read for it, and if any such
     * configuration does not tell its identity, nothing is deleted.
     */
    static void sweepUnknown(ClusterStore store, long now) throws IOException {
        Set<String> stored = store.children(Keys.ITEMS); // listed first: an item's configuration is stored before this
        Set<String> deleting = store.children(DELETING);
        Set<String> known = new HashSet<>();
        Set<String> loadedConfigs = new HashSet<>();
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            for (Item item : Jenkins.get().allItems()) {
                String uuid = item.getItemUuid();
                if (uuid != null) {
                    known.add(uuid);
                }
                String key = PersistenceStores.keyFor(
                        hudson.model.Items.getConfigFile(item).getFile());
                if (key != null) {
                    loadedConfigs.add(key);
                }
            }
        }
        Set<String> candidates = new TreeSet<>(stored);
        candidates.removeAll(known);
        candidates.removeAll(deleting); // being deleted, or left to the marker's sweep
        unknown.keySet().retainAll(candidates);
        if (candidates.isEmpty()) {
            return;
        }
        // items stored but not loaded here: their identities, from their configuration
        List<String> configs = new ArrayList<>();
        storedConfigs(store, "jobs", configs);
        for (String key : configs) {
            if (loadedConfigs.contains(key)) {
                continue;
            }
            PersistenceStore.Entry e = store.read(key);
            if (e == null) {
                continue; // deleted (or moved) meanwhile
            }
            java.util.regex.Matcher m = ITEM_UUID.matcher(new String(e.content(), StandardCharsets.UTF_8));
            if (!m.find()) {
                LOGGER.fine(() -> "Not sweeping stored data of unknown items: " + key
                        + " is not loaded here, and does not tell its identity");
                unknown.clear();
                return;
            }
            candidates.remove(m.group(1).trim());
        }
        for (String uuid : candidates) {
            String prefix = Keys.itemByUuid(uuid);
            String fingerprint = fingerprint(store, prefix);
            Unknown seen = unknown.get(uuid);
            if (seen == null || !seen.fingerprint().equals(fingerprint)) {
                unknown.put(uuid, new Unknown(now, fingerprint)); // first seen, or changed since: wait again
                continue;
            }
            if (now - seen.since() < UNKNOWN_GRACE_MILLIS) {
                continue;
            }
            unknown.remove(uuid);
            LOGGER.info(() -> "Deleting what is stored about item " + uuid + ", which exists nowhere");
            deleteStored(prefix, Keys.ITEM_BLOBS + "/" + uuid, uuid);
        }
    }

    private static final java.util.regex.Pattern ITEM_UUID =
            java.util.regex.Pattern.compile("<itemUuid>([^<]+)</itemUuid>");

    /** The keys of the stored configurations of items below a key (items, and items in folders, at any depth). */
    private static void storedConfigs(ClusterStore store, String key, List<String> configs) throws IOException {
        for (String child : store.children(key)) {
            String k = key + "/" + child;
            if (child.equals("config.xml")) {
                configs.add(k);
            } else {
                storedConfigs(store, k, configs);
            }
        }
    }

    /** What an item's stored data holds, coarsely: its children, its next build number, and its running builds. */
    private static String fingerprint(ClusterStore store, String prefix) throws IOException {
        PersistenceStore.Entry next = store.read(Keys.nextBuildNumber(prefix));
        return new TreeSet<>(store.children(prefix)) + " " + (next == null ? "-" : next.version()) + " "
                + new TreeSet<>(store.children(Keys.running(prefix))) + " "
                + new TreeSet<>(store.children(Keys.builds(prefix)));
    }

    @Extension
    public static final class Republisher extends SaveableListener {

        @Override
        public void onChange(Saveable o, XmlFile file) {
            if (!(o instanceof Run<?, ?> run)
                    || PersistenceStores.isApplyingRemoteChange()
                    || !isShared(run.getParent())) {
                return;
            }
            if (RunningBuilds.wasDiscarded(run)) {
                // a copy of a build lost to another controller, discarded here but still referenced, saved its
                // build.xml
                // again: never published, and dropped again, as it would stand for the new owner's record here
                RunningBuilds.discardAgain(run);
                return;
            }
            if (run.isLogUpdated() || LOADING.get() != null || RunningBuilds.wasLost(run)) {
                return;
            }
            try {
                String key = recordKeyOf(run);
                ClusterStore store = store();
                if (key != null && store != null && Manifest.filesAdded(run.getRootDir()) && store.read(key) != null) {
                    publish(run, true, true); // files were added too: the record alone would leave them out
                } else {
                    republish(run);
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.WARNING, "Failed to republish " + run, e);
            }
        }
    }
}
