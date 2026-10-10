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
                    found = blobs.download(key + "/" + name, out);
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
            boolean runningHere;
            try {
                dir = owner.getRootDir();
                Queue.Executable executable = owner.getExecutable();
                runningHere = executable instanceof Run<?, ?> run && run.isLogUpdated();
            } catch (IOException e) {
                LOGGER.log(Level.FINE, "Cannot tell whether " + owner + " is running", e);
                return null;
            }
            File log = new File(dir, LOG);
            if (runningHere) {
                Shipper.track(blobs, key, log);
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

        private static final class Tracked {
            final AzureBlobStore blobs;
            final String key;
            final File log;
            final File index;
            long logOffset;
            long indexOffset;

            Tracked(AzureBlobStore blobs, String key, File log) throws IOException {
                this.blobs = blobs;
                this.key = key;
                this.log = log;
                this.index = new File(log.getPath() + "-index");
                // resuming (e.g. after a restart): continue after what was already shipped
                this.logOffset = blobs.size(key + "/" + LOG);
                this.indexOffset = blobs.size(key + "/" + INDEX);
            }
        }

        private static final Map<String, Tracked> tracked = new ConcurrentHashMap<>();
        private static ScheduledFuture<?> task;

        static void track(AzureBlobStore blobs, String key, File log) {
            tracked.computeIfAbsent(key, k -> {
                try {
                    return new Tracked(blobs, k, log);
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

        private static void shipAll() {
            for (Tracked t : tracked.values()) {
                try {
                    ship(t);
                } catch (IOException | RuntimeException e) {
                    LOGGER.log(Level.WARNING, "Failed to ship the log of " + t.key + "; will retry", e);
                }
            }
        }

        private static void ship(Tracked t) throws IOException {
            synchronized (t) {
                t.logOffset = shipFile(t.blobs, t.key + "/" + LOG, t.log, t.logOffset);
                t.indexOffset = shipFile(t.blobs, t.key + "/" + INDEX, t.index, t.indexOffset);
            }
        }

        private static long shipFile(AzureBlobStore blobs, String blobKey, File file, long offset) throws IOException {
            while (file.length() > offset) {
                int n = (int) Math.min(MAX_SHIP, file.length() - offset);
                byte[] bytes = new byte[n];
                try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                    raf.seek(offset);
                    raf.readFully(bytes);
                }
                blobs.append(blobKey, bytes, 0, n);
                offset += n;
            }
            return offset;
        }

        /** Ships the rest of a build's log and stops tracking it. */
        static void finish(String key) throws IOException {
            Tracked t = tracked.remove(key);
            if (t != null) {
                ship(t);
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
                Shipper.track(blobs, key, new File(run.getRootDir(), LOG));
            }
        }

        @Override
        public void onFinalized(Run<?, ?> run) {
            String key = run instanceof FlowExecutionOwner.Executable ? buildKey(run.getRootDir()) : null;
            if (key != null) {
                try {
                    Shipper.finish(key);
                } catch (IOException | RuntimeException e) {
                    LOGGER.log(Level.WARNING, "Failed to ship the end of the log of " + run, e);
                }
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
                    : blobs.readFrom(jobKey + "/builds/" + number + "/" + LOG, 0);
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
