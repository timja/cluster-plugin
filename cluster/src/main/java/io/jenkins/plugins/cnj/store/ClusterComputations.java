package io.jenkins.plugins.cnj.store;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.model.Executor;
import hudson.model.ExecutorListener;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Queue;
import hudson.model.queue.CauseOfBlockage;
import hudson.model.queue.QueueListener;
import hudson.model.queue.QueueTaskDispatcher;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.persistence.BuildLeases;
import jenkins.persistence.PersistenceStore;
import jenkins.persistence.PersistenceStores;

/**
 * Folder computations (a multibranch project's branch indexing, an organization folder's scan) run on one controller at
 * a time, cluster-wide (Target 15, ET17).
 *
 * <p>A computation is not a job, so it does not go through the shared queue: every controller asked to scan a project
 * scans it. Scans running at once on several controllers each saw a new branch before any had recorded its revision,
 * and each scheduled its first build: the branch built twice. On one controller, scans of a project never overlap. Here
 * a lease per folder ({@code computations/<folder key>}) lets one controller compute it; the others wait their turn
 * (and then find nothing new), and the lease lapses with its holder if that controller dies.
 */
public final class ClusterComputations {

    private static final Logger LOGGER = Logger.getLogger(ClusterComputations.class.getName());

    /** Folder key → the epoch of the lease held here. */
    private static final Map<String, Long> held = new ConcurrentHashMap<>();

    private ClusterComputations() {}

    /** The lease key of a task that is a folder computation, or null for anything else. */
    @CheckForNull
    static String leaseKey(Queue.Task task) {
        if (!(task instanceof ItemGroup<?> && task instanceof Item item)) {
            return null;
        }
        String key = PersistenceStores.keyFor(item.getRootDir());
        return key == null ? null : "computations/" + key;
    }

    @CheckForNull
    private static BuildLeases leases() {
        PersistenceStore store = PersistenceStores.get();
        return store == null ? null : store.leases();
    }

    private static void release(Queue.Task task) {
        String key = leaseKey(task);
        Long epoch = key == null ? null : held.remove(key);
        BuildLeases leases = leases();
        if (epoch != null && leases != null) {
            try {
                leases.release(key, epoch);
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.FINE, "Could not release " + key + "; it lapses with this controller", e);
            }
        }
    }

    /** Holds a computation back while another controller computes the same folder. */
    @Extension
    public static final class Dispatcher extends QueueTaskDispatcher {
        @Override
        public CauseOfBlockage canRun(Queue.Item item) {
            String key = leaseKey(item.task);
            BuildLeases leases = key == null ? null : leases();
            if (leases == null) {
                return null;
            }
            try {
                long epoch = leases.acquire(key);
                if (epoch == 0) {
                    return new Blocked();
                }
                held.put(key, epoch);
                return null;
            } catch (IOException | RuntimeException e) {
                // the store is unreachable: as on one controller (a computation may then overlap one elsewhere)
                LOGGER.log(Level.FINE, "Could not take " + key, e);
                return null;
            }
        }
    }

    /** Releases the lease once the computation ends. */
    @Extension
    public static final class Completion implements ExecutorListener {
        @Override
        public void taskCompleted(Executor executor, Queue.Task task, long durationMS) {
            release(task);
        }

        @Override
        public void taskCompletedWithProblems(Executor executor, Queue.Task task, long durationMS, Throwable problems) {
            release(task);
        }
    }

    /** ... or once the queue item leaves without running (cancelled). */
    @Extension
    public static final class Cancellation extends QueueListener {
        @Override
        public void onLeft(Queue.LeftItem li) {
            if (li.isCancelled()) {
                release(li.task);
            }
        }
    }

    static final class Blocked extends CauseOfBlockage {
        @Override
        public String getShortDescription() {
            return "Being computed by another controller";
        }
    }
}
