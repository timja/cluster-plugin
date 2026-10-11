package io.jenkins.plugins.cnj.store;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.console.ConsoleNote;
import hudson.model.Action;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.TransientActionFactory;
import jenkins.persistence.BuildRecords;
import jenkins.persistence.PersistenceStore;
import jenkins.persistence.PersistenceStores;
import jenkins.persistence.RunningBuilds;
import jenkins.util.Timer;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;
import org.jenkinsci.plugins.workflow.log.FileLogStorage;
import org.jenkinsci.plugins.workflow.log.LogStorage;
import org.jenkinsci.plugins.workflow.log.LogStorageFactory;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;

/**
 * Pipeline logs in blob storage, so that every controller can read them.
 *
 * <p>While a build runs here, it logs to local files through {@link FileLogStorage} as usual
 * ({@code log} and {@code log-index}, which carries the step spans), and a shipper appends whatever
 * was added to each file to an append blob every {@link #SHIP_PERIOD_MILLIS}
 * ({@code jobs/<item>/builds/<N>/log} and {@code .../log-index}), and once more when the build is finalized,
 * before its record is published.
 * A controller without the local files (another controller, or a fresh one) downloads both blobs into the
 * build directory and reads them with {@link FileLogStorage}, so its parsing is reused unchanged.
 * Those files are left out of the build snapshot.
 *
 * <p>Other backends (CloudWatch, Log Analytics, Elasticsearch) would replace this class with their own
 * {@link LogStorageFactory}, keyed by job and build number.
 */
public final class BlobLogStorage {

    private static final Logger LOGGER = Logger.getLogger(BlobLogStorage.class.getName());

    static final String LOG = "log";
    static final String INDEX = "log-index";

    static final long SHIP_PERIOD_MILLIS = Long.getLong(BlobLogStorage.class.getName() + ".shipPeriodMillis", 1_000);
    /** Most bytes shipped from one file per period, to bound memory. */
    private static final int MAX_SHIP = 16 * 1024 * 1024;

    private BlobLogStorage() {}

    @CheckForNull
    static AzureBlobStore blobs() {
        return PersistenceStores.getBlobStore() instanceof AzureBlobStore a ? a : null;
    }

    /**
     * The key of a build directory whose builds are shared, such as {@code jobs/p/builds/5}, or null.
     */
    @CheckForNull
    static String buildKey(@NonNull File buildDir) {
        PersistenceStore store = PersistenceStores.get();
        String key = PersistenceStores.keyFor(buildDir);
        if (store == null || key == null || !key.matches("jobs/.+/builds/[0-9]+")) {
            return null;
        }
        return store.storesBuildRecords(key.substring(0, key.lastIndexOf("/builds/"))) ? key : null;
    }

    @CheckForNull
    private static String buildKey(@NonNull FlowExecutionOwner owner) {
        try {
            return buildKey(owner.getRootDir());
        } catch (IOException e) {
            return null;
        }
    }

    /** Downloads the log of a build which ran elsewhere into its directory, if not there yet. */
    static void fetch(@NonNull AzureBlobStore blobs, @NonNull String key, @NonNull File dir) throws IOException {
        Files.createDirectories(dir.toPath());
        // index first: a log without its index would show no step boundaries
        for (String name : List.of(INDEX, LOG)) {
            Path target = new File(dir, name).toPath();
            if (Files.exists(target)) {
                continue;
            }
            Path tmp = Files.createTempFile(dir.toPath(), name, ".tmp");
            try {
                boolean found;
                try (OutputStream out = Files.newOutputStream(tmp)) {
                    found = blobs.downloadSegmented(key + "/" + name, out); // every segment, in order
                }
                if (found) {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        }
    }

    @Extension(ordinal = 100)
    public static final class Factory implements LogStorageFactory {

        @CheckForNull
        @Override
        public LogStorage forBuild(@NonNull FlowExecutionOwner owner) {
            AzureBlobStore blobs = blobs();
            String key = blobs == null ? null : buildKey(owner);
            if (key == null) {
                return null;
            }
            File dir;
            Run<?, ?> running = null;
            try {
                dir = owner.getRootDir();
                Queue.Executable executable = owner.getExecutable();
                if (executable instanceof Run<?, ?> run && run.isLogUpdated()) {
                    running = run;
                }
            } catch (IOException e) {
                LOGGER.log(Level.FINE, "Cannot tell whether " + owner + " is running", e);
                return null;
            }
            File log = new File(dir, LOG);
            if (running != null) {
                Shipper.track(blobs, key, log, running);
            } else if (!log.exists()) {
                try {
                    fetch(blobs, key, dir);
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Could not download the log of " + key, e);
                }
            }
            return FileLogStorage.forFile(log);
        }
    }

    /**
     * Appends what running builds added to their log files to the corresponding blobs.
     */
    static final class Shipper {

        /**
         * One local file shipped to a segmented append blob ({@code log}, {@code log.1}, …): a new segment starts before
         * the current one reaches {@link AzureBlobStore#MAX_SEGMENT_BLOCKS} blocks (Target 14).
         */
        private static final class Stream {
            final String base;
            final File file;
            /** Bytes shipped, over all segments. */
            long offset;
            int segment;
            /** Where the current segment starts in the file. */
            long segmentStart;
            int segmentBlocks;
            /** {@link System#currentTimeMillis} of the last append. */
            long lastShip;

            Stream(AzureBlobStore blobs, String base, File file) throws IOException {
                this.base = base;
                this.file = file;
                // resuming (e.g. after a restart or adoption): continue after what was already shipped
                List<AzureBlobStore.Segment> segments = blobs.segments(base);
                for (AzureBlobStore.Segment seg : segments) {
                    segment = seg.index();
                    segmentStart = offset;
                    segmentBlocks = seg.blocks();
                    offset += seg.size();
                }
            }

            long pending() {
                return Math.max(0, file.length() - offset);
            }

            void reset() {
                offset = 0;
                segment = 0;
                segmentStart = 0;
                segmentBlocks = 0;
            }
        }

        private static final class Tracked {
            final AzureBlobStore blobs;
            final String key;
            final File log;
            final File index;
            final Run<?, ?> run;
            final Stream logStream;
            final Stream indexStream;
            long lapses;
            /** The build completed: stop tracking once everything is shipped (Target 14). */
            volatile boolean finishing;
            /** Its record was published before the tail of its log was shipped. */
            volatile boolean publishedEarly;

            boolean shippedAll() {
                return logStream.pending() == 0 && (!index.exists() || indexStream.pending() == 0);
            }

            Tracked(AzureBlobStore blobs, String key, File log, Run<?, ?> run) throws IOException {
                this.blobs = blobs;
                this.key = key;
                this.log = log;
                this.run = run;
                this.lapses = lapseCount();
                this.index = new File(log.getPath() + "-index");
                this.logStream = new Stream(blobs, key + "/" + LOG, log);
                this.indexStream = new Stream(blobs, key + "/" + INDEX, index);
            }
        }

        private static final Map<String, Tracked> tracked = new ConcurrentHashMap<>();
        private static ScheduledFuture<?> task;

        static void track(AzureBlobStore blobs, String key, File log, Run<?, ?> run) {
            tracked.computeIfAbsent(key, k -> {
                try {
                    return new Tracked(blobs, k, log, run);
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Cannot ship the log of " + k, e);
                    return null;
                }
            });
            synchronized (Shipper.class) {
                if (task == null) {
                    task = Timer.get()
                            .scheduleWithFixedDelay(
                                    Shipper::shipAll, SHIP_PERIOD_MILLIS, SHIP_PERIOD_MILLIS, TimeUnit.MILLISECONDS);
                }
            }
        }

        /**
         * Fewer, larger appends (Target 14): a build's log is shipped once this much is pending, or
         * {@link #COALESCE_MILLIS} after the last shipment; save points and the end of a build ship at once.
         */
        static final int COALESCE_BYTES = Integer.getInteger(BlobLogStorage.class.getName() + ".coalesceBytes", 32 * 1024);
        static final long COALESCE_MILLIS = Long.getLong(BlobLogStorage.class.getName() + ".coalesceMillis", 3_000);

        private static boolean due(Tracked t) {
            return t.finishing
                    || t.logStream.pending() >= COALESCE_BYTES
                    || System.currentTimeMillis() - t.logStream.lastShip >= COALESCE_MILLIS
                    || t.indexStream.pending() > 0 && System.currentTimeMillis() - t.indexStream.lastShip >= COALESCE_MILLIS;
        }

        private static void shipAll() {
            for (Tracked t : tracked.values()) {
                try {
                    if (!due(t)) {
                        continue;
                    }
                    ship(t);
                    if (t.finishing && t.shippedAll()) {
                        done(t);
                    }
                } catch (IOException | RuntimeException e) {
                    LOGGER.log(Level.WARNING, "Failed to ship the log of " + t.key + "; will retry", e);
                }
            }
        }

        /** A completed build's whole log is shipped: stop tracking it, and republish it if its record went out first. */
        private static void done(Tracked t) {
            tracked.remove(t.key, t);
            if (t.publishedEarly && t.run != null) {
                LOGGER.info(() -> "Shipped the rest of the log of " + t.run + "; republishing it");
                try {
                    jenkins.persistence.BuildRecords.republishFiles(t.run);
                } catch (IOException | RuntimeException e) {
                    LOGGER.log(Level.WARNING, "Could not republish " + t.run + " after shipping its log", e);
                }
            }
        }

        private static void ship(Tracked t) throws IOException {
            synchronized (t) {
                if (PersistenceStores.get() instanceof PostgresPersistenceStore p && p.mayHaveLapsed()) {
                    // e.g. just woke from a pause: another controller may own the build by now; wait for the heartbeat
                    return;
                }
                long lapses = lapseCount();
                if (lapses != t.lapses) {
                    // this controller may have looked dead to the others: check it still owns the build first
                    if (t.run != null && !RunningBuilds.owns(t.run)) {
                        drop(t.key);
                        RunningBuilds.lost(t.run);
                        return;
                    }
                    t.lapses = lapses;
                }
                boolean ok = shipStream(t.blobs, t.logStream) && shipStream(t.blobs, t.indexStream);
                if (!ok) {
                    if (t.run != null && RunningBuilds.owns(t.run)) {
                        // we own the build: a controller which lost it appended meanwhile; our local copy is the truth
                        LOGGER.warning(() ->
                                "Log of " + t.key + " was appended to by a previous owner; rewriting it from here");
                        rewrite(t.blobs, t.logStream);
                        rewrite(t.blobs, t.indexStream);
                        return;
                    }
                    // someone else appended: another controller owns the build now
                    LOGGER.warning(
                            () -> "Log of " + t.key + " was appended to elsewhere; no longer shipping it from here");
                    drop(t.key);
                }
            }
        }

        /**
         * Appends what the file has beyond what was shipped, starting a new segment before the current one is full.
         *
         * @return false if the blob was appended to by someone else
         */
        private static boolean shipStream(AzureBlobStore blobs, Stream st) throws IOException {
            while (st.file.length() > st.offset) {
                int n = (int) Math.min(MAX_SHIP, st.file.length() - st.offset);
                int blocks = (n + AzureBlobStore.MAX_APPEND_BLOCK - 1) / AzureBlobStore.MAX_APPEND_BLOCK;
                if (st.segmentBlocks > 0 && st.segmentBlocks + blocks > AzureBlobStore.MAX_SEGMENT_BLOCKS) {
                    st.segment++;
                    st.segmentStart = st.offset;
                    st.segmentBlocks = 0;
                    LOGGER.fine(() -> "Starting segment " + st.segment + " of " + st.base);
                }
                byte[] bytes = new byte[n];
                try (RandomAccessFile raf = new RandomAccessFile(st.file, "r")) {
                    raf.seek(st.offset);
                    raf.readFully(bytes);
                }
                if (!blobs.appendAt(AzureBlobStore.segment(st.base, st.segment), bytes, 0, n, st.offset - st.segmentStart)) {
                    return false;
                }
                st.offset += n;
                st.segmentBlocks += blocks;
                st.lastShip = System.currentTimeMillis();
            }
            return true;
        }

        /** Replaces a segmented blob with the whole local file, as fresh append blobs. */
        private static void rewrite(AzureBlobStore blobs, Stream st) throws IOException {
            blobs.deleteSegmented(st.base);
            st.reset();
            if (st.file.exists()) {
                shipStream(blobs, st);
            }
        }

        /** Ships what a build's log has now, if it is tracked here; failures propagate. */
        static void shipNow(@CheckForNull String key) throws IOException {
            Tracked t = key == null ? null : tracked.get(key);
            if (t != null) {
                ship(t);
            }
        }

        /** Stops shipping a build's log without shipping the rest, as when it is owned elsewhere now. */
        static void drop(String key) {
            tracked.remove(key);
        }

        private static long lapseCount() {
            return PersistenceStores.get() instanceof PostgresPersistenceStore p ? p.lapseCount() : 0;
        }

        /**
         * Ships the rest of a build's log and stops tracking it; if that cannot be done now (the store or the blob store
         * is unreachable), it is shipped later, and the build republished then, so that no controller keeps a log
         * without its end (Target 14).
         */
        static void finish(String key) {
            Tracked t = tracked.get(key);
            if (t == null) {
                return;
            }
            t.finishing = true;
            try {
                ship(t);
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.WARNING, "Failed to ship the end of the log of " + key + "; will retry", e);
            }
            if (t.shippedAll()) {
                tracked.remove(key, t);
            } else {
                t.publishedEarly = true; // the record is published right after this
            }
        }
    }

    @Extension(ordinal = 1000) // before BuildRecords publishes the record
    public static final class Listener extends RunListener<Run<?, ?>> {

        @Override
        public void onStarted(Run<?, ?> run, TaskListener listener) {
            AzureBlobStore blobs = blobs();
            String key = blobs == null || !(run instanceof FlowExecutionOwner.Executable)
                    ? null
                    : buildKey(run.getRootDir());
            if (key != null) {
                Shipper.track(blobs, key, new File(run.getRootDir(), LOG), run);
            }
        }

        @Override
        public void onFinalized(Run<?, ?> run) {
            String key = run instanceof FlowExecutionOwner.Executable ? buildKey(run.getRootDir()) : null;
            if (key != null) {
                if (!RunningBuilds.owns(run)) {
                    Shipper.drop(key); // taken over by another controller, which ships the log from now on
                    return;
                }
                Shipper.finish(key);
            }
        }
    }

    /** Pipeline logs are in blobs, not in the snapshot. */
    @Extension
    public static final class Exclusion implements BuildRecords.Exclusion {
        @NonNull
        @Override
        public Collection<String> excludes(@NonNull Run<?, ?> run) {
            return run instanceof FlowExecutionOwner.Executable && blobs() != null ? List.of(LOG, INDEX) : List.of();
        }
    }

    /**
     * Read-only console of a build running on another controller: {@code job/<name>/running-console/<N>}.
     */
    public static final class RunningConsole implements Action {
        private final Job<?, ?> job;

        RunningConsole(Job<?, ?> job) {
            this.job = job;
        }

        @Override
        public String getIconFileName() {
            return null;
        }

        @Override
        public String getDisplayName() {
            return null;
        }

        @Override
        public String getUrlName() {
            return "running-console";
        }

        public void doDynamic(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
            job.checkPermission(Item.READ);
            AzureBlobStore blobs = blobs();
            String jobKey = PersistenceStores.keyFor(job.getRootDir());
            int number;
            try {
                number = Integer.parseInt(req.getRestOfPath().replaceAll("^/|/$", ""));
            } catch (NumberFormatException e) {
                rsp.sendError(404);
                return;
            }
            byte[] log = blobs == null || jobKey == null
                    ? null
                    : blobs.readSegmentedFrom(jobKey + "/builds/" + number + "/" + LOG, 0);
            if (log == null) {
                rsp.sendError(404);
                return;
            }
            rsp.setContentType("text/plain;charset=UTF-8");
            try (BufferedReader r = new BufferedReader(
                            new InputStreamReader(new ByteArrayInputStream(log), StandardCharsets.UTF_8));
                    PrintWriter w = new PrintWriter(rsp.getOutputStream(), false, StandardCharsets.UTF_8)) {
                String line;
                while ((line = r.readLine()) != null) {
                    w.println(ConsoleNote.removeNotes(line));
                }
            }
        }
    }

    @Extension
    public static final class RunningConsoleFactory extends TransientActionFactory<Job> {

        @Override
        public Class<Job> type() {
            return Job.class;
        }

        @NonNull
        @Override
        public Collection<? extends Action> createFor(@NonNull Job target) {
            return blobs() == null ? List.of() : List.of(new RunningConsole(target));
        }
    }
}
