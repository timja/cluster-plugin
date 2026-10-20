package io.jenkins.plugins.cluster;

import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Item;
import hudson.model.PeriodicWork;
import hudson.model.TaskListener;
import hudson.model.listeners.ItemListener;
import io.jenkins.plugins.cluster.spi.ClusterStore;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.persistence.PersistenceStores;
import jenkins.util.PeriodicWorkCoordinator;

/**
 * Housekeeping for the shared build queue and cluster-singleton periodic work.
 */
public final class SharedQueueMaintenance {

    private static final Logger LOGGER = Logger.getLogger(SharedQueueMaintenance.class.getName());

    private SharedQueueMaintenance() {}

    /**
     * Puts queue items claimed by a controller which then died back into the queue, so that another one starts them.
     */
    @Extension
    public static final class Reclaim extends AsyncPeriodicWork {
        public Reclaim() {
            super("Reclaim shared queue items of gone controllers");
        }

        @Override
        public long getRecurrencePeriod() {
            return TimeUnit.SECONDS.toMillis(15);
        }

        @Override
        protected void execute(TaskListener listener) throws IOException {
            ClusterStore store = ClusterStore.get();
            if (store != null) {
                int n = store.reclaimAndPrune();
                if (n > 0) {
                    LOGGER.info(() -> "Put " + n + " queue item(s) claimed by gone controllers back into the queue");
                }
                // started by a controller that died before the build's placeholder was written: the build never began
                for (ClusterStore.StartedItem item : store.startedByGone()) {
                    String prefix = io.jenkins.plugins.cluster.spi.Keys.itemByUuid(item.itemUuid());
                    int number = item.buildNumber();
                    if (store.read(io.jenkins.plugins.cluster.spi.Keys.placeholder(prefix, number)) == null
                            && store.read(io.jenkins.plugins.cluster.spi.Keys.buildRecord(prefix, number)) == null) {
                        LOGGER.info(() -> item.task() + " #" + number
                                + " was started by a gone controller and never began; queued again");
                        store.requeueStarted(item.id());
                    }
                }
            }
        }

        @Override
        protected Level getNormalLoggingLevel() {
            return Level.FINE;
        }
    }

    /**
     * Forgets the started items of a deleted item, which {@link Reclaim} would otherwise requeue. Only where the deletion
     * was made; a rename or move changes nothing (records are kept by the item's identity).
     */
    @Extension
    public static final class Forget extends ItemListener {
        @Override
        public void onDeleted(Item item) {
            ClusterStore store = ClusterStore.get();
            String uuid = item.getItemUuid();
            if (store == null || uuid == null || PersistenceStores.isApplyingRemoteChange()) {
                return;
            }
            jenkins.util.Timer.get().submit(() -> {
                try {
                    store.cancelStartedOf(uuid);
                } catch (IOException | RuntimeException e) {
                    LOGGER.log(Level.WARNING, "Cannot forget the started queue items of " + item.getFullName(), e);
                }
            });
        }
    }

    /**
     * Runs each period of {@link jenkins.util.ClusterSingleton} work on the first controller to claim it.
     */
    @Extension
    public static final class Coordinator implements PeriodicWorkCoordinator {
        @Override
        public boolean shouldRun(PeriodicWork work, long period) {
            ClusterStore store = ClusterStore.get();
            if (store == null) {
                return true;
            }
            try {
                return store.claim("_periodic", work.getClass().getName(), period);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Cannot claim " + work + " for period " + period + "; skipping it", e);
                return false;
            }
        }
    }
}
