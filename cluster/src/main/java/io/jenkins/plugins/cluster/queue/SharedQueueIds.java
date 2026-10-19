package io.jenkins.plugins.cluster.queue;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.Action;
import hudson.model.Queue;
import io.jenkins.plugins.cluster.spi.ClusterQueue;
import io.jenkins.plugins.cluster.spi.ClusterStore;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.queue.QueueIdStrategy;

/**
 * Ids unique across the cluster, so that an item has the same id on every controller.
 */
@Extension(ordinal = 100)
public final class SharedQueueIds extends QueueIdStrategy {

    /** As the class this was part of: logging configured for it applies. */
    private static final Logger LOGGER = Logger.getLogger(SharedQueue.class.getName());

    private static final QueueIdStrategy LOCAL = new QueueIdStrategy.DefaultStrategy();
    /** Local ids taken while the cluster sequence is unreachable are offset by this much (2^52). */
    static final long LOCAL_RANGE = 1L << 52;
    /** The id of the mirror being created. */
    static final ThreadLocal<Long> MIRRORED = new ThreadLocal<>();

    @Override
    public long generateIdFor(@NonNull Queue.Task project, @NonNull List<Action> actions) {
        Long mirrored = MIRRORED.get();
        if (mirrored != null) {
            return mirrored;
        }
        ClusterQueue cq = SharedQueue.queue();
        if (cq != null) {
            try {
                return nextId(cq);
            } catch (IOException | RuntimeException e) {
                // the store is unreachable: an id from a range the cluster sequence never reaches, so that it
                // cannot collide with one handed out elsewhere
                LOGGER.log(Level.WARNING, "Cannot get a cluster-wide queue id; using a local one", e);
                return LOCAL_RANGE + LOCAL.generateIdFor(project, actions);
            }
        }
        return withoutSharedQueue().generateIdFor(project, actions);
    }

    /** The strategy used when there is no shared queue: whichever would have been used without this one. */
    private QueueIdStrategy withoutSharedQueue() {
        for (QueueIdStrategy other : ExtensionList.lookup(QueueIdStrategy.class)) {
            if (other != this) {
                return other;
            }
        }
        return LOCAL;
    }

    /** Ids taken from the cluster sequence ahead of need, off the queue lock (unused ones are skipped numbers). */
    private record Pool(ClusterStore store, BlockingQueue<Long> ids) {}

    private static volatile Pool pool;
    private static final int SPARE = 64;
    private static final AtomicBoolean filling = new AtomicBoolean();

    private static Pool pool() {
        ClusterStore store = ClusterStore.get();
        Pool p = pool;
        if (p == null || p.store() != store) {
            p = new Pool(store, new LinkedBlockingQueue<>()); // ids of another store's sequence are no use
            pool = p;
        }
        return p;
    }

    /** Off the lock: tops the pool up, with ids taken in one store call; at startup, and once it is half used. */
    static void fill(ClusterQueue cq) {
        Pool p = pool();
        if (p.ids().size() < SPARE / 2 && filling.compareAndSet(false, true)) {
            SharedQueue.claims.submit(() -> {
                try {
                    int n = SPARE - p.ids().size();
                    if (n > 0) {
                        for (long id : cq.nextIds(n)) {
                            p.ids().add(id);
                        }
                    }
                } catch (IOException | RuntimeException e) {
                    LOGGER.log(Level.FINE, "Cannot take queue ids ahead", e);
                } finally {
                    filling.set(false);
                }
            });
        }
    }

    /** An id from the pool (refilled in the background), or from the store if the pool ran dry. */
    private static long nextId(ClusterQueue cq) throws IOException {
        Long id = pool().ids().poll();
        fill(cq);
        if (id != null) {
            return id;
        }
        // the pool ran dry (a burst of items larger than it, before it was refilled): a store call made under the
        // queue lock, as an exception (an id is needed now, and one from a local range would leave the item
        // unshared); the other is a shared job's build number (StoredBuildNumberAssigner)
        return cq.nextId();
    }

    @Override
    public void persist(@NonNull Queue.State queueState) {
        (SharedQueue.queue() != null ? LOCAL : withoutSharedQueue()).persist(queueState);
    }

    @Override
    public void load(@NonNull Queue.State queueState) {
        (SharedQueue.queue() != null ? LOCAL : withoutSharedQueue()).load(queueState);
    }
}
