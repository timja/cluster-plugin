package io.jenkins.plugins.cluster.builds;

import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.RunMap;
import hudson.model.TaskListener;
import io.jenkins.plugins.cluster.builds.BuildRecords.Manifest;
import io.jenkins.plugins.cluster.builds.BuildRecords.Published;
import io.jenkins.plugins.cluster.spi.ClusterStore;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;

/**
 * Republishes recently completed builds whose directories changed without their record being saved, as when a
 * plugin adds a file to a completed build: their snapshots would otherwise miss it on other controllers.
 */
@Extension
public final class LateFiles extends AsyncPeriodicWork {

    /** As the class this was part of: logging configured for it applies. */
    private static final Logger LOGGER = Logger.getLogger(BuildRecords.class.getName());

    public LateFiles() {
        super("Files added to completed builds");
    }

    @Override
    public long getRecurrencePeriod() {
        return TimeUnit.SECONDS.toMillis(Long.getLong(BuildRecords.class.getName() + ".lateFilesPeriodSeconds", 60));
    }

    /** Builds looked at by the broader scan per run, at most; the next run carries on. */
    static final int SCAN_LIMIT = Integer.getInteger(BuildRecords.class.getName() + ".lateFilesScanLimit", 1000);

    static final long SCAN_MILLIS = TimeUnit.MINUTES.toMillis(10);
    private long lastScan;
    private int scanFrom;

    @Override
    protected void execute(TaskListener listener) {
        if (BuildRecords.store() == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastScan > SCAN_MILLIS) {
            lastScan = now;
            scanLoaded();
        }
        for (String key : BuildRecords.recentlyPublished.keys()) {
            Published p = BuildRecords.recentlyPublished.get(key);
            if (p == null) {
                continue;
            }
            Run<?, ?> run = p.run().get();
            if (run == null
                    || now - p.at() > BuildRecords.LATE_FILES_MILLIS
                    || BuildRecords.isDeleted(key, run)
                    || !new File(run.getRootDir(), BuildRecords.RECORD).isFile()) { // e.g. its job was deleted
                BuildRecords.recentlyPublished.remove(key);
                continue;
            }
            if (!BuildRecords.signature(run.getRootDir()).equals(p.signature())) {
                try {
                    LOGGER.fine(() -> "Republishing " + run + ": files were added to it after it was published");
                    BuildRecords.republishFiles(run);
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
            RunMap<?> runs = BuildRecords.runsOf(job);
            if (runs != null && BuildRecords.isShared(job)) {
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
                String key = BuildRecords.recordKeyOf(run);
                ClusterStore store = BuildRecords.store();
                if (key != null && store != null && store.read(key) != null && !BuildRecords.isDeleted(key, run)) {
                    LOGGER.fine(() -> "Republishing " + run + ": files were added to it");
                    BuildRecords.republishFiles(run);
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
