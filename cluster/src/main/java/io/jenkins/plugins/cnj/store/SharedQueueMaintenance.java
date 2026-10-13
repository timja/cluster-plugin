package io.jenkins.plugins.cnj.store;

import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Item;
import hudson.model.PeriodicWork;
import hudson.model.TaskListener;
import hudson.model.listeners.ItemListener;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.persistence.PersistenceStores;
import jenkins.util.PeriodicWorkCoordinator;

/**
 * Housekeeping for the shared build queue and cluster-singleton periodic work (Target 5).
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
            if (PersistenceStores.get() instanceof PostgresPersistenceStore store && store.queue() != null) {
                int n = store.reclaimAndPrune();
                if (n > 0) {
                    LOGGER.info(() -> "Put " + n + " queue item(s) claimed by gone controllers back into the queue");
                }
                // started by a controller that died before the build's placeholder was written: the build never began
                for (PostgresPersistenceStore.StartedItem item : store.startedByGone()) {
                    hudson.model.Job<?, ?> job = jenkins.model.Jenkins.get().getItemByFullName(item.task(), hudson.model.Job.class);
                    String jobKey = job == null ? null : PersistenceStores.keyFor(job.getRootDir());
                    if (jobKey == null) {
                        continue;
                    }
                    int number = item.buildNumber();
                    if (store.read(jobKey + "/running/" + number + ".xml") == null
                            && store.read(jobKey + "/builds/" + number + "/build.xml") == null) {
                        LOGGER.info(() -> item.task() + " #" + number + " was started by a gone controller and never began; queued again");
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
     * Forgets the started items of a deleted or renamed item, which {@link Reclaim} would otherwise requeue on an item
     * created later under the same name.
     */
    @Extension
    public static final class Forget extends ItemListener {
        @Override
        public void onDeleted(Item item) {
            cancel(item.getFullName());
        }

        @Override
        public void onLocationChanged(Item item, String oldFullName, String newFullName) {
            cancel(oldFullName);
        }

        private static void cancel(String fullName) {
            if (PersistenceStores.get() instanceof PostgresPersistenceStore store && store.queue() != null) {
                try {
                    store.cancelStartedOf(fullName);
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Cannot forget the started queue items of " + fullName, e);
                }
            }
        }
    }

    /**
     * Runs each period of {@link jenkins.util.ClusterSingleton} work on the first controller to claim it.
     */
    @Extension
    public static final class Coordinator implements PeriodicWorkCoordinator {
        @Override
        public boolean shouldRun(PeriodicWork work, long period) {
            if (!(PersistenceStores.get() instanceof PostgresPersistenceStore store)) {
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
