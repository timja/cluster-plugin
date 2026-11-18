package io.jenkins.plugins.cluster.builds;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Job;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import io.jenkins.plugins.cluster.spi.BuildLeases;
import io.jenkins.plugins.cluster.spi.ClusterStore;
import io.jenkins.plugins.cluster.spi.Keys;
import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.util.Timer;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * One build at a time, cluster-wide, of a job which does not allow concurrent builds: a lease per job
 * ({@code _items/<uuid>/running/exclusive}), held by the controller claiming one of its queue items or running (or
 * adopting) one of its builds.
 *
 * <p>The store's lease is this controller's, whichever build it is for: it is released once nothing here holds it, so
 * that a build finalizing does not release it under the next build of the job (claimed as soon as the first one
 * completed), nor a build adopted here run beside another. What holds it here: {@code q:<id>} for a queue item claimed
 * here, {@code r:<number>} for a build running here.
 */
@Restricted(NoExternalUse.class)
public final class ExclusiveLeases {

    private static final Logger LOGGER = Logger.getLogger(ExclusiveLeases.class.getName());

    private ExclusiveLeases() {}

    /**
     * The store's lease held here, what holds it here, and the store's {@linkplain ClusterStore#lapseCount lapse count}
     * when it was last known held: once this controller may have looked gone to the others, another may have taken the
     * lease over, so the hold is checked against the store before it is trusted again.
     */
    private record Hold(long epoch, Set<String> holders, long lapses) {}

    /** Exclusive leases held here: lease key → its hold; changed under {@link #lockOf}. */
    private static final Map<String, Hold> exclusive = new ConcurrentHashMap<>();

    private static final Map<String, Object> locks = new ConcurrentHashMap<>();
    /**
     * Build holders of exclusive leases whose build is being adopted here and not resumed yet, by lease key; changed
     * under {@link #lockOf}. Until it is resumed nothing here shows the build running, so a claim must not join its hold.
     */
    private static final Map<String, Set<String>> adopting = new ConcurrentHashMap<>();

    /** One lock per lease key: taking and releasing the lease (in the store) is done under it, never under the queue lock. */
    private static Object lockOf(String key) {
        return locks.computeIfAbsent(key, k -> new Object());
    }

    /** What a queue item claimed here holds. */
    @NonNull
    public static String claimHolder(long id) {
        return "q:" + id;
    }

    static String buildHolder(int number) {
        return "r:" + number;
    }

    @CheckForNull
    private static String key(Job<?, ?> job) {
        String prefix = BuildRecords.prefix(job);
        return prefix == null ? null : Keys.running(prefix) + "/exclusive";
    }

    /**
     * Takes the exclusive lease on a job which does not allow concurrent builds, so that only one controller runs it at
     * a time.
     *
     * @param holder what it is held for here ({@link #claimHolder})
     * @return true if this controller holds it (or the job allows concurrent builds, or builds are not shared)
     */
    public static boolean acquire(@NonNull Job<?, ?> job, @NonNull String holder) throws IOException {
        String key = key(job);
        ClusterStore store = ClusterStore.get();
        if (key == null || store == null || !(job instanceof Queue.Task task) || task.isConcurrentBuild()) {
            return true;
        }
        BuildLeases leases = store.leases();
        synchronized (lockOf(key)) {
            Hold hold = exclusive.get(key);
            if (hold != null && holder.startsWith("q:") && adopting.containsKey(key)) {
                return false; // a build of the job is being adopted here: the queue here would not see it running yet
            }
            long lapses = store.lapseCount();
            if (hold != null && hold.lapses() != lapses) {
                // this controller may have looked gone meanwhile: the lease may be another controller's now
                if (leases.holds(key, hold.epoch())) {
                    hold = new Hold(hold.epoch(), hold.holders(), lapses);
                    exclusive.put(key, hold);
                } else {
                    Set<String> lost = hold.holders();
                    LOGGER.info(() -> "Lost the exclusive lease " + key + " held here for " + lost);
                    exclusive.remove(key);
                    adopting.remove(key);
                    hold = null;
                }
            }
            if (hold == null) {
                long epoch = leases.acquire(key);
                if (epoch == 0) {
                    return false;
                }
                hold = new Hold(epoch, new HashSet<>(), lapses);
                exclusive.put(key, hold);
            }
            hold.holders().add(holder);
            return true;
        }
    }

    /** Lets go of what a holder held here of a job's exclusive lease, releasing the lease once nothing here holds it. */
    public static void release(@NonNull Job<?, ?> job, @NonNull String holder) {
        String key = key(job);
        if (key == null || !exclusive.containsKey(key)) {
            return;
        }
        synchronized (lockOf(key)) {
            notAdopting(key, holder);
            Hold hold = exclusive.get(key);
            if (hold == null
                    || !hold.holders().remove(holder)
                    || !hold.holders().isEmpty()) {
                return;
            }
            exclusive.remove(key);
            release(key, hold.epoch(), 0);
        }
    }

    /**
     * Releases an exclusive lease nothing here holds any more, under {@link #lockOf}; retried if the store fails, as the
     * lease of a live controller never lapses and would block the job everywhere. A retry releases nothing if the job is
     * held here again meanwhile (by the same lease, released with that hold).
     */
    private static void release(String key, long epoch, int attempt) {
        ClusterStore store = ClusterStore.get();
        if (store == null || exclusive.containsKey(key)) {
            return;
        }
        try {
            store.leases().release(key, epoch);
        } catch (IOException | RuntimeException e) {
            if (attempt < RELEASE_ATTEMPTS) {
                LOGGER.log(Level.INFO, "Failed to release " + key + "; retrying", e);
                Timer.get()
                        .schedule(
                                () -> {
                                    synchronized (lockOf(key)) {
                                        release(key, epoch, attempt + 1);
                                    }
                                },
                                Math.min(60, 1L << attempt),
                                TimeUnit.SECONDS);
            } else {
                // never given up on: retried with the periodic cleanup (releasePending) for as long as it fails
                if (unreleased.put(key, epoch) == null) {
                    LOGGER.log(Level.WARNING, "Failed to release " + key + "; retrying periodically", e);
                }
            }
        }
    }

    /** How many times a failed release is retried at once (backing off) before it is left to {@link #releasePending}. */
    static /* non-final for tests */ int RELEASE_ATTEMPTS = 10;

    /** Exclusive leases whose release kept failing, by key: their epochs, retried by {@link #releasePending}. */
    private static final Map<String, Long> unreleased = new ConcurrentHashMap<>();

    /** Retries releasing the exclusive leases whose release kept failing (the store was unreachable for long). */
    static void releasePending() {
        for (Map.Entry<String, Long> e : Map.copyOf(unreleased).entrySet()) {
            String key = e.getKey();
            synchronized (lockOf(key)) {
                if (unreleased.remove(key, e.getValue())) {
                    release(
                            key,
                            e.getValue(),
                            RELEASE_ATTEMPTS); // put back if it fails again; nothing if held here again since
                }
            }
        }
    }

    static int unreleasedCount() {
        return unreleased.size();
    }

    private static void notAdopting(String key, String holder) {
        adopting.computeIfPresent(key, (k, holders) -> holders.remove(holder) && holders.isEmpty() ? null : holders);
    }

    /**
     * Takes over the exclusive lease of a job before adopting one of its builds (the previous holder is gone).
     *
     * @return false if another controller holds it (it started a newer build meanwhile), or another build of the job is
     *     claimed or runs here: the build must not resume now
     */
    static boolean adopt(@NonNull Job<?, ?> job, int number) throws IOException {
        String key = key(job);
        if (key == null) {
            return true;
        }
        synchronized (lockOf(key)) {
            Hold hold = exclusive.get(key);
            if (hold != null && hold.holders().stream().anyMatch(h -> !h.equals(buildHolder(number)))) {
                return false; // the lease is this controller's, but for another build of the job
            }
            if (!acquire(job, buildHolder(number))) {
                return false;
            }
            if (exclusive.containsKey(key)) {
                adopting.computeIfAbsent(key, k -> new HashSet<>()).add(buildHolder(number)); // until resumed
            }
            return true;
        }
    }

    /**
     * Tells that a build adopted with {@link #adopt} was resumed (it is loaded, so the queue here sees it running):
     * items of its job claimed here may share its hold from now on (to be blocked by it locally).
     */
    static void resumed(@NonNull Job<?, ?> job, int number) {
        String key = key(job);
        if (key != null && adopting.containsKey(key)) {
            synchronized (lockOf(key)) {
                notAdopting(key, buildHolder(number));
            }
        }
    }

    /**
     * Gives up the exclusive lease taken by {@link #adopt} for an adoption that did not happen after all (if it was
     * taken), so that the job is not blocked everywhere by this controller, which does not run that build.
     */
    static void abandon(@NonNull Job<?, ?> job, int number) {
        release(job, buildHolder(number));
    }

    /** A build of a job holding the lease here: held by the build from its start, not by its queue item, until finalized. */
    @Extension
    public static final class Builds extends RunListener<Run<?, ?>> {
        @Override
        public void onStarted(Run<?, ?> run, TaskListener listener) {
            String key = key(run.getParent());
            long id = run.getQueueId();
            if (key != null && id > 0 && exclusive.containsKey(key)) {
                synchronized (lockOf(key)) {
                    Hold hold = exclusive.get(key);
                    if (hold != null && hold.holders().remove(claimHolder(id))) {
                        hold.holders()
                                .add(buildHolder(run.getNumber())); // held by the build now, until it is finalized
                    }
                }
            }
        }

        @Override
        public void onFinalized(Run<?, ?> run) {
            // whether claimed here (with a shared queue) or adopted
            release(run.getParent(), buildHolder(run.getNumber()));
        }
    }
}
