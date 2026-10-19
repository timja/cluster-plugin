package io.jenkins.plugins.cluster.queue;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.model.Action;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.FileParameterValue;
import hudson.model.Job;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.Queue;
import hudson.model.queue.CauseOfBlockage;
import hudson.model.queue.FoldableAction;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.util.DaemonThreadFactory;
import hudson.util.NamingThreadFactory;
import hudson.util.Secret;
import io.jenkins.plugins.cluster.builds.ExclusiveLeases;
import io.jenkins.plugins.cluster.builds.RunningBuilds;
import io.jenkins.plugins.cluster.spi.ClusterQueue;
import io.jenkins.plugins.cluster.spi.ClusterStore;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.ItemUuids;
import jenkins.model.Jenkins;
import jenkins.model.queue.QueueStartGate;
import jenkins.persistence.PersistenceStores;
import jenkins.security.HMACConfidentialKey;
import jenkins.util.SystemProperties;
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

    static String origin() {
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
    static String pinnedTo(@NonNull Queue.Item item, @NonNull List<Action> actions) {
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
    static boolean pinHolds(@NonNull String pinnedTo) {
        return seemsAlive(pinnedTo); // as last read, off the queue lock
    }

    static String toXml(@NonNull List<? extends Action> actions) {
        return Queue.XSTREAM.toXML(new ArrayList<>(actions));
    }

    @SuppressWarnings("unchecked")
    static List<Action> fromXml(@NonNull String xml) {
        return (List<Action>) Queue.XSTREAM.fromXML(xml);
    }

    static long dueOf(Queue.Item item) {
        return item instanceof Queue.WaitingItem wi ? wi.timestamp.getTimeInMillis() : System.currentTimeMillis();
    }

    /**
     * Merges a request folded into an item: its causes join the item's, and its other {@link FoldableAction}s (such as a
     * {@code build} step's, which the local queue folded into its copy) are added unless an equal one is there.
     */
    static List<Action> merge(List<Action> into, List<? extends Action> from) {
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
    static Queue.Item localItem(long id) {
        Queue.Item item = Queue.getInstance().getItem(id);
        return item instanceof Queue.LeftItem ? null : item;
    }

    /** The job with this identity here (whoever asks), or null. */
    @CheckForNull
    static Job<?, ?> jobOf(@NonNull ClusterQueue.Record r) {
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            return ItemUuids.get(r.itemUuid()) instanceof Job<?, ?> job ? job : null;
        }
    }

    // ------------------------------------------------------------------ what is decided under the queue lock

    /*
     * The queue lock is held while items are scheduled and while the queue is maintained (QueueStartGate), so nothing
     * here talks to the store with it held: a slow store would stop the whole queue. Publishing goes through an outbox,
     * the gate decides from the records this controller knows (fed by the mirror), and claims are made off the lock.
     * One exception: a new item's id, when the pool of ids taken ahead ran dry (see SharedQueueIds.nextId).
     */

    /** The records of items in this controller's queue, as last read or delivered. */
    static final Map<Long, ClusterQueue.Record> known = new ConcurrentHashMap<>();
    /** Local items being shared (published, or folded into an item from elsewhere): they do not start meanwhile. */
    static final Set<Long> sharing = ConcurrentHashMap.newKeySet();
    /** Items cancelled here while being shared, whose cancellation is shared once they are. */
    static final Set<Long> cancelledWhileSharing = ConcurrentHashMap.newKeySet();
    /** Items whose record is being looked up, nothing being known of it here. */
    static final Set<Long> reconciling = ConcurrentHashMap.newKeySet();
    /** Items with a claim under way. */
    static final Set<Long> claiming = ConcurrentHashMap.newKeySet();
    /** Items with a local id which may start here: the store answered, and their job's exclusive lease is held. */
    static final Set<Long> readyLocal = ConcurrentHashMap.newKeySet();

    /**
     * A few threads: items are shared independently, so one waiting on a slow write does not hold up the others; the
     * changes of one item go through one thread, in order (its publication before the folds into it).
     */
    static final ExecutorService[] outbox = new ExecutorService[4];

    static {
        for (int i = 0; i < outbox.length; i++) {
            outbox[i] = Executors.newSingleThreadExecutor(
                    new NamingThreadFactory(new DaemonThreadFactory(), "Shared queue outbox " + i));
        }
    }

    static ExecutorService outbox(long id) {
        return outbox[(int) Math.floorMod(id, (long) outbox.length)];
    }

    static final ExecutorService claims =
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
    static void knowClaimed(@NonNull ClusterQueue.Record now) {
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
    static boolean knownClaimedHere(long id) {
        ClusterQueue.Record k = known.get(id);
        return k != null && k.state() == ClusterQueue.State.CLAIMED && origin().equals(k.claimedBy());
    }

    /** Live origins as last read, refreshed off the lock; null if unknown (taken as everyone alive). */
    private static volatile Set<String> live;

    private static volatile long liveAt;
    private static final AtomicBoolean liveRefreshing = new AtomicBoolean();

    /** Whether an origin was alive when last asked; asks again, off the lock, when that is a few seconds old. */
    static boolean seemsAlive(String origin) {
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
    static void dropLocal(long id, boolean taken) {
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
     * before starting it, the store puts it back, under the same id, and it is mirrored again (QueueMirror.create).
     */
    static final Set<Long> droppedHere = Collections.newSetFromMap(Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
            return size() > 10_000;
        }
    }));

    // ------------------------------------------------------------------ local changes → cluster

    /** How long after an item left the queue here to start its build must have started. */
    static /* non-final for tests */ long LEFT_WITHOUT_BUILD_SECONDS = 60;

    /**
     * An item which left the queue here to start, but whose task made no build (a job disabled meanwhile, say, or one
     * failing to make its build): its shared record is cancelled, or it would stay claimed by this controller, built
     * nowhere; and what its claim held of its job's exclusive lease is released.
     */
    static void leftWithoutBuild(Queue.LeftItem li) {
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
    static Boolean claimedHere(ClusterQueue cq, long id) {
        try {
            ClusterQueue.Record r = cq.get(id);
            return r != null && origin().equals(r.claimedBy());
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Whether a controller which reserved an item is known to be gone (its reservation then holds nothing up). */
    static boolean gone(String origin) throws IOException {
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
        String holder = QueueReservations.holder(id);
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

    /** Items cancelled here while being claimed, by id: the claim lets go of what it holds once it ends. */
    static final Set<Long> leftWhileClaiming = ConcurrentHashMap.newKeySet();

    /** Why items could not be claimed here (yet), by id, for the gate to say. */
    static final Map<Long, String> notClaimed = new ConcurrentHashMap<>();

    /** Cause of interruption of a build whose queue item another controller took meanwhile. */
    public static final class ClaimLost extends jenkins.model.CauseOfInterruption {
        @Override
        public String getShortDescription() {
            return "Another controller took the queue item of this build";
        }
    }

    // ------------------------------------------------------------------ cluster changes → local queue

    // ------------------------------------------------------------------ futures of items taken elsewhere

}
