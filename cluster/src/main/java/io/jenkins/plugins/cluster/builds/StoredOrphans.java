package io.jenkins.plugins.cluster.builds;

import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.TaskListener;
import io.jenkins.plugins.cluster.spi.ClusterStore;
import io.jenkins.plugins.cluster.spi.Keys;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.persistence.PersistenceStore;
import jenkins.util.ClusterSingleton;

/**
 * Finishes deleting what is stored about deleted items whose cleanup failed, or whose controller died during it
 * (their {@link BuildRecords#DELETING} marker is still there), once per cluster, every few minutes. And, less often, deletes
 * what is stored about items that no longer exist anywhere although nothing marked them deleted (their deletion
 * failed before the marker was written, or something was written for them as they were deleted): see
 * {@link BuildRecords#sweepUnknown}.
 */
@ClusterSingleton
@Extension
public final class StoredOrphans extends AsyncPeriodicWork {

    /** As the class this was part of: logging configured for it applies. */
    private static final Logger LOGGER = Logger.getLogger(BuildRecords.class.getName());

    public StoredOrphans() {
        super("Stored data of deleted items");
    }

    @Override
    public long getRecurrencePeriod() {
        return TimeUnit.MINUTES.toMillis(5);
    }

    private long lastUnknownSweep;

    @Override
    protected void execute(TaskListener listener) throws IOException {
        ClusterStore store = BuildRecords.store();
        if (store == null) {
            return;
        }
        for (String uuid : store.children(BuildRecords.DELETING)) {
            PersistenceStore.Entry e = store.read(BuildRecords.DELETING + "/" + uuid);
            String[] lines = e == null ? new String[0] : new String(e.content(), StandardCharsets.UTF_8).split("\n");
            if (lines.length >= 2 && lines[0].equals(Keys.itemByUuid(uuid))) {
                LOGGER.info(() -> "Finishing the deletion of what was stored about item " + uuid);
                BuildRecords.deleteStored(lines[0], lines[1], uuid);
            }
        }
        long now = System.currentTimeMillis();
        if (now - lastUnknownSweep >= BuildRecords.UNKNOWN_SWEEP_MILLIS) {
            lastUnknownSweep = now;
            BuildRecords.sweepUnknown(store, now);
        }
    }

    @Override
    protected Level getNormalLoggingLevel() {
        return Level.FINE;
    }
}
