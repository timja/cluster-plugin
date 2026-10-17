package io.jenkins.plugins.cluster.queue;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.init.Terminator;
import hudson.model.Action;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.FileParameterValue;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.ItemListener;
import hudson.model.listeners.RunListener;
import hudson.model.queue.CauseOfBlockage;
import hudson.model.queue.FoldableAction;
import hudson.model.queue.QueueListener;
import hudson.model.queue.SubTask;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.slaves.Cloud;
import hudson.slaves.CloudProvisioningListener;
import hudson.slaves.NodeProvisioner;
import hudson.util.DaemonThreadFactory;
import hudson.util.NamingThreadFactory;
import hudson.util.Secret;
import io.jenkins.plugins.cluster.builds.BuildElsewhere;
import io.jenkins.plugins.cluster.builds.ExclusiveLeases;
import io.jenkins.plugins.cluster.builds.RunningBuilds;
import io.jenkins.plugins.cluster.spi.ClusterQueue;
import io.jenkins.plugins.cluster.spi.ClusterStore;
import io.jenkins.plugins.cluster.spi.Keys;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.ItemUuids;
import jenkins.model.Jenkins;
import jenkins.model.queue.QueueIdStrategy;
import jenkins.model.queue.QueueStartGate;
import jenkins.persistence.PersistenceStore;
import jenkins.persistence.PersistenceStores;
import jenkins.security.HMACConfidentialKey;
import jenkins.util.SystemProperties;
import jenkins.util.Timer;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Shares the build queue between controllers through a {@link ClusterQueue}.
 *
 * <p>Every controller keeps its own {@link Queue}, which still applies quiet periods, blocking and executor matching;
 * the cluster queue is the source of truth for which items exist:
 * <ul>
 * <li>an item scheduled here is published; scheduling a job and parameters already waiting anywhere folds into that item
 * (causes merge, the earlier quiet period wins), so it builds once
 * <li>items published elsewhere are mirrored here, with the same id ({@link Queue#scheduleShared})
 * <li>an item may only start on the controller which claims it ({@link QueueStartGate}); the others hand their copy off
 * ({@link Queue#handOff}): it leaves their queue taken elsewhere, and its future completes with the build elsewhere
 * <li>cancelling an item anywhere cancels it everywhere
 * </ul>
 * Only items of jobs are shared, by the job's identity; parts of running builds (such as Pipeline {@code node} blocks)
 * stay with their build.
 */
@Restricted(NoExternalUse.class)
public final class SharedQueue {

    private static final Logger LOGGER = Logger.getLogger(SharedQueue.class.getName());

    /**
     * How long after its quiet period an item is left to the controller it was scheduled on, before others may start it.
     * A Pipeline's agents are only known once it runs, so by default a build starts where it was requested, if that controller can.
     */
    @SuppressFBWarnings(value = "MS_SHOULD_BE_FINAL", justification = "for tests and the script console")
    public static /* non-final for tests and the script console */ long ORIGIN_GRACE_MILLIS =
            SystemProperties.getLong(SharedQueue.class.getName() + ".originGraceMillis", 10_000L);

    /** Whether the build queue is shared through the cluster store; on by default. */
    static final boolean ENABLED = SystemProperties.getBoolean(SharedQueue.class.getName() + ".enabled", true);

    private SharedQueue() {}

    @CheckForNull
    static ClusterQueue queue() {
        ClusterStore store = ClusterStore.get();
        return store == null || !ENABLED ? null : store.queue();
    }

    private static String origin() {
        ClusterStore store = ClusterStore.get();
        if (store == null) {
            throw new IllegalStateException("No cluster store");
        }
        return store.getOrigin();
    }

    static boolean shared(@NonNull Queue.Task task) {
        return task instanceof Job<?, ?> job
                && !(task instanceof Queue.TransientTask)
                && job.getItemUuid() != null
                && ItemUuids.isPinned(job) // a row under a name-derived identity would be another item's once pinned
                && queue() != null;
    }

    /**
     * Items with equal keys fold into one, as the local queue does by task and {@link ParametersAction}.
     * Each parameter is encoded with explicit lengths (so no value can pass for several parameters); sensitive values
     * enter it only as a keyed hash, so that queue records reveal nothing of them.
     * Other actions which decide or take part in folding locally ({@link Queue.QueueAction}, such as a replay's, and
     * {@link FoldableAction}, such as a {@code build} step's) cannot be asked across controllers: they enter the key as
     * they are stored, so that a request carrying one folds only into an item carrying an equal one.
     */
    static String dedupKey(@NonNull Queue.Task task, @NonNull List<Action> actions) {
        List<byte[]> parameters = new ArrayList<>();
        List<byte[]> others = new ArrayList<>();
        for (Action a : actions) {
            if (a instanceof ParametersAction pa) {
                for (ParameterValue v : pa.getParameters()) {
                    // while queued a ParametersAction may hold nulls; one field cannot pass for a parameter's three
                    parameters.add(v == null ? encode("null") : encode(v));
                }
            } else if (a instanceof Queue.QueueAction || a instanceof FoldableAction && !(a instanceof CauseAction)) {
                others.add(encode(a.getClass().getName(), Queue.XSTREAM.toXML(a)));
            }
        }
        String name = ((Job<?, ?>) task).getItemUuid(); // a rename does not change which items fold together
        if (parameters.isEmpty() && others.isEmpty()) {
            return name;
        }
        String key = parameters.isEmpty() ? name : name + "#" + digest(parameters);
        return others.isEmpty() ? key : key + "#actions:" + digest(others);
    }

    private static String digest(List<byte[]> parts) {
        parts.sort(Arrays::compare);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (byte[] p : parts) {
                digest.update(ByteBuffer.allocate(4).putInt(p.length).array());
                digest.update(p);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    /** Keys the hash of sensitive parameter values; shared by the controllers once runtime keys are. */
    private static final HMACConfidentialKey SENSITIVE =
            new HMACConfidentialKey(SharedQueue.class, "sensitiveParameters");

    private static byte[] encode(ParameterValue v) {
        Object value = v.getValue();
        String text = value instanceof Secret secret ? secret.getPlainText() : String.valueOf(value);
        if (v.isSensitive()) {
            text = "mac:" + SENSITIVE.mac(text);
        }
        return encode(v.getName(), v.getClass().getName(), text);
    }

    private static byte[] encode(String... fields) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (String field : fields) {
            byte[] b = String.valueOf(field).getBytes(StandardCharsets.UTF_8);
            bytes.writeBytes(ByteBuffer.allocate(4).putInt(b.length).array());
            bytes.writeBytes(b);
        }
        return bytes.toByteArray();
    }

    /**
     * The controller an item must run on, or null if any may run it: a {@code build} step waiting only for its
     * downstream build to start needs that build itself (asked of pipeline-build-step's relay, if installed), and an
     * uploaded file parameter is only here.
     */
    @CheckForNull
    private static String pinnedTo(@NonNull Queue.Item item, @NonNull List<Action> actions) {
        for (Action a : actions) {
            if (a instanceof ParametersAction pa
                    && pa.getParameters().stream().anyMatch(FileParameterValue.class::isInstance)) {
                return origin();
            }
        }
        Jenkins j = Jenkins.getInstanceOrNull();
        if (j != null && j.getPlugin("pipeline-build-step") != null && BuildStepPins.pinsToOrigin(item)) {
            return origin();
        }
        return null;
    }

    /** Apart, so that nothing of pipeline-build-step is loaded without it. */
    private static final class BuildStepPins {
        static boolean pinsToOrigin(Queue.Item item) {
            org.jenkinsci.plugins.workflow.support.steps.build.BuildTriggerRelay relay =
                    org.jenkinsci.plugins.workflow.support.steps.build.BuildTriggerRelay.get();
            return relay != null && relay.pinsToOrigin(item);
        }
    }

    /** Whether a pin still holds: its controller is alive (a pin to one that died must not keep the item forever). */
    private static boolean pinHolds(@NonNull String pinnedTo) {
        return seemsAlive(pinnedTo); // as last read, off the queue lock
    }

    static String toXml(@NonNull List<? extends Action> actions) {
        return Queue.XSTREAM.toXML(new ArrayList<>(actions));
    }

    @SuppressWarnings("unchecked")
    static List<Action> fromXml(@NonNull String xml) {
        return (List<Action>) Queue.XSTREAM.fromXML(xml);
    }

    private static long dueOf(Queue.Item item) {
        return item instanceof Queue.WaitingItem wi ? wi.timestamp.getTimeInMillis() : System.currentTimeMillis();
    }

    /**
     * Merges a request folded into an item: its causes join the item's, and its other {@link FoldableAction}s (such as a
     * {@code build} step's, which the local queue folded into its copy) are added unless an equal one is there.
     */
    private static List<Action> merge(List<Action> into, List<? extends Action> from) {
        List<Cause> causes = new ArrayList<>();
        for (Action a : into) {
            if (a instanceof CauseAction ca) {
                causes.addAll(ca.getCauses());
            }
        }
        for (Action a : from) {
            if (a instanceof CauseAction ca) {
                causes.addAll(ca.getCauses());
            }
        }
        List<Action> merged = new ArrayList<>();
        Set<String> present = new HashSet<>();
        for (Action a : into) {
            if (!(a instanceof CauseAction)) {
                merged.add(a);
                present.add(Queue.XSTREAM.toXML(a));
            }
        }
        for (Action a : from) {
            if (a instanceof FoldableAction && !(a instanceof CauseAction) && present.add(Queue.XSTREAM.toXML(a))) {
                merged.add(a);
            }
        }
        merged.add(new CauseAction(causes));
        return merged;
    }

    /** The item with this id in this controller's queue, waiting, blocked or buildable (not one that left). */
    @CheckForNull
    private static Queue.Item localItem(long id) {
        Queue.Item item = Queue.getInstance().getItem(id);
        return item instanceof Queue.LeftItem ? null : item;
    }

    /** The job with this identity here (whoever asks), or null. */
    @CheckForNull
    private static Job<?, ?> jobOf(@NonNull ClusterQueue.Record r) {
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            return ItemUuids.get(r.itemUuid()) instanceof Job<?, ?> job ? job : null;
        }
    }

    // ------------------------------------------------------------------ what is decided under the queue lock

    /*
     * The queue lock is held while items are scheduled and while the queue is maintained (QueueStartGate), so nothing
     * here talks to the store with it held: a slow store would stop the whole queue. Publishing goes through an outbox,
     * the gate decides from the records this controller knows (fed by the mirror), and claims are made off the lock.
     * One exception: a new item's id, when the pool of ids taken ahead ran dry (see Ids.nextId).
     */

    /** The records of items in this controller's queue, as last read or delivered. */
    static final Map<Long, ClusterQueue.Record> known = new ConcurrentHashMap<>();
    /** Local items being shared (published, or folded into an item from elsewhere): they do not start meanwhile. */
    static final Set<Long> sharing = ConcurrentHashMap.newKeySet();
    /** Items cancelled here while being shared, whose cancellation is shared once they are. */
    private static final Set<Long> cancelledWhileSharing = ConcurrentHashMap.newKeySet();
    /** Items whose record is being looked up, nothing being known of it here. */
    private static final Set<Long> reconciling = ConcurrentHashMap.newKeySet();
    /** Items with a claim under way. */
    private static final Set<Long> claiming = ConcurrentHashMap.newKeySet();
    /** Items with a local id which may start here: the store answered, and their job's exclusive lease is held. */
    private static final Set<Long> readyLocal = ConcurrentHashMap.newKeySet();

    /**
     * A few threads: items are shared independently, so one waiting on a slow write does not hold up the others; the
     * changes of one item go through one thread, in order (its publication before the folds into it).
     */
    private static final ExecutorService[] outbox = new ExecutorService[4];

    static {
        for (int i = 0; i < outbox.length; i++) {
            outbox[i] = Executors.newSingleThreadExecutor(
                    new NamingThreadFactory(new DaemonThreadFactory(), "Shared queue outbox " + i));
        }
    }

    private static ExecutorService outbox(long id) {
        return outbox[(int) Math.floorMod(id, (long) outbox.length)];
    }

    private static final ExecutorService claims =
            Executors.newFixedThreadPool(4, new NamingThreadFactory(new DaemonThreadFactory(), "Shared queue claims"));

    /** Records a record, unless it is older than the one known (records only move on: waiting, claimed, started). */
    static void know(@NonNull ClusterQueue.Record r) {
        known.merge(r.id(), r, (old, now) -> rank(now) >= rank(old) ? now : old);
    }

    private static int rank(ClusterQueue.Record r) {
        return switch (r.state()) {
            case WAITING -> 0;
            case CLAIMED -> 1;
            case STARTED, CANCELLED -> 2;
        };
    }

    /**
     * Records a record read after a claim. Claimed here, the local item first takes its actions as claimed, before the
     * gate (which decides from what is known) lets it start: a fold made elsewhere just before may not have been
     * mirrored yet. Both under the queue lock, so that a late mirrored change does not put older actions back between.
     */
    private static void knowClaimed(@NonNull ClusterQueue.Record now) {
        if (now.state() != ClusterQueue.State.CLAIMED || !origin().equals(now.claimedBy())) {
            know(now);
            return;
        }
        Queue.withLock(() -> {
            Calendar due = new GregorianCalendar();
            due.setTimeInMillis(now.dueAt());
            Queue.getInstance().updateShared(now.id(), due, fromXml(now.actions()));
            know(now);
        });
    }

    /** Whether an item is known to be claimed here: its actions are then those it was claimed with (knowClaimed). */
    private static boolean knownClaimedHere(long id) {
        ClusterQueue.Record k = known.get(id);
        return k != null && k.state() == ClusterQueue.State.CLAIMED && origin().equals(k.claimedBy());
    }

    /** Live origins as last read, refreshed off the lock; null if unknown (taken as everyone alive). */
    private static volatile Set<String> live;

    private static volatile long liveAt;
    private static final AtomicBoolean liveRefreshing = new AtomicBoolean();

    /** Whether an origin was alive when last asked; asks again, off the lock, when that is a few seconds old. */
    private static boolean seemsAlive(String origin) {
        if (System.currentTimeMillis() - liveAt > 2_000 && liveRefreshing.compareAndSet(false, true)) {
            claims.submit(() -> {
                try {
                    live = RunningBuilds.liveOrigins();
                    liveAt = System.currentTimeMillis();
                } catch (IOException | RuntimeException e) {
                    LOGGER.log(Level.FINE, "Cannot read which controllers are alive", e);
                } finally {
                    liveRefreshing.set(false);
                }
            });
        }
        Set<String> l = live;
        return l == null || l.contains(origin);
    }

    /** Why a shared item does not start here. */
    static final class Blocked extends CauseOfBlockage {
        private final String text;

        Blocked(String text) {
            this.text = text;
        }

        @Override
        public String getShortDescription() {
            return text;
        }
    }

    /**
     * Takes an item out of this controller's queue because it was claimed or started elsewhere ({@code taken}: it is
     * handed off, and its future follows the build elsewhere), or cancelled elsewhere (it is cancelled here too).
     */
    private static void dropLocal(long id, boolean taken) {
        PersistenceStores.applyingRemoteChange(() -> {
            if (taken) {
                Queue.HandOff handOff = Queue.getInstance().handOff(id);
                if (handOff != null) {
                    LOGGER.fine(() -> "Queue item " + id + " was taken by another controller");
                    HandOffs.add(id, handOff);
                    droppedHere.add(id);
                }
            } else {
                Queue.Item item = localItem(id);
                if (item != null && Queue.getInstance().cancel(item)) {
                    LOGGER.fine(() -> "Dropped queue item " + id + ", cancelled elsewhere");
                    droppedHere.add(id);
                }
                HandOffs.cancelled(id);
            }
            return null;
        });
    }

    /**
     * Items dropped here because they were taken or cancelled elsewhere: should the controller which took one go away
     * before starting it, the store puts it back, under the same id, and it is mirrored again (Mirror.create).
     */
    private static final Set<Long> droppedHere =
            Collections.newSetFromMap(Collections.synchronizedMap(new LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
                    return size() > 10_000;
                }
            }));

    // ------------------------------------------------------------------ local changes → cluster

    @Extension
    public static final class Publisher extends QueueListener {

        @Override
        public void onEnterWaiting(Queue.WaitingItem wi) {
            if (PersistenceStores.isApplyingRemoteChange() || !shared(wi.task)) {
                return;
            }
            if (wi.getId() >= Ids.LOCAL_RANGE) {
                // a local id, taken while the cluster sequence was unreachable: another controller may have taken the
                // same one, so it is never shared; it stays (and builds) here
                return;
            }
            // under the queue lock (schedule2): only prepared here, shared by the outbox; it does not start meanwhile
            List<Action> actions = List.copyOf(wi.getActions());
            ClusterQueue.Record record = record(wi, actions);
            sharing.add(wi.getId());
            outbox(wi.getId()).submit(() -> share(wi, record, actions));
        }

        private static ClusterQueue.Record record(Queue.Item item, List<Action> actions) {
            String key = dedupKey(item.task, actions);
            if (keptApart(actions)) {
                key += "#apart:" + item.getId(); // folds into nothing
            }
            Job<?, ?> job = (Job<?, ?>) item.task;
            return new ClusterQueue.Record(
                    item.getId(),
                    job.getItemUuid(),
                    job.getFullName(),
                    key,
                    toXml(actions),
                    dueOf(item),
                    ClusterQueue.State.WAITING,
                    origin(),
                    pinnedTo(item, actions),
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
                ClusterQueue cq = queue();
                if (cq == null) {
                    return; // not shared (any more)
                }
                ClusterQueue.Record r = cq.get(id);
                if (r != null) {
                    know(r);
                    return;
                }
                Queue.Item item = localItem(id);
                if (item != null && sharing.add(id)) {
                    List<Action> actions = List.copyOf(item.getActions());
                    share(item, record(item, actions), actions);
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.FINE, "Cannot look up queue item " + id + "; not starting it for now", e);
            } finally {
                reconciling.remove(id);
                Queue.getInstance().scheduleMaintenance();
            }
        }

        private static void share(Queue.Item item, ClusterQueue.Record record, List<Action> actions) {
            long id = item.getId();
            try (ACLContext ctx = ACL.as2(ACL.SYSTEM2)) { // to see the job when mirroring the item folded into
                ClusterQueue cq = queue();
                if (cq == null) {
                    return; // not shared (any more)
                }
                ClusterQueue.Record stored = cq.publish(record);
                know(stored);
                if (stored.id() != item.getId()) {
                    // the same job and parameters were already waiting (scheduled elsewhere at the same time): fold
                    // into
                    // that item, then drop this one, which has not started (the gate held it)
                    LOGGER.fine(() -> item + " folds into queue item " + stored.id() + " from " + stored.origin());
                    if (!fold(cq, stored.id(), actions, dueOf(item), record.pinnedTo())) {
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
                    outbox(id).execute(() -> foldedInto.remove(id)); // after those: none come once it is gone
                    Mirror.mirror(stored.id());
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.WARNING, "Failed to share " + item + "; it does not start until it is", e);
            } finally {
                sharing.remove(id);
                if (cancelledWhileSharing.remove(id)) {
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
        private static boolean fold(
                ClusterQueue cq, long id, List<Action> actions, long due, @CheckForNull String pinTo)
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
                        toXml(merge(fromXml(now.actions()), actions)),
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
            if (PersistenceStores.isApplyingRemoteChange() || !shared(item.task)) {
                return;
            }
            long id = item.getId();
            List<Action> actions = List.copyOf(request);
            long due = dueOf(item);
            String pinTo = pinnedTo(item, actions);
            // after the item's publication (the same outbox thread): only the request is merged into what is stored
            outbox(id).submit(() -> {
                try {
                    fold(queue(), foldedInto.getOrDefault(id, id), actions, due, pinTo);
                } catch (IOException | RuntimeException e) {
                    LOGGER.log(Level.WARNING, "Failed to share changes to queue item " + id, e);
                }
            });
        }

        @Override
        public void onLeft(Queue.LeftItem li) {
            long id = li.getId();
            known.remove(id);
            notClaimed.remove(id);
            readyLocal.remove(id);
            if ((li.isCancelled() || li.isTakenElsewhere()) && li.task instanceof Job<?, ?> job) {
                // what a claim here held of its job's exclusive lease (not under the queue lock, which this holds); one
                // still under way lets go of it when it ends. Taken elsewhere, its build runs there: a hold kept here
                // would let the job's next item be claimed here beside it.
                if (claiming.contains(id)) {
                    leftWhileClaiming.add(id);
                    if (!claiming.contains(id)) {
                        leftWhileClaiming.remove(id); // it ended meanwhile: released below
                    }
                }
                Timer.get().submit(() -> ExclusiveLeases.release(job, ExclusiveLeases.claimHolder(id)));
            }
            if (li.isTakenElsewhere()) {
                return; // handed off: its record is another controller's now
            }
            if (!li.isCancelled() && shared(li.task)) {
                // left to start here: should its task make no build (a job disabled meanwhile, one failing to make its
                // build), its record would stay claimed by this controller, built nowhere
                Timer.get().schedule(() -> leftWithoutBuild(li), LEFT_WITHOUT_BUILD_SECONDS, TimeUnit.SECONDS);
            }
            if (li.isCancelled() && shared(li.task)) {
                Timer.get().submit(() -> Reservations.release(id)); // a store call: not under the queue lock
            }
            if (!li.isCancelled() || PersistenceStores.isApplyingRemoteChange() || !shared(li.task)) {
                return;
            }
            // while it is being published, the store may not have it yet: share() cancels it once it does
            cancelledWhileSharing.add(id);
            if (sharing.contains(id) || !cancelledWhileSharing.remove(id)) {
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
                                    ClusterQueue cq = queue();
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
                                                Level.WARNING,
                                                "Failed to share the cancellation of queue item " + id,
                                                e);
                                    }
                                }
                            },
                            attempt == 0 ? 0 : 1L << Math.min(attempt, 6),
                            TimeUnit.SECONDS);
        }
    }

    /** How long after an item left the queue here to start its build must have started. */
    static /* non-final for tests */ long LEFT_WITHOUT_BUILD_SECONDS = 60;

    /**
     * An item which left the queue here to start, but whose task made no build (a job disabled meanwhile, say, or one
     * failing to make its build): its shared record is cancelled, or it would stay claimed by this controller, built
     * nowhere; and what its claim held of its job's exclusive lease is released.
     */
    private static void leftWithoutBuild(Queue.LeftItem li) {
        long id = li.getId();
        ClusterQueue cq = queue();
        if (cq == null || li.getExecutable() != null) {
            return; // it started
        }
        try {
            ClusterQueue.Record r = cq.get(id);
            if (r == null || r.state() != ClusterQueue.State.CLAIMED || !origin().equals(r.claimedBy())) {
                return; // started (its start recorded), or no longer this controller's
            }
            if (cq.cancel(id)) {
                LOGGER.info(() -> "Queue item " + id + " left the queue here without a build; cancelled");
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.log(
                    Level.WARNING,
                    "Failed to cancel queue item " + id + ", which left the queue here without a build",
                    e);
        }
        if (li.task instanceof Job<?, ?> job) {
            ExclusiveLeases.release(job, ExclusiveLeases.claimHolder(id)); // no build holds it
        }
    }

    /**
     * Whether a claim that failed with an exception went through after all.
     *
     * @return null if that is not known either (the store cannot be read)
     */
    @CheckForNull
    private static Boolean claimedHere(ClusterQueue cq, long id) {
        try {
            ClusterQueue.Record r = cq.get(id);
            return r != null && origin().equals(r.claimedBy());
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Items whose controller is starting cloud agents for them. With the shared queue, the controller an item was
     * scheduled on provisions for it (it alone counts it as demand), but starting an agent can take longer than the
     * origin's head start; without a reservation, another controller with a free executor would take the item, and the
     * agent started for it would have nothing to do. A reservation is a store entry ({@link Keys#queueReservation})
     * holding the controller's origin; others wait for it while that controller is alive, for at most
     * {@link #RESERVATION_MILLIS}. It is a hint, not a fence: a controller claiming the item at the moment it is reserved
     * may still take it, and the agent started for it then idles until its cloud's retention removes it.
     */
    @Extension
    public static final class Reservations extends CloudProvisioningListener {

        /** The origin of the controller holding a current reservation of an item, or null. */
        @CheckForNull
        static String holder(long id) throws IOException {
            ClusterStore store = ClusterStore.get();
            PersistenceStore.Entry e = store == null ? null : store.read(Keys.queueReservation(id));
            if (e == null) {
                return null;
            }
            String[] parts = new String(e.content(), StandardCharsets.UTF_8).split(" ", 2);
            try {
                if (parts.length < 2
                        || System.currentTimeMillis() - Long.parseLong(parts[1].trim()) > RESERVATION_MILLIS) {
                    return null;
                }
            } catch (NumberFormatException x) {
                return null;
            }
            return parts[0];
        }

        static void release(long id) {
            ClusterStore store = ClusterStore.get();
            if (store != null) {
                try {
                    store.delete(Keys.queueReservation(id), PersistenceStore.ANY);
                } catch (IOException | RuntimeException e) {
                    LOGGER.log(Level.FINE, e, () -> "Could not release the reservation of queue item " + id);
                }
            }
        }

        @Override
        public void onStarted(
                Cloud cloud, hudson.model.Label label, Collection<NodeProvisioner.PlannedNode> plannedNodes) {
            ClusterQueue cq = queue();
            ClusterStore store = ClusterStore.get();
            if (cq == null || store == null) {
                return;
            }
            int executors = plannedNodes.stream().mapToInt(p -> p.numExecutors).sum();
            String me = origin();
            for (Queue.BuildableItem bi : Queue.getInstance().getBuildableItems()) {
                if (executors <= 0) {
                    break;
                }
                // demand is counted per part of the task (as LoadStatistics does): any part needing this label
                int needing = 0;
                for (SubTask st : bi.task.getSubTasks()) {
                    if (Objects.equals(label, bi.getAssignedLabelFor(st))) {
                        needing++;
                    }
                }
                if (!shared(bi.task) || needing == 0) {
                    continue;
                }
                try {
                    ClusterQueue.Record r = cq.get(bi.getId());
                    if (r == null || r.claimedBy() != null || !provisionsHere(r, System.currentTimeMillis())) {
                        continue;
                    }
                    String reservedBy = holder(bi.getId());
                    if (reservedBy != null && (me.equals(reservedBy) || !gone(reservedBy))) {
                        continue;
                    }
                    store.write(
                            Keys.queueReservation(bi.getId()),
                            (me + " " + System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8),
                            PersistenceStore.ANY);
                    executors -= needing;
                    LOGGER.fine(() -> "Reserved " + bi + " while starting an agent for it on " + cloud.name);
                } catch (IOException | RuntimeException e) {
                    LOGGER.log(Level.FINE, e, () -> "Could not reserve " + bi);
                }
            }
        }
    }

    /** Whether a controller which reserved an item is known to be gone (its reservation then holds nothing up). */
    private static boolean gone(String origin) throws IOException {
        Set<String> live = RunningBuilds.liveOrigins();
        return live != null && !live.contains(origin);
    }

    /**
     * Whether this controller provisions for an item: the one that claimed it, or it is pinned to; otherwise its origin
     * while alive (it goes first, and may hold the agents the build needs); otherwise, any.
     */
    static boolean provisionsHere(@NonNull ClusterQueue.Record r, long now) throws IOException {
        String me = origin();
        if (r.claimedBy() != null) {
            return me.equals(r.claimedBy());
        }
        String pinnedTo = r.pinnedTo();
        if (pinnedTo != null && pinHolds(pinnedTo)) {
            return me.equals(pinnedTo);
        }
        if (me.equals(r.origin())) {
            return true;
        }
        Set<String> live = RunningBuilds.liveOrigins();
        if (live == null) {
            // membership unknown: as the gate decides, its controller goes first, any other after a while
            return now > r.dueAt() + ORIGIN_GRACE_MILLIS && !reservedElsewhere(r.id(), me, null);
        }
        // its controller is gone: one of the others provisions, the same one whichever of them asks (unless another
        // reserved it first, before a controller joining changed which one that is)
        return !live.contains(r.origin())
                && me.equals(provisioner(r.id(), live))
                && !reservedElsewhere(r.id(), me, live);
    }

    /** Whether another controller, not known to be gone, holds a reservation of an item: it is starting an agent for it. */
    private static boolean reservedElsewhere(long id, String me, @CheckForNull Set<String> live) throws IOException {
        String holder = Reservations.holder(id);
        return holder != null && !holder.equals(me) && (live == null || live.contains(holder));
    }

    /** How long a reservation holds at most (the agent may never come up). */
    @SuppressFBWarnings(value = "MS_SHOULD_BE_FINAL", justification = "for tests and the script console")
    public static /* non-final for tests and the script console */ long RESERVATION_MILLIS =
            SystemProperties.getLong(SharedQueue.class.getName() + ".reservationMillis", 5 * 60_000L);

    /** The live controller which provisions for an item whose controller is gone: highest hash of item and controller. */
    @CheckForNull
    static String provisioner(long id, @NonNull Set<String> live) {
        String chosen = null;
        int best = 0;
        for (String o : new TreeSet<>(live)) {
            int h = (o + "#" + id).hashCode();
            if (chosen == null || h > best) {
                chosen = o;
                best = h;
            }
        }
        return chosen;
    }

    @Extension
    public static final class Gate implements QueueStartGate {
        /** Recent provisioning answers per item id: load statistics ask for every label, every few seconds. */
        private final Map<Long, Map.Entry<Long, Boolean>> provisioning = new ConcurrentHashMap<>();

        /** Only one controller provisions for an item: see {@link #provisionsHere}. */
        @Override
        public boolean countsForProvisioning(@NonNull Queue.BuildableItem item) {
            if (!shared(item.task)) {
                return true;
            }
            long now = System.currentTimeMillis();
            Map.Entry<Long, Boolean> cached = provisioning.get(item.getId());
            if (cached != null && now - cached.getKey() < 2_000) {
                return cached.getValue();
            }
            boolean answer;
            try {
                ClusterQueue cq = queue();
                ClusterQueue.Record r = cq == null ? null : cq.get(item.getId());
                answer = r == null || provisionsHere(r, now);
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
            if (!shared(item.task)) {
                return null;
            }
            // under the queue lock: from what is known only; the store is asked off the lock (claim below)
            long id = item.getId();
            if (id >= Ids.LOCAL_RANGE) {
                // a local id, never shared (see Publisher.onEnterWaiting): it builds here, once the store answers (its
                // build number is taken there, after it has left the queue) and with its job's exclusive lease
                if (readyLocal.contains(id)) {
                    return null;
                }
                if (claiming.add(id)) {
                    Job<?, ?> job = (Job<?, ?>) item.task;
                    claims.submit(() -> claimLocal(job, id));
                }
                String why = notClaimed.get(id);
                return new Blocked(why != null ? why : "Waiting for the persistence store");
            }
            if (sharing.contains(id)) {
                return new Blocked("Being shared with the other controllers");
            }
            ClusterQueue.Record r = known.get(id);
            if (r == null) {
                // restored from before a restart, or its publication failed (perhaps after the store had it): whether
                // it may start depends on its record, if any; during a store outage it waits rather than risk a
                // duplicate
                if (reconciling.add(id)) {
                    outbox(id).submit(() -> Publisher.reconcile(id));
                }
                return new Blocked("Being checked with the other controllers");
            }
            String me = origin();
            if (me.equals(r.claimedBy()) && r.state() == ClusterQueue.State.CLAIMED) {
                return null;
            }
            if (r.state() != ClusterQueue.State.WAITING) {
                // taken elsewhere, or started here already: an item started here has left the queue, so a copy still in
                // it is stale (e.g. restored by a reload from a queue saved before it started)
                boolean taken = r.state() != ClusterQueue.State.CANCELLED && !me.equals(r.claimedBy());
                Timer.get().submit(() -> dropLocal(id, taken));
                return new Blocked(
                        r.state() == ClusterQueue.State.STARTED && me.equals(r.claimedBy())
                                ? "Started already"
                                : "Starting on another controller");
            }
            String pinnedTo = r.pinnedTo();
            if (pinnedTo != null && !me.equals(pinnedTo) && pinHolds(pinnedTo)) {
                return new Blocked("Waiting for the controller that scheduled it (it waits for the result)");
            }
            if (!me.equals(r.origin())
                    && System.currentTimeMillis() < r.dueAt() + ORIGIN_GRACE_MILLIS
                    && seemsAlive(r.origin())) {
                // the controller it was requested on goes first (e.g. it may hold the agents the build needs)
                return new Blocked("Waiting for the controller it was scheduled on");
            }
            Job<?, ?> job = (Job<?, ?>) item.task;
            if (claiming.add(id)) {
                claims.submit(() -> claim(job, id));
            }
            String why = notClaimed.get(id);
            return new Blocked(why != null ? why : "Being claimed for this controller");
        }

        /**
         * Off the lock: claims an item for this controller (first taking its job's exclusive lease, for a job allowing
         * one build at a time), then has the queue look at it again.
         */
        private static void claim(Job<?, ?> job, long id) {
            ClusterQueue cq = queue();
            String holder = ExclusiveLeases.claimHolder(id);
            boolean changed = true;
            try {
                if (cq == null) {
                    return; // not shared (any more)
                }
                String me = origin();
                ClusterQueue.Record r = cq.get(id);
                // not if a request folded into it since the gate looked pinned it to a live controller elsewhere (the
                // claim
                // checks it did not since)
                String pinnedTo = r == null ? null : r.pinnedTo();
                if (r != null
                        && r.state() == ClusterQueue.State.WAITING
                        && (pinnedTo == null || me.equals(pinnedTo) || !pinHolds(pinnedTo))) {
                    String reservedBy = Reservations.holder(id);
                    if (reservedBy != null && !origin().equals(reservedBy) && !gone(reservedBy)) {
                        notClaimed.put(
                                id, "Waiting for the agent the controller it was scheduled on is starting for it");
                        changed = false; // looked at again at the next maintenance, not at once
                        return;
                    }
                    if (!ExclusiveLeases.acquire(job, holder)) {
                        notClaimed.put(id, "A build is already in progress on another controller");
                        changed = false; // looked at again at the next maintenance, not at once
                        return;
                    }
                    notClaimed.remove(id);
                    if (cq.claim(id, r.pinnedTo())) {
                        LOGGER.fine(() -> "Claimed queue item " + id);
                    } else {
                        ExclusiveLeases.release(job, holder);
                    }
                } else if (r == null || !origin().equals(r.claimedBy())) {
                    ExclusiveLeases.release(
                            job, holder); // held since a claim whose outcome was unknown, which went elsewhere
                }
                ClusterQueue.Record now = cq.get(id);
                if (now != null) {
                    knowClaimed(now);
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.WARNING, "Cannot claim queue item " + id + "; not starting it for now", e);
                if (Boolean.FALSE.equals(claimedHere(cq, id))) {
                    ExclusiveLeases.release(job, holder);
                }
                // not known: the lease is kept, and the claim tried again (it finds it went through, or releases it)
            } finally {
                claiming.remove(id);
                if (leftWhileClaiming.remove(id)) {
                    ExclusiveLeases.release(
                            job, holder); // cancelled meanwhile (Publisher.onLeft): the build will not start
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
                    notClaimed.put(id, "A build is already in progress on another controller");
                    changed = false; // looked at again at the next maintenance, not at once
                    return;
                }
                notClaimed.remove(id);
                readyLocal.add(id);
            } catch (IOException | RuntimeException e) {
                LOGGER.log(
                        Level.FINE, "Cannot ready queue item " + id + " with a local id; not starting it for now", e);
                notClaimed.put(id, "Waiting for the persistence store");
                changed = false;
            } finally {
                claiming.remove(id);
                if (leftWhileClaiming.remove(id)) {
                    readyLocal.remove(id);
                    ExclusiveLeases.release(
                            job, holder); // cancelled meanwhile (Publisher.onLeft): the build will not start
                }
                if (changed) {
                    Queue.getInstance().scheduleMaintenance();
                }
            }
        }
    }

    /** Items cancelled here while being claimed, by id: the claim lets go of what it holds once it ends. */
    private static final Set<Long> leftWhileClaiming = ConcurrentHashMap.newKeySet();

    /** Why items could not be claimed here (yet), by id, for the gate to say. */
    private static final Map<Long, String> notClaimed = new ConcurrentHashMap<>();

    @Extension
    public static final class Starts extends RunListener<Run<?, ?>> {
        @Override
        public void onStarted(Run<?, ?> run, TaskListener listener) {
            ClusterQueue cq = queue();
            long id = run.getQueueId();
            if (cq == null || id <= 0 || !(run.getParent() instanceof Queue.Task task) || !shared(task)) {
                return;
            }
            HandOffs.startedHere(id, run);
            recordStart(cq, run, 0);
        }

        @Override
        public void onFinalized(Run<?, ?> run) {
            long id = run.getQueueId();
            if (id > 0 && queue() != null) {
                HandOffs.completedHere(id, run);
            }
        }

        /**
         * Marks the item started, retrying while the store is unreachable: an item left claimed would be put back into
         * the queue (and built again) if this controller later went away.
         */
        private static void recordStart(ClusterQueue cq, Run<?, ?> run, int attempt) {
            long id = run.getQueueId();
            int number = run.getNumber();
            try {
                ClusterQueue.Record r = cq.get(id);
                if (r != null && !cq.started(id, number)) {
                    ClusterQueue.Record now = cq.get(id);
                    if (now == null || now.state() != ClusterQueue.State.STARTED || now.buildNumber() != number) {
                        claimLost(run, now);
                    } // else this very build, recorded by the controller that started it (adopted here since, say)
                }
                Reservations.release(id);
            } catch (IOException | RuntimeException e) {
                if (attempt < 30) {
                    LOGGER.log(Level.INFO, "Failed to record that queue item " + id + " started; retrying", e);
                    Timer.get()
                            .schedule(
                                    () -> recordStart(cq, run, attempt + 1),
                                    Math.min(30, 1L << Math.min(attempt, 5)),
                                    TimeUnit.SECONDS);
                } else {
                    LOGGER.log(Level.WARNING, "Failed to record that queue item " + id + " started", e);
                }
            }
        }

        /**
         * Stops a build which started here for a queue item this controller no longer had claimed (its claim lapsed while
         * it was paused, say): another controller builds that item, or will.
         */
        private static void claimLost(Run<?, ?> run, @CheckForNull ClusterQueue.Record now) {
            LOGGER.warning(() -> run + " started for queue item " + run.getQueueId()
                    + ", which this controller no longer had claimed ("
                    + (now == null ? "gone" : now.state() + (now.claimedBy() == null ? "" : " by " + now.claimedBy()))
                    + "); stopping it");
            hudson.model.Executor executor = run.getExecutor();
            if (executor != null) {
                executor.interrupt(hudson.model.Result.ABORTED, new ClaimLost());
            }
        }
    }

    /** Cause of interruption of a build whose queue item another controller took meanwhile. */
    public static final class ClaimLost extends jenkins.model.CauseOfInterruption {
        @Override
        public String getShortDescription() {
            return "Another controller took the queue item of this build";
        }
    }

    /**
     * Ids unique across the cluster, so that an item has the same id on every controller.
     */
    @Extension(ordinal = 100)
    public static final class Ids extends QueueIdStrategy {
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
            ClusterQueue cq = queue();
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
                claims.submit(() -> {
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
            (queue() != null ? LOCAL : withoutSharedQueue()).persist(queueState);
        }

        @Override
        public void load(@NonNull Queue.State queueState) {
            (queue() != null ? LOCAL : withoutSharedQueue()).load(queueState);
        }
    }

    // ------------------------------------------------------------------ cluster changes → local queue

    /**
     * Applies changes to the cluster queue to this controller's queue, one at a time.
     */
    public static final class Mirror {
        private static Mirror instance;

        private final AutoCloseable subscription;
        private final BlockingQueue<ClusterQueue.Record> changes = new LinkedBlockingQueue<>();
        private final Thread worker;

        private Mirror(ClusterQueue cq) {
            subscription = cq.subscribe(changes::add);
            worker = new Thread(this::run, "Shared queue mirror");
            worker.setDaemon(true);
        }

        /** Once the queue is loaded, which clears it first (and would drop items mirrored before). */
        @Initializer(after = InitMilestone.JOB_CONFIG_ADAPTED, requires = Queue.LOADED)
        public static synchronized void start() throws IOException {
            stop();
            // what was known of another store's queue (a Jenkins restarted in the same JVM) says nothing of this one's
            known.clear();
            sharing.clear();
            cancelledWhileSharing.clear();
            reconciling.clear();
            claiming.clear();
            readyLocal.clear();
            notClaimed.clear();
            leftWhileClaiming.clear();
            droppedHere.clear();
            HandOffs.clear();
            ClusterQueue cq = queue();
            if (cq == null) {
                return;
            }
            instance = new Mirror(cq);
            instance.changes.addAll(cq.pending());
            instance.worker.start();
            Ids.fill(cq); // so that the first items do not wait for the store with the queue lock held
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
            String me = origin();
            if (localItem(r.id()) != null) {
                know(r); // for the gate, which decides from what is known
            }
            switch (r.state()) {
                case WAITING -> {
                    if (localItem(r.id()) != null) {
                        // e.g. another controller folded a request into it: take over its causes and quiet period
                        // (unless claimed here since: this change came late, and its actions are older)
                        Calendar due = new GregorianCalendar();
                        due.setTimeInMillis(r.dueAt());
                        List<Action> actions = fromXml(r.actions());
                        Queue.withLock(() -> {
                            if (!knownClaimedHere(r.id())) {
                                Queue.getInstance().updateShared(r.id(), due, actions);
                            }
                        });
                    } else if (!me.equals(r.origin()) || droppedHere.contains(r.id())) {
                        // our own items are recreated only if dropped: else absent here means they already left
                        create(r);
                    }
                }
                case CLAIMED -> {
                    if (!me.equals(r.claimedBy())) {
                        dropLocal(r.id(), true);
                    }
                }
                case STARTED -> {
                    if (!me.equals(r.claimedBy())) {
                        dropLocal(r.id(), true);
                        HandOffs.startedElsewhere(r);
                    }
                }
                case CANCELLED -> dropLocal(r.id(), false);
                default -> throw new IllegalStateException(r.toString());
            }
        }

        /** Mirrors a waiting item, if it is not here yet. */
        static void mirror(long id) {
            try {
                ClusterQueue cq = queue();
                ClusterQueue.Record r = cq == null ? null : cq.get(id);
                if (r != null && r.state() == ClusterQueue.State.WAITING && localItem(id) == null) {
                    create(r);
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.WARNING, "Failed to mirror queue item " + id, e);
            }
        }

        private static void create(ClusterQueue.Record event) {
            ClusterQueue.Record r;
            try {
                ClusterQueue cq = queue();
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
                    || here != null && !(here instanceof Queue.LeftItem && droppedHere.contains(r.id()))) {
                return; // e.g. it left here (an item put back after being dropped here is mirrored again)
            }
            Job<?, ?> job = jobOf(r);
            if (job == null) {
                // its job may not be here yet: item changes are applied apart from queue changes
                if (awaitingJob.size() > 10_000) {
                    awaitingJob.clear(); // jobs which never came
                }
                awaitingJob
                        .computeIfAbsent(r.itemUuid(), k -> ConcurrentHashMap.newKeySet())
                        .add(r.id());
                job = jobOf(r); // created meanwhile?
                if (job == null) {
                    LOGGER.fine(() -> "Not mirroring " + r + " until its job is here");
                    return;
                }
                awaitingJob.computeIfPresent(
                        r.itemUuid(), (k, ids) -> ids.remove(r.id()) && ids.isEmpty() ? null : ids);
            }
            if (!(job instanceof Queue.Task task)) {
                return;
            }
            int quietPeriod = (int) Math.max(0, (r.dueAt() - System.currentTimeMillis() + 999) / 1000);
            know(r);
            Ids.MIRRORED.set(r.id());
            try {
                // never folded here: records the shared queue kept apart stay apart
                PersistenceStores.applyingRemoteChange(
                        () -> Queue.getInstance().scheduleShared(task, quietPeriod, fromXml(r.actions())));
            } finally {
                Ids.MIRRORED.remove();
            }
            droppedHere.remove(r.id());
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
                                ids.forEach(Mirror::mirror);
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
                            ids.forEach(Mirror::mirror);
                        }
                    });
                }
            }
        }
    }

    // ------------------------------------------------------------------ futures of items taken elsewhere

    /**
     * The hand-offs of items another controller took ({@link Queue#handOff}), completed as the build elsewhere starts and
     * completes (or the item is cancelled there), so that whoever waits on the item's future here (the CLI's
     * {@code build -s}, a {@code build} step) follows that build. An item put back and built here after all completes
     * them with its build here.
     */
    static final class HandOffs {

        /**
         * What follows one item: every hand-off of it here (an item re-created here after its claimant died is handed
         * off again when claimed elsewhere, with another left item), all told of its build.
         */
        private record Pending(
                List<Queue.HandOff> handOffs,
                long since,
                @CheckForNull String uuid,
                int number) {

            Pending and(Queue.HandOff handOff) {
                if (handOffs.contains(handOff)) {
                    return this;
                }
                List<Queue.HandOff> all = new ArrayList<>(handOffs);
                all.add(handOff);
                return new Pending(List.copyOf(all), since, uuid, number);
            }

            Pending startedAs(@CheckForNull String uuid, int number) {
                return new Pending(handOffs, since, uuid, number);
            }

            void started(Queue.Executable view) {
                handOffs.forEach(h -> h.started(view));
            }

            void completed(Queue.Executable run) {
                handOffs.forEach(h -> h.completed(run));
            }

            void cancelled() {
                handOffs.forEach(Queue.HandOff::cancelled);
            }
        }

        /** By queue item id. */
        private static final Map<Long, Pending> pending = new ConcurrentHashMap<>();

        /** How long a hand-off is followed at most: the build elsewhere may never be heard of (its controller died). */
        static final long MAX_MILLIS = TimeUnit.DAYS.toMillis(1);

        private HandOffs() {}

        static void add(long id, @NonNull Queue.HandOff handOff) {
            follow(id, handOff);
        }

        /** A local item was folded into another one ({@code into}): its future follows that item. */
        static void folded(long id, long into, @NonNull Queue.HandOff handOff) {
            follow(into, handOff);
        }

        private static void follow(long id, Queue.HandOff handOff) {
            pending.merge(
                    id,
                    new Pending(List.of(handOff), System.currentTimeMillis(), null, 0),
                    (followed, ignored) -> followed.and(handOff));
            prune();
        }

        static void cancelled(long id) {
            Pending p = pending.remove(id);
            if (p != null) {
                p.cancelled();
            }
        }

        /** The item started on another controller: its future's start condition gets that build (or its view). */
        static void startedElsewhere(@NonNull ClusterQueue.Record r) {
            Pending p = pending.get(r.id());
            if (p == null || r.buildNumber() <= 0 || p.number() > 0) {
                return;
            }
            Job<?, ?> job = jobOf(r);
            if (job == null) {
                return;
            }
            pending.put(r.id(), p.startedAs(r.itemUuid(), r.buildNumber()));
            Run<?, ?> run = job.getBuildByNumber(r.buildNumber());
            if (run != null && !run.isLogUpdated()) {
                // completed already, and its record is here
                pending.remove(r.id());
                p.completed((Queue.Executable) run);
                return;
            }
            p.started(run != null ? (Queue.Executable) run : BuildElsewhere.view(job, r.buildNumber(), r.claimedBy()));
        }

        /** The item started here after all (put back, then claimed here): its future follows this build. */
        static void startedHere(long id, @NonNull Run<?, ?> run) {
            Pending p = pending.get(id);
            if (p != null && p.number() == 0) {
                pending.put(id, p.startedAs(run.getParent().getItemUuid(), run.getNumber()));
                p.started((Queue.Executable) run);
            }
        }

        static void completedHere(long id, @NonNull Run<?, ?> run) {
            Pending p = pending.get(id);
            if (p != null
                    && run.getNumber() == p.number()
                    && Objects.equals(run.getParent().getItemUuid(), p.uuid())) {
                pending.remove(id);
                p.completed((Queue.Executable) run);
            }
        }

        /**
         * The record of a build completed elsewhere was published here: the future of an item it was the build of
         * completes with it.
         */
        static void recorded(@NonNull Job<?, ?> job, int number) {
            if (pending.isEmpty()) {
                return;
            }
            String uuid = job.getItemUuid();
            for (Map.Entry<Long, Pending> e : pending.entrySet()) {
                Pending p = e.getValue();
                if (p.number() == number && Objects.equals(uuid, p.uuid())) {
                    Run<?, ?> run = job.getBuildByNumber(number);
                    if (run != null && !run.isLogUpdated() && pending.remove(e.getKey(), p)) {
                        p.completed((Queue.Executable) run);
                    }
                }
            }
        }

        private static void prune() {
            long now = System.currentTimeMillis();
            pending.values().removeIf(p -> now - p.since() > MAX_MILLIS);
        }

        static void clear() {
            pending.clear();
        }

        /** How many are followed, for tests. */
        static int size() {
            return pending.size();
        }
    }

    /** Builds completed elsewhere complete the futures of the items they were the builds of. */
    @Extension
    public static final class Records implements io.jenkins.plugins.cluster.builds.BuildRecords.RecordListener {
        @Override
        public void recordedElsewhere(@NonNull Job<?, ?> job, int number) {
            HandOffs.recorded(job, number);
        }
    }
}
