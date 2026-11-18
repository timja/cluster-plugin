package io.jenkins.plugins.cluster.builds;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.FilePath;
import hudson.Util;
import hudson.model.Job;
import hudson.model.Queue;
import hudson.model.RunMap;
import hudson.util.DaemonThreadFactory;
import hudson.util.NamingThreadFactory;
import io.jenkins.plugins.cluster.PerItemCache;
import io.jenkins.plugins.cluster.spi.ClusterBlobStore;
import io.jenkins.plugins.cluster.spi.ClusterStore;
import io.jenkins.plugins.cluster.spi.Keys;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import jenkins.model.Jenkins;
import jenkins.persistence.PersistenceStore;
import jenkins.util.Timer;

/**
 * Fetches the builds other controllers made into their local directories ({@link BuildRecords#materialize}), when they
 * are first loaded here.
 *
 * <p>A build is loaded under its job's build index lock, and often under the queue's lock too (the queue asks a job for
 * its last build). Its record and snapshot are read, and the snapshot extracted, off those locks: into a directory next
 * to the build's, on a thread of their own. Under the build index lock, the fetch only checks that no change of the
 * build's record was told since it read the record ({@link #changes}; else it is fetched again), and moves the files in.
 * A load waits for its fetch with the build index lock released ({@link Object#wait}): another load of the build may have
 * loaded it meanwhile ({@link Outcome#WAITED}), or its index entry been reset ({@link Outcome#WAITED_RESET}), and there is
 * only ever one instance of a build. A load under the queue's lock does not wait: core takes the build for not loadable; its index entry is reset
 * right after ({@link #deferred}), so that a load not under the queue's lock waits for the fetch, and again once fetched
 * (the queue is looked at again).
 */
final class BuildFetches {

    private static final Logger LOGGER = Logger.getLogger(BuildRecords.class.getName());

    private BuildFetches() {}

    private static final ExecutorService FETCHERS = Executors.newFixedThreadPool(
            4, new NamingThreadFactory(new DaemonThreadFactory(), "Builds fetched from other controllers"));

    /** How long a load waits for its build to be fetched. */
    static final long WAIT_MILLIS = TimeUnit.MINUTES.toMillis(5);

    /** Fetches in progress, by record key. */
    private static final Map<String, CompletableFuture<Boolean>> fetching = new ConcurrentHashMap<>();

    /**
     * Records whose load gave up under the queue's lock, by record key: core took the build for not loadable, so its index
     * entry is reset ({@link RunMap#unload}) right after that load, else once fetched.
     */
    private static final Set<String> deferred = ConcurrentHashMap.newKeySet();

    /** Changes of build records told here (written or deleted elsewhere), by record key: only generations are kept. */
    private static final PerItemCache<Boolean> changes = PerItemCache.create();

    /** Resets of builds' index entries ({@link #reset}), by record key: only generations are kept. */
    private static final PerItemCache<Boolean> resets = PerItemCache.create();

    /** Builds found without a stored snapshot, by record key: told once. */
    private static final Set<String> missingSnapshots = ConcurrentHashMap.newKeySet();

    /** Tells of a change of a build's record elsewhere: a fetch of it in progress fetches it again. */
    static void changed(@NonNull String recordKey) {
        changes.changed(recordKey);
    }

    /** Whether this thread holds the queue's lock: then it must not wait for the store. */
    static boolean queueLockHeld() {
        Jenkins j = Jenkins.getInstanceOrNull();
        return j != null && j.getQueue().isLockHeldByCurrentThread();
    }

    /** How a {@link #fetch} ended. */
    enum Outcome {
        /** The record is in the build directory, which the build index lock was held for throughout. */
        FETCHED,
        /**
         * The record is in the build directory, fetched while this thread waited with the build index lock released:
         * another load of the build may have loaded it meanwhile, which is then the instance to use.
         */
        WAITED,
        /**
         * As {@link #WAITED}, and the build's index entry was reset meanwhile (a load under the queue's lock gave up on
         * it): the entry this load is for is no longer the index's, so the build is to be loaded through the index.
         */
        WAITED_RESET,
        /** Fetched in the background, not waited for: the build is loaded once fetched. */
        LATER,
        /** Not fetched: not stored, say. */
        NOT_FETCHED
    }

    /**
     * Fetches a build into its directory, waiting for it unless this thread holds the queue's lock or {@code wait} is
     * false.
     *
     * @param wait false if the caller loads the build later (it does not take the build for not loadable meanwhile)
     */
    @NonNull
    static Outcome fetch(@NonNull Job<?, ?> job, @NonNull String prefix, int number, @NonNull File dir, boolean wait)
            throws IOException {
        String key = Keys.buildRecord(prefix, number);
        boolean queueLocked = wait && queueLockHeld();
        if (queueLocked) {
            // before the fetch starts: it moves the files in under the build index lock, which this thread holds until
            // its load has given up; the index entry is reset once that load returned (or by the fetch)
            deferred.add(key);
            resetLater(job, number, key);
        }
        CompletableFuture<Boolean> mine = new CompletableFuture<>();
        CompletableFuture<Boolean> running = fetching.putIfAbsent(key, mine);
        if (running == null) {
            running = mine;
            FETCHERS.execute(() -> {
                boolean done = false;
                try {
                    done = fetchAndMoveIn(job, prefix, number, dir, key);
                } catch (IOException | RuntimeException e) {
                    LOGGER.log(Level.WARNING, "Could not fetch " + job.getFullName() + " #" + number, e);
                } finally {
                    if (!done) {
                        deferred.remove(key); // not loadable (the build is not stored, say): as core remembers it
                    }
                    fetching.remove(key, mine);
                    Object lock = lockOf(job);
                    synchronized (lock) {
                        lock.notifyAll();
                    }
                    mine.complete(done);
                }
            });
        }
        if (queueLocked || !wait) {
            LOGGER.fine(() -> job.getFullName() + " #" + number + " is loaded once fetched: "
                    + (queueLocked ? "the queue does not wait" : "not waited for"));
            return Outcome.LATER;
        }
        if (running.isDone()) {
            return new File(dir, BuildRecords.RECORD).isFile() ? Outcome.FETCHED : Outcome.NOT_FETCHED;
        }
        Object lock = lockOf(job);
        long reset = resets.generation(key);
        await(running, lock);
        if (new File(dir, BuildRecords.RECORD).isFile()) {
            return resets.generation(key) == reset ? Outcome.WAITED : Outcome.WAITED_RESET;
        }
        if (!running.isDone() && Thread.holdsLock(lock)) {
            // given up waiting: core takes the build for not loadable; its index entry is reset once fetched
            deferred.add(key);
            return Outcome.LATER;
        }
        return Outcome.NOT_FETCHED;
    }

    /**
     * Resets a build's index entry once the load that gave up on it under the queue's lock returned (it holds the build
     * index lock until then), unless the fetch did already: a load not under the queue's lock then waits for the fetch.
     */
    private static void resetLater(Job<?, ?> job, int number, String key) {
        Timer.get().execute(() -> reset(job, number, key));
    }

    /** Resets a build's index entry if a load gave up on it since it was last reset. */
    private static void reset(Job<?, ?> job, int number, String key) {
        Object lock = lockOf(job);
        synchronized (lock) {
            if (deferred.remove(key) && lock instanceof RunMap<?> runs) {
                resets.changed(key); // before the entry is replaced: a load waiting meanwhile then sees it
                runs.unload(number); // taken for not loadable by that load: loaded on its next access
            }
        }
    }

    /** For tests: whether the index entry of a build whose load gave up under the queue's lock is yet to be reset. */
    static boolean isDeferred(@NonNull String recordKey) {
        return deferred.contains(recordKey);
    }

    /** The lock a build of a job is loaded under: its build index. */
    @NonNull
    private static Object lockOf(@NonNull Job<?, ?> job) {
        RunMap<?> runs = BuildRecords.runsOf(job);
        return runs != null ? runs : job;
    }

    /** Waits for a fetch, with the lock it moves the files in under released if this thread holds it. */
    private static void await(CompletableFuture<Boolean> fetch, Object lock) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MILLIS);
        try {
            while (!fetch.isDone() && System.nanoTime() < deadline) {
                if (Thread.holdsLock(lock)) {
                    lock.wait(1000); // released meanwhile; notified when the fetch is done
                } else {
                    try {
                        fetch.get(1, TimeUnit.SECONDS);
                    } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException e) {
                        // again, or done
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw (IOException) new InterruptedIOException().initCause(e);
        }
    }

    /** A record, and the files of its snapshot extracted next to the build directory (none without a blob store). */
    private record Fetched(
            @NonNull PersistenceStore.Entry record,
            @CheckForNull Path files) {}

    private static boolean fetchAndMoveIn(Job<?, ?> job, String prefix, int number, File dir, String key)
            throws IOException {
        ClusterStore store = BuildRecords.store();
        if (store == null) {
            return false;
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            long generation = changes.generation(key);
            Fetched fetched = fetch(store, job, prefix, number, dir, key);
            if (fetched == null) {
                return false;
            }
            try {
                Object lock = lockOf(job);
                synchronized (lock) {
                    if (changes.generation(key) != generation) {
                        LOGGER.fine(() -> job.getFullName() + " #" + number + " changed while it was fetched; again");
                        continue;
                    }
                    if (!new File(dir, BuildRecords.RECORD).isFile()) {
                        moveIn(fetched, dir);
                    }
                    // under the same lock, before any load can find the record: a load gave up meanwhile
                    reset(job, number, key);
                }
                LOGGER.fine(() -> "Materialized " + job.getFullName() + " #" + number);
                Queue q = Jenkins.get().getQueue();
                q.scheduleMaintenance(); // e.g. an item which looked at this build before it was fetched
                return true;
            } finally {
                if (fetched.files() != null) {
                    Util.deleteRecursive(fetched.files().toFile());
                }
            }
        }
        LOGGER.info(() ->
                job.getFullName() + " #" + number + " kept changing while it was fetched; fetched when next loaded");
        deferred.remove(key);
        return false;
    }

    /** Reads a build's record and extracts its snapshot next to its directory; null if either is not stored. */
    @CheckForNull
    private static Fetched fetch(ClusterStore store, Job<?, ?> job, String prefix, int number, File dir, String key)
            throws IOException {
        PersistenceStore.Entry record = store.read(key);
        if (record == null) {
            return null;
        }
        ClusterBlobStore blobs = ClusterBlobStore.get();
        if (blobs == null) {
            return new Fetched(record, null);
        }
        String blobPrefix = Keys.blobs(job);
        InputStream snapshot = blobs.get(Keys.snapshotBlob(blobPrefix, number));
        if (snapshot == null) {
            snapshot =
                    blobs.get(BuildRecords.blob(blobPrefix, number, BuildRecords.LOST_SNAPSHOT)); // only the stand-in
        }
        if (snapshot == null) {
            // a record is stored only once its snapshot is: one stored before a blob store was configured, or the
            // store restored to an earlier point than the blob store, say; left unmaterialized (not loadable, which
            // core remembers) rather than taken as complete without its files. Told once per build
            Level level = missingSnapshots.add(key) ? Level.INFO : Level.FINE;
            LOGGER.log(
                    level,
                    () -> job.getFullName() + " #" + number
                            + " has a stored record but no stored snapshot; it cannot be opened here");
            return null;
        }
        Files.createDirectories(dir.getParentFile().toPath());
        Path staged = Files.createTempDirectory(dir.getParentFile().toPath(), "." + dir.getName() + "-fetched");
        try (InputStream in = snapshot) {
            new FilePath(staged.toFile()).untarFrom(in, FilePath.TarCompression.GZIP);
            BuildRecords.removeLinks(staged);
            return new Fetched(record, staged);
        } catch (IOException | InterruptedException | RuntimeException e) {
            Util.deleteRecursive(staged.toFile());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                throw (IOException) new InterruptedIOException().initCause(e);
            }
            throw e instanceof IOException io ? io : new IOException(e);
        }
    }

    /**
     * Moves a fetched build into its directory, over what it holds: the snapshot's files replace those of the same name,
     * and nothing else there is removed (artifacts, and whatever else no snapshot holds, have no other copy), except the
     * files the snapshot it replaces had, and this one no longer has ({@link BuildRecords.SnapshotFiles}: removed
     * elsewhere). A link is never followed, in the snapshot nor in the directory. The record goes last: a local
     * {@code build.xml} means the directory is complete.
     */
    private static void moveIn(Fetched fetched, File dir) throws IOException {
        Files.createDirectories(dir.toPath());
        if (fetched.files() != null) {
            Path root = dir.toPath().toRealPath();
            Set<String> shared = new TreeSet<>();
            List<Path> files;
            try (Stream<Path> walk = Files.walk(fetched.files())) {
                files = walk.filter(f -> Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS))
                        .toList();
            }
            for (Path f : files) {
                String rel = fetched.files().relativize(f).toString().replace(File.separatorChar, '/');
                Path target = root.resolve(rel).normalize();
                if (rel.equals(BuildRecords.RECORD)
                        || rel.equals(BuildRecords.Manifest.NAME)
                        || rel.equals(BuildRecords.RecordVersion.NAME)
                        || rel.equals(BuildRecords.SnapshotFiles.NAME)
                        || rel.startsWith("archive/")
                        || !target.startsWith(root)
                        || target.equals(root)) {
                    continue; // what this controller keeps itself, artifacts (in no snapshot), or not of the directory
                }
                if (BuildRecords.throughLink(root, target)) {
                    LOGGER.warning(() -> "Not extracting " + rel + " into " + dir + ": through a link");
                    continue;
                }
                Files.createDirectories(target.getParent());
                if (!target.getParent().toRealPath().startsWith(root)
                        || Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                    LOGGER.warning(() -> "Not extracting " + rel + " into " + dir + ": through a link, or a directory");
                    continue;
                }
                Files.move(f, target, StandardCopyOption.REPLACE_EXISTING); // a link there is replaced, not followed
                shared.add(rel);
            }
            for (String rel : BuildRecords.SnapshotFiles.read(dir)) {
                Path old = root.resolve(rel).normalize();
                if (!shared.contains(rel)
                        && old.startsWith(root)
                        && !old.equals(root)
                        && !BuildRecords.throughLink(root, old)
                        && Files.isRegularFile(old, LinkOption.NOFOLLOW_LINKS)) {
                    Files.delete(old);
                }
            }
            BuildRecords.SnapshotFiles.write(dir, shared);
        }
        BuildRecords.Manifest.write(dir, BuildRecords.signature(dir)); // as published: files added later are told apart
        BuildRecords.RecordVersion.write(dir, fetched.record().version());
        BuildRecords.writeRecord(dir, fetched.record().content());
    }
}
