package io.jenkins.plugins.cluster.queue;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Job;
import hudson.model.Queue;
import hudson.model.queue.CauseOfBlockage;
import io.jenkins.plugins.cluster.agents.LaunchLease;
import io.jenkins.plugins.cluster.builds.ExclusiveLeases;
import io.jenkins.plugins.cluster.queue.SharedQueue.Blocked;
import io.jenkins.plugins.cluster.spi.ClusterQueue;
import io.jenkins.plugins.cluster.spi.ClusterStore;
import io.jenkins.plugins.cluster.spi.Keys;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.queue.QueueStartGate;
import jenkins.util.Timer;

@Extension
public final class SharedQueueGate implements QueueStartGate {

    /** As the class this was part of: logging configured for it applies. */
    private static final Logger LOGGER = Logger.getLogger(SharedQueue.class.getName());
    /** Recent provisioning answers per item id: load statistics ask for every label, every few seconds. */
    private final Map<Long, Map.Entry<Long, Boolean>> provisioning = new ConcurrentHashMap<>();

    /** Only one controller provisions for an item: see {@link SharedQueue#provisionsHere}. */
    @Override
    public boolean countsForProvisioning(@NonNull Queue.BuildableItem item) {
        if (!SharedQueue.shared(item.task)) {
            return true;
        }
        long now = System.currentTimeMillis();
        Map.Entry<Long, Boolean> cached = provisioning.get(item.getId());
        if (cached != null && now - cached.getKey() < 2_000) {
            return cached.getValue();
        }
        boolean answer;
        try {
            ClusterQueue cq = SharedQueue.queue();
            ClusterQueue.Record r = cq == null ? null : cq.get(item.getId());
            answer = r == null || SharedQueue.provisionsHere(r, now, LaunchLease.holdersOf(item.getAssignedLabel()));
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.FINE, e, () -> "Cannot tell who provisions for " + item);
            answer = true;
        }
        if (provisioning.size() > 10_000) {
            provisioning.clear();
        }
        provisioning.put(item.getId(), Map.entry(now, answer));
        return answer;
    }

    @Override
    public CauseOfBlockage mayStart(@NonNull Queue.BuildableItem item) {
        if (!SharedQueue.shared(item.task)) {
            return null;
        }
        // under the queue lock: from what is known only; the store is asked off the lock (claim below)
        long id = item.getId();
        if (id >= SharedQueueIds.LOCAL_RANGE) {
            // a local id, never shared (see QueuePublisher.onEnterWaiting): it builds here, once the store answers (its
            // build number is taken there, after it has left the queue) and with its job's exclusive lease
            if (SharedQueue.readyLocal.contains(id)) {
                return null;
            }
            if (SharedQueue.claiming.add(id)) {
                Job<?, ?> job = (Job<?, ?>) item.task;
                SharedQueue.claims.submit(() -> claimLocal(job, id));
            }
            String why = SharedQueue.notClaimed.get(id);
            return new Blocked(why != null ? why : "Waiting for the persistence store");
        }
        if (SharedQueue.sharing.contains(id)) {
            return new Blocked("Being shared with the other controllers");
        }
        ClusterQueue.Record r = SharedQueue.known.get(id);
        if (r == null) {
            // restored from before a restart, or its publication failed (perhaps after the store had it): whether
            // it may start depends on its record, if any; during a store outage it waits rather than risk a
            // duplicate
            if (SharedQueue.reconciling.add(id)) {
                SharedQueue.outbox(id).submit(() -> QueuePublisher.reconcile(id));
            }
            return new Blocked("Being checked with the other controllers");
        }
        String me = SharedQueue.origin();
        if (me.equals(r.claimedBy()) && r.state() == ClusterQueue.State.CLAIMED) {
            return null;
        }
        if (r.state() != ClusterQueue.State.WAITING) {
            // taken elsewhere, or started here already: an item started here has left the queue, so a copy still in
            // it is stale (e.g. restored by a reload from a queue saved before it started)
            boolean taken = r.state() != ClusterQueue.State.CANCELLED && !me.equals(r.claimedBy());
            Timer.get().submit(() -> SharedQueue.dropLocal(id, taken));
            return new Blocked(
                    r.state() == ClusterQueue.State.STARTED && me.equals(r.claimedBy())
                            ? "Started already"
                            : "Starting on another controller");
        }
        String pinnedTo = r.pinnedTo();
        if (pinnedTo != null && !me.equals(pinnedTo) && SharedQueue.pinHolds(pinnedTo)) {
            return new Blocked("Waiting for the controller that scheduled it (it waits for the result)");
        }
        if (!me.equals(r.origin())
                && System.currentTimeMillis() < r.dueAt() + SharedQueue.ORIGIN_GRACE_MILLIS
                && SharedQueue.seemsAlive(r.origin())) {
            // the controller it was requested on goes first (e.g. it may hold the agents the build needs)
            return new Blocked("Waiting for the controller it was scheduled on");
        }
        Job<?, ?> job = (Job<?, ?>) item.task;
        if (SharedQueue.claiming.add(id)) {
            SharedQueue.claims.submit(() -> claim(job, id));
        }
        String why = SharedQueue.notClaimed.get(id);
        return new Blocked(why != null ? why : "Being claimed for this controller");
    }

    /**
     * Off the lock: claims an item for this controller (first taking its job's exclusive lease, for a job allowing
     * one build at a time), then has the queue look at it again.
     */
    private static void claim(Job<?, ?> job, long id) {
        ClusterQueue cq = SharedQueue.queue();
        String holder = ExclusiveLeases.claimHolder(id);
        boolean changed = true;
        try {
            if (cq == null) {
                return; // not shared (any more)
            }
            String me = SharedQueue.origin();
            ClusterQueue.Record r = cq.get(id);
            // not if a request folded into it since the gate looked pinned it to a live controller elsewhere (the
            // claim
            // checks it did not since)
            String pinnedTo = r == null ? null : r.pinnedTo();
            if (r != null
                    && r.state() == ClusterQueue.State.WAITING
                    && (pinnedTo == null || me.equals(pinnedTo) || !SharedQueue.pinHolds(pinnedTo))) {
                String reservedBy = QueueReservations.holder(id);
                if (reservedBy != null && !SharedQueue.origin().equals(reservedBy) && !SharedQueue.gone(reservedBy)) {
                    SharedQueue.notClaimed.put(
                            id, "Waiting for the agent the controller it was scheduled on is starting for it");
                    changed = false; // looked at again at the next maintenance, not at once
                    return;
                }
                if (!ExclusiveLeases.acquire(job, holder)) {
                    SharedQueue.notClaimed.put(id, "A build is already in progress on another controller");
                    changed = false; // looked at again at the next maintenance, not at once
                    return;
                }
                SharedQueue.notClaimed.remove(id);
                if (cq.claim(id, r.pinnedTo())) {
                    LOGGER.fine(() -> "Claimed queue item " + id);
                } else {
                    ExclusiveLeases.release(job, holder);
                }
            } else if (r == null || !SharedQueue.origin().equals(r.claimedBy())) {
                ExclusiveLeases.release(
                        job, holder); // held since a claim whose outcome was unknown, which went elsewhere
            }
            ClusterQueue.Record now = cq.get(id);
            if (now != null) {
                SharedQueue.knowClaimed(now);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Cannot claim queue item " + id + "; not starting it for now", e);
            if (Boolean.FALSE.equals(SharedQueue.claimedHere(cq, id))) {
                ExclusiveLeases.release(job, holder);
            }
            // not known: the lease is kept, and the claim tried again (it finds it went through, or releases it)
        } finally {
            SharedQueue.claiming.remove(id);
            if (SharedQueue.leftWhileClaiming.remove(id)) {
                ExclusiveLeases.release(
                        job, holder); // cancelled meanwhile (QueuePublisher.onLeft): the build will not start
            }
            if (changed) {
                Queue.getInstance().scheduleMaintenance();
            }
        }
    }

    /**
     * Off the lock: readies an item with a local id (taken while the store was unreachable) to start here, as a claim
     * does a shared item: once the store answers, so that its build number can be taken there (failing that once it
     * has left the queue would lose it), and its job's exclusive lease taken, so that it does not run beside a build
     * of the job on another controller. The store may still fail between this and the build number being taken.
     */
    private static void claimLocal(Job<?, ?> job, long id) {
        String holder = ExclusiveLeases.claimHolder(id);
        boolean changed = true;
        try {
            ClusterStore store = ClusterStore.get();
            if (store == null) {
                throw new IOException("No cluster store");
            }
            store.children(Keys.item(job)); // whether the store answers
            if (!ExclusiveLeases.acquire(job, holder)) {
                SharedQueue.notClaimed.put(id, "A build is already in progress on another controller");
                changed = false; // looked at again at the next maintenance, not at once
                return;
            }
            SharedQueue.notClaimed.remove(id);
            SharedQueue.readyLocal.add(id);
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.FINE, "Cannot ready queue item " + id + " with a local id; not starting it for now", e);
            SharedQueue.notClaimed.put(id, "Waiting for the persistence store");
            changed = false;
        } finally {
            SharedQueue.claiming.remove(id);
            if (SharedQueue.leftWhileClaiming.remove(id)) {
                SharedQueue.readyLocal.remove(id);
                ExclusiveLeases.release(
                        job, holder); // cancelled meanwhile (QueuePublisher.onLeft): the build will not start
            }
            if (changed) {
                Queue.getInstance().scheduleMaintenance();
            }
        }
    }
}
