package io.jenkins.plugins.cluster.queue;

import hudson.Extension;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.init.Terminator;
import hudson.model.Action;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.Queue;
import hudson.model.listeners.ItemListener;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.cluster.spi.ClusterQueue;
import io.jenkins.plugins.cluster.spi.Resync;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.persistence.PersistenceStores;
import jenkins.util.Timer;

/**
 * Applies changes to the cluster queue to this controller's queue, one at a time.
 */
public final class QueueMirror {

    /** As the class this was part of: logging configured for it applies. */
    private static final Logger LOGGER = Logger.getLogger(SharedQueue.class.getName());

    private static QueueMirror instance;

    private final AutoCloseable subscription;
    private final BlockingQueue<ClusterQueue.Record> changes = new LinkedBlockingQueue<>();
    private final Thread worker;

    private QueueMirror(ClusterQueue cq) {
        subscription = cq.subscribe(changes::add);
        worker = new Thread(this::run, "Shared queue mirror");
        worker.setDaemon(true);
    }

    /** Once the queue is loaded, which clears it first (and would drop items mirrored before). */
    @Initializer(after = InitMilestone.JOB_CONFIG_ADAPTED, requires = Queue.LOADED)
    public static synchronized void start() throws IOException {
        stop();
        // what was known of another store's queue (a Jenkins restarted in the same JVM) says nothing of this one's
        SharedQueue.known.clear();
        SharedQueue.sharing.clear();
        SharedQueue.cancelledWhileSharing.clear();
        SharedQueue.reconciling.clear();
        SharedQueue.claiming.clear();
        SharedQueue.readyLocal.clear();
        SharedQueue.notClaimed.clear();
        SharedQueue.leftWhileClaiming.clear();
        SharedQueue.droppedHere.clear();
        HandOffs.clear();
        ClusterQueue cq = SharedQueue.queue();
        if (cq == null) {
            return;
        }
        instance = new QueueMirror(cq);
        instance.changes.addAll(cq.pending());
        instance.worker.start();
        SharedQueueIds.fill(cq); // so that the first items do not wait for the store with the queue lock held
    }

    @Terminator
    public static synchronized void stop() {
        if (instance != null) {
            try {
                instance.subscription.close();
            } catch (Exception e) {
                LOGGER.log(Level.FINE, null, e);
            }
            instance.worker.interrupt();
            instance = null;
        }
    }

    /**
     * Applies again what the store has of the items this controller mirrors or follows, as if their events had just
     * arrived: every waiting or claimed record (as at startup), and the record of each item in the queue here or
     * handed off from it which is neither (started or cancelled since, perhaps elsewhere).
     */
    static void catchUp() throws IOException {
        QueueMirror m;
        synchronized (QueueMirror.class) {
            m = instance;
        }
        ClusterQueue cq = SharedQueue.queue();
        if (m == null || cq == null) {
            return;
        }
        List<ClusterQueue.Record> records = new ArrayList<>(cq.pending());
        Set<Long> listed = new HashSet<>();
        records.forEach(r -> listed.add(r.id()));
        Set<Long> ids = new TreeSet<>(HandOffs.ids());
        for (Queue.Item item : Queue.getInstance().getItems()) {
            if (item.getId() < SharedQueueIds.LOCAL_RANGE) {
                ids.add(item.getId());
            }
        }
        for (long id : ids) {
            if (!listed.contains(id)) {
                ClusterQueue.Record r = cq.get(id);
                if (r != null) {
                    records.add(r);
                }
            }
        }
        m.changes.addAll(records);
    }

    /** Catches the mirror up with the store: events may have been lost. */
    @Extension
    public static final class CatchUp extends Resync {
        @Override
        public void catchUp() throws IOException {
            QueueMirror.catchUp();
        }
    }

    /** Whether every change received so far has been applied, for tests. */
    static synchronized boolean isIdle() {
        return instance == null || instance.changes.isEmpty() && instance.busy == 0;
    }

    private volatile int busy;

    private void run() {
        while (true) {
            ClusterQueue.Record r;
            try {
                r = changes.take();
            } catch (InterruptedException e) {
                return;
            }
            busy = 1;
            try (ACLContext ctx = ACL.as2(ACL.SYSTEM2)) {
                apply(r);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "Failed to apply queue change " + r, e);
            } finally {
                busy = 0;
            }
        }
    }

    private static void apply(ClusterQueue.Record r) {
        String me = SharedQueue.origin();
        if (SharedQueue.localItem(r.id()) != null) {
            SharedQueue.know(r); // for the gate, which decides from what is known
        }
        switch (r.state()) {
            case WAITING -> {
                if (SharedQueue.localItem(r.id()) != null) {
                    // e.g. another controller folded a request into it: take over its causes and quiet period
                    // (unless claimed here since: this change came late, and its actions are older)
                    Calendar due = new GregorianCalendar();
                    due.setTimeInMillis(r.dueAt());
                    List<Action> actions = SharedQueue.fromXml(r.actions());
                    Queue.withLock(() -> {
                        if (!SharedQueue.knownClaimedHere(r.id())) {
                            Queue.getInstance().updateShared(r.id(), due, actions);
                        }
                    });
                } else if (!me.equals(r.origin()) || SharedQueue.droppedHere.contains(r.id())) {
                    // our own items are recreated only if dropped: else absent here means they already left
                    create(r);
                }
            }
            case CLAIMED -> {
                if (!me.equals(r.claimedBy())) {
                    SharedQueue.dropLocal(r.id(), true);
                }
            }
            case STARTED -> {
                if (!me.equals(r.claimedBy())) {
                    SharedQueue.dropLocal(r.id(), true);
                    HandOffs.startedElsewhere(r);
                }
            }
            case CANCELLED -> SharedQueue.dropLocal(r.id(), false);
            default -> throw new IllegalStateException(r.toString());
        }
    }

    /** Mirrors a waiting item, if it is not here yet. */
    static void mirror(long id) {
        try {
            ClusterQueue cq = SharedQueue.queue();
            ClusterQueue.Record r = cq == null ? null : cq.get(id);
            if (r != null && r.state() == ClusterQueue.State.WAITING && SharedQueue.localItem(id) == null) {
                create(r);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Failed to mirror queue item " + id, e);
        }
    }

    private static void create(ClusterQueue.Record event) {
        ClusterQueue.Record r;
        try {
            ClusterQueue cq = SharedQueue.queue();
            if (cq == null) {
                return; // not shared (any more)
            }
            r = cq.get(event.id()); // the event may be stale: the item may have been claimed meanwhile
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Cannot mirror queue item " + event.id(), e);
            return;
        }
        Queue.Item here = r == null ? null : Queue.getInstance().getItem(r.id());
        if (r == null
                || r.state() != ClusterQueue.State.WAITING
                || here != null && !(here instanceof Queue.LeftItem && SharedQueue.droppedHere.contains(r.id()))) {
            return; // e.g. it left here (an item put back after being dropped here is mirrored again)
        }
        Job<?, ?> job = SharedQueue.jobOf(r);
        if (job == null) {
            // its job may not be here yet: item changes are applied apart from queue changes
            if (awaitingJob.size() > 10_000) {
                awaitingJob.clear(); // jobs which never came
            }
            awaitingJob
                    .computeIfAbsent(r.itemUuid(), k -> ConcurrentHashMap.newKeySet())
                    .add(r.id());
            job = SharedQueue.jobOf(r); // created meanwhile?
            if (job == null) {
                LOGGER.fine(() -> "Not mirroring " + r + " until its job is here");
                return;
            }
            awaitingJob.computeIfPresent(r.itemUuid(), (k, ids) -> ids.remove(r.id()) && ids.isEmpty() ? null : ids);
        }
        if (!(job instanceof Queue.Task task)) {
            return;
        }
        int quietPeriod = (int) Math.max(0, (r.dueAt() - System.currentTimeMillis() + 999) / 1000);
        SharedQueue.know(r);
        SharedQueueIds.MIRRORED.set(r.id());
        try {
            // never folded here: records the shared queue kept apart stay apart
            PersistenceStores.applyingRemoteChange(
                    () -> Queue.getInstance().scheduleShared(task, quietPeriod, SharedQueue.fromXml(r.actions())));
        } finally {
            SharedQueueIds.MIRRORED.remove();
        }
        SharedQueue.droppedHere.remove(r.id());
    }

    /** Waiting items whose job was not here when they were mirrored, by the job's identity: they are once it comes. */
    private static final Map<String, Set<Long>> awaitingJob = new ConcurrentHashMap<>();

    @Extension
    public static final class JobArrivals extends ItemListener {
        @Override
        public void onCreated(Item item) {
            arrived(item);
        }

        @Override
        public void onLoaded() {
            for (String uuid : List.copyOf(awaitingJob.keySet())) {
                Set<Long> ids = awaitingJob.remove(uuid);
                if (ids != null) {
                    Timer.get().submit(() -> {
                        try (ACLContext ctx = ACL.as2(ACL.SYSTEM2)) {
                            ids.forEach(QueueMirror::mirror);
                        }
                    });
                }
            }
        }

        private static void arrived(Item item) {
            String uuid = item.getItemUuid();
            Set<Long> ids = uuid == null ? null : awaitingJob.remove(uuid);
            if (ids != null) {
                Timer.get().submit(() -> {
                    try (ACLContext ctx = ACL.as2(ACL.SYSTEM2)) {
                        ids.forEach(QueueMirror::mirror);
                    }
                });
            }
        }
    }
}
