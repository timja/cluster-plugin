package io.jenkins.plugins.cluster.queue;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.model.Action;
import hudson.model.Job;
import hudson.model.Queue;
import hudson.model.queue.QueueListener;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.cluster.builds.ExclusiveLeases;
import io.jenkins.plugins.cluster.spi.ClusterQueue;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.persistence.PersistenceStores;
import jenkins.util.Timer;

@Extension
public final class QueuePublisher extends QueueListener {

    /** As the class this was part of: logging configured for it applies. */
    private static final Logger LOGGER = Logger.getLogger(SharedQueue.class.getName());

    @Override
    public void onEnterWaiting(Queue.WaitingItem wi) {
        if (PersistenceStores.isApplyingRemoteChange() || !SharedQueue.shared(wi.task)) {
            return;
        }
        if (wi.getId() >= SharedQueueIds.LOCAL_RANGE) {
            // a local id, taken while the cluster sequence was unreachable: another controller may have taken the
            // same one, so it is never shared; it stays (and builds) here
            return;
        }
        // under the queue lock (schedule2): only prepared here, shared by the outbox; it does not start meanwhile
        List<Action> actions = List.copyOf(wi.getActions());
        ClusterQueue.Record record = record(wi, actions);
        SharedQueue.sharing.add(wi.getId());
        SharedQueue.outbox(wi.getId()).submit(() -> share(wi, record, actions));
    }

    private static ClusterQueue.Record record(Queue.Item item, List<Action> actions) {
        String key = SharedQueue.dedupKey(item.task, actions);
        if (keptApart(actions)) {
            key += "#apart:" + item.getId(); // folds into nothing
        }
        Job<?, ?> job = (Job<?, ?>) item.task;
        return new ClusterQueue.Record(
                item.getId(),
                job.getItemUuid(),
                job.getFullName(),
                key,
                SharedQueue.toXml(actions),
                SharedQueue.dueOf(item),
                ClusterQueue.State.WAITING,
                SharedQueue.origin(),
                SharedQueue.pinnedTo(item, actions),
                null,
                0);
    }

    /**
     * Whether the local queue would keep a request apart even from an item with equal actions: one of its
     * {@link Queue.QueueAction}s (such as a replay's) asks for a new item whatever is queued.
     */
    private static boolean keptApart(List<Action> actions) {
        for (Action a : actions) {
            if (a instanceof Queue.QueueAction qa && qa.shouldSchedule(new ArrayList<>(actions))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Off the lock: looks up the record of an item nothing is known of here (restored from before a restart, or
     * whose publication failed, perhaps after the store had it), or publishes the item if the store has none.
     */
    static void reconcile(long id) {
        try (ACLContext ctx = ACL.as2(ACL.SYSTEM2)) {
            ClusterQueue cq = SharedQueue.queue();
            if (cq == null) {
                return; // not shared (any more)
            }
            ClusterQueue.Record r = cq.get(id);
            if (r != null) {
                SharedQueue.know(r);
                return;
            }
            Queue.Item item = SharedQueue.localItem(id);
            if (item != null && SharedQueue.sharing.add(id)) {
                List<Action> actions = List.copyOf(item.getActions());
                share(item, record(item, actions), actions);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.FINE, "Cannot look up queue item " + id + "; not starting it for now", e);
        } finally {
            SharedQueue.reconciling.remove(id);
            Queue.getInstance().scheduleMaintenance();
        }
    }

    private static void share(Queue.Item item, ClusterQueue.Record record, List<Action> actions) {
        long id = item.getId();
        try (ACLContext ctx = ACL.as2(ACL.SYSTEM2)) { // to see the job when mirroring the item folded into
            ClusterQueue cq = SharedQueue.queue();
            if (cq == null) {
                return; // not shared (any more)
            }
            ClusterQueue.Record stored = cq.publish(record);
            SharedQueue.know(stored);
            if (stored.id() != item.getId()) {
                // the same job and parameters were already waiting (scheduled elsewhere at the same time): fold
                // into
                // that item, then drop this one, which has not started (the gate held it)
                LOGGER.fine(() -> item + " folds into queue item " + stored.id() + " from " + stored.origin());
                if (!fold(cq, stored.id(), actions, SharedQueue.dueOf(item), record.pinnedTo())) {
                    // taken meanwhile: this one is kept, and, nothing being known under its id, the gate has it
                    // published again (as an item of its own, or folded into another waiting one)
                    return;
                }
                // requests folded into this one here meanwhile, shared after this, go where it went
                foldedInto.put(id, stored.id());
                // by id: with no quiet period, it may be blocked or buildable by now; it was folded, not cancelled
                // for good, so its future follows the item it was folded into
                PersistenceStores.applyingRemoteChange(() -> {
                    Queue.HandOff handOff = Queue.getInstance().handOff(id);
                    if (handOff != null) {
                        HandOffs.folded(id, stored.id(), handOff);
                    }
                    return null;
                });
                SharedQueue.outbox(id).execute(() -> foldedInto.remove(id)); // after those: none come once it is gone
                QueueMirror.mirror(stored.id());
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Failed to share " + item + "; it does not start until it is", e);
        } finally {
            SharedQueue.sharing.remove(id);
            if (SharedQueue.cancelledWhileSharing.remove(id)) {
                shareCancellation(id, 0); // published meanwhile (or not: then there is nothing to cancel)
            }
            Queue.getInstance().scheduleMaintenance();
        }
    }

    /**
     * Merges a request into a waiting item, compare-and-set: one fold elsewhere at the same time is kept.
     *
     * @param pinTo pins the item to this controller, unless it is pinned already: the request must run here
     * @return whether the request was merged into it
     */
    private static boolean fold(ClusterQueue cq, long id, List<Action> actions, long due, @CheckForNull String pinTo)
            throws IOException {
        for (int attempt = 0; attempt < 20; attempt++) {
            ClusterQueue.Record now = cq.get(id);
            if (now == null || now.state() != ClusterQueue.State.WAITING) {
                if (pinTo != null && now != null && !pinTo.equals(now.claimedBy())) {
                    LOGGER.info(() -> "Queue item " + id + " was taken by " + now.claimedBy()
                            + " before a request needing this controller was folded into it");
                }
                return false; // started (or cancelled) meanwhile, as a request folded a moment later would have
                // found
            }
            if (cq.update(
                    id,
                    now.actions(),
                    SharedQueue.toXml(SharedQueue.merge(SharedQueue.fromXml(now.actions()), actions)),
                    Math.min(now.dueAt(), due),
                    pinTo)) {
                if (pinTo != null && now.pinnedTo() != null && !pinTo.equals(now.pinnedTo())) {
                    LOGGER.info(() -> "Queue item " + id + " is pinned to " + now.pinnedTo()
                            + "; a request needing this controller was folded into it");
                }
                return true;
            }
        }
        LOGGER.warning(() -> "Queue item " + id + " kept changing; a request folded into it may lack its cause");
        return false;
    }

    /** Local items folded into an item from elsewhere when shared, by id: the item folded into, by the time it was. */
    private static final Map<Long, Long> foldedInto = new ConcurrentHashMap<>();

    @Override
    public void onFolded(Queue.Item item, List<Action> request) {
        if (PersistenceStores.isApplyingRemoteChange() || !SharedQueue.shared(item.task)) {
            return;
        }
        long id = item.getId();
        List<Action> actions = List.copyOf(request);
        long due = SharedQueue.dueOf(item);
        String pinTo = SharedQueue.pinnedTo(item, actions);
        // after the item's publication (the same outbox thread): only the request is merged into what is stored
        SharedQueue.outbox(id).submit(() -> {
            try {
                fold(SharedQueue.queue(), foldedInto.getOrDefault(id, id), actions, due, pinTo);
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.WARNING, "Failed to share changes to queue item " + id, e);
            }
        });
    }

    @Override
    public void onLeft(Queue.LeftItem li) {
        long id = li.getId();
        SharedQueue.known.remove(id);
        SharedQueue.notClaimed.remove(id);
        SharedQueue.readyLocal.remove(id);
        if ((li.isCancelled() || li.isTakenElsewhere()) && li.task instanceof Job<?, ?> job) {
            // what a claim here held of its job's exclusive lease (not under the queue lock, which this holds); one
            // still under way lets go of it when it ends. Taken elsewhere, its build runs there: a hold kept here
            // would let the job's next item be claimed here beside it.
            if (SharedQueue.claiming.contains(id)) {
                SharedQueue.leftWhileClaiming.add(id);
                if (!SharedQueue.claiming.contains(id)) {
                    SharedQueue.leftWhileClaiming.remove(id); // it ended meanwhile: released below
                }
            }
            Timer.get().submit(() -> ExclusiveLeases.release(job, ExclusiveLeases.claimHolder(id)));
        }
        if (li.isTakenElsewhere()) {
            return; // handed off: its record is another controller's now
        }
        if (!li.isCancelled() && SharedQueue.shared(li.task)) {
            // left to start here: should its task make no build (a job disabled meanwhile, one failing to make its
            // build), its record would stay claimed by this controller, built nowhere
            Timer.get()
                    .schedule(
                            () -> SharedQueue.leftWithoutBuild(li),
                            SharedQueue.LEFT_WITHOUT_BUILD_SECONDS,
                            TimeUnit.SECONDS);
        }
        if (li.isCancelled() && SharedQueue.shared(li.task)) {
            Timer.get().submit(() -> QueueReservations.release(id)); // a store call: not under the queue lock
        }
        if (!li.isCancelled() || PersistenceStores.isApplyingRemoteChange() || !SharedQueue.shared(li.task)) {
            return;
        }
        // while it is being published, the store may not have it yet: share() cancels it once it does
        SharedQueue.cancelledWhileSharing.add(id);
        if (SharedQueue.sharing.contains(id) || !SharedQueue.cancelledWhileSharing.remove(id)) {
            return; // share() cancels it, or did already
        }
        // not under the queue lock, which Queue.cancel holds here: a slow store would stop the whole queue
        shareCancellation(id, 0);
    }

    private static void shareCancellation(long id, int attempt) {
        Timer.get()
                .schedule(
                        () -> {
                            try {
                                ClusterQueue cq = SharedQueue.queue();
                                if (cq != null) {
                                    cq.cancel(id);
                                }
                            } catch (IOException | RuntimeException e) {
                                if (attempt < 10) {
                                    LOGGER.log(
                                            Level.FINE,
                                            "Failed to share the cancellation of queue item " + id + "; retrying",
                                            e);
                                    shareCancellation(id, attempt + 1);
                                } else {
                                    LOGGER.log(
                                            Level.WARNING, "Failed to share the cancellation of queue item " + id, e);
                                }
                            }
                        },
                        attempt == 0 ? 0 : 1L << Math.min(attempt, 6),
                        TimeUnit.SECONDS);
    }
}
