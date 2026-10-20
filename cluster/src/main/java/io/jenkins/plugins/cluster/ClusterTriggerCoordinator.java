package io.jenkins.plugins.cluster;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.init.InitMilestone;
import hudson.model.PeriodicWork;
import hudson.triggers.Trigger;
import io.jenkins.plugins.cluster.spi.ClusterStore;
import io.jenkins.plugins.cluster.spi.Membership;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.persistence.PersistenceStores;
import jenkins.triggers.TriggerCoordinator;
import jenkins.triggers.TriggeredItem;

/**
 * Runs each tick of each cron-style trigger on exactly one controller.
 *
 * <ul>
 * <li><b>Affinity:</b> the owner of an item is chosen by rendezvous hashing of its top-level item name over the
 *     ready members (alive, and done starting: items loaded, {@link MemberStatus}), so a folder or multibranch project
 *     and everything in it stay on one controller, and polling and indexing keep comparing against that controller's
 *     state. When a member joins or leaves, only the items it gains or loses move. A controller still starting owns
 *     nothing, so its share stays with the others until it can run it.
 * <li><b>Claim:</b> the owner then claims the (item, trigger, minute) in the database. While controllers briefly
 *     disagree about membership, two may each think they own an item; only one claim succeeds.
 * <li><b>Catch-up:</b> every controller remembers, for {@link #CATCH_UP_MINUTES}, the ticks it left to another
 *     controller ({@link MissedTicks}). When that controller is found gone (it died before the others noticed) or not
 *     ready, the item's owner now claims the ticks it missed and, if it got any of them, runs the trigger once for
 *     them all, unless a later tick of the trigger was handled meanwhile.
 * </ul>
 * A tick is never run twice. It may still be missed: while controllers disagree about the owner of an item, by a
 * controller that is alive but does not run it (e.g. it has not loaded the item yet), beyond the catch-up window, or
 * while the store is unreachable.
 */
@Extension
public class ClusterTriggerCoordinator implements TriggerCoordinator {

    private static final Logger LOGGER = Logger.getLogger(ClusterTriggerCoordinator.class.getName());
    private static final long MEMBERS_CACHE_MILLIS = 2_000;

    /**
     * How long a tick left to another controller is remembered, to be run here if that controller turns out to be gone
     * ({@code -Dio.jenkins.plugins.cluster.ClusterTriggerCoordinator.catchUpMinutes}; 0 turns catching up off).
     */
    static final long CATCH_UP_MINUTES =
            Long.getLong(ClusterTriggerCoordinator.class.getName() + ".catchUpMinutes", 15);

    /** Most ticks remembered at once; beyond that, ticks left to others are not caught up. */
    static final int MAX_REMEMBERED =
            Integer.getInteger(ClusterTriggerCoordinator.class.getName() + ".maxRememberedTicks", 200_000);

    private Set<String> members;
    private long membersFetched;

    final MissedTicks missed = new MissedTicks(CATCH_UP_MINUTES, MAX_REMEMBERED);

    /** Whether this controller is done starting; replaced by tests. */
    java.util.function.BooleanSupplier readyHere = ClusterTriggerCoordinator::readyHere;

    @Override
    public boolean shouldRun(@NonNull TriggeredItem item, @NonNull Trigger<?> trigger, @NonNull Calendar tick) {
        if (!(PersistenceStores.get() instanceof ClusterStore store)) {
            return true;
        }
        return shouldRun(
                store, item.getFullName(), trigger.getDescriptor().getId(), tick.getTimeInMillis() / 60_000, trigger);
    }

    boolean shouldRun(ClusterStore store, String fullName, String id, long minute, Object trigger) {
        try {
            String owner = ownerOf(topLevel(fullName), members(store));
            if (!store.getOrigin().equals(owner)) {
                LOGGER.fine(() -> fullName + " is owned by " + owner);
                missed.leftTo(fullName, id, minute, owner);
                return false;
            }
            boolean claimed = store.claim(fullName, id, minute);
            missed.handledHere(fullName, id, minute);
            LOGGER.fine(() -> (claimed ? "Claimed " : "Already claimed: ") + id + " of " + fullName);
            return claimed;
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Cannot coordinate " + trigger + " of " + fullName + "; skipping this tick", e);
            return false;
        }
    }

    private synchronized Set<String> members(ClusterStore store) throws IOException {
        long now = System.currentTimeMillis();
        if (members == null || now - membersFetched > MEMBERS_CACHE_MILLIS) {
            members = readyMembers(store.members(), store.getOrigin(), readyHere.getAsBoolean(), store::aliveMembers);
            membersFetched = now;
        }
        return members;
    }

    /** Forgets the cached members (tests). */
    synchronized void forgetMembers() {
        members = null;
    }

    /** Whether this controller is done starting (and so may own triggers). */
    static boolean readyHere() {
        Jenkins j = Jenkins.getInstanceOrNull();
        return j != null && j.getInitLevel() == InitMilestone.COMPLETED;
    }

    /**
     * The members which may own triggers: the live ones done starting ({@link MemberStatus#isReady}), this controller
     * as it knows itself (its last heartbeat may say otherwise). If none is ready, as when every controller is starting,
     * every live one.
     */
    @NonNull
    static Set<String> readyMembers(
            @NonNull List<Membership.Member> all,
            @NonNull String self,
            boolean selfReady,
            @NonNull IOSupplier<Set<String>> alive)
            throws IOException {
        Set<String> ready = new TreeSet<>();
        for (Membership.Member m : all) {
            if (m.alive() && MemberStatus.isReady(m.info())) {
                ready.add(m.origin());
            }
        }
        if (selfReady) {
            ready.add(self);
        } else {
            ready.remove(self);
        }
        return ready.isEmpty() ? alive.get() : ready;
    }

    @FunctionalInterface
    interface IOSupplier<T> {
        T get() throws IOException;
    }

    /**
     * Runs the triggers whose ticks a gone owner missed ({@link MissedTicks}): claims each missed tick and, if any claim
     * succeeded, runs the trigger once.
     */
    void catchUp(@NonNull ClusterStore store, long nowMinute) throws IOException {
        catchUp(store, nowMinute, ClusterTriggerCoordinator::triggerOf);
    }

    void catchUp(
            @NonNull ClusterStore store,
            long nowMinute,
            @NonNull java.util.function.BiFunction<String, String, Trigger<?>> triggers)
            throws IOException {
        Set<String> ready = members(store);
        String self = store.getOrigin();
        for (MissedTicks.Due due : missed.due(nowMinute, ready, self, name -> ownerOf(topLevel(name), ready))) {
            Trigger<?> trigger = triggers.apply(due.item(), due.trigger());
            if (trigger == null) {
                continue; // deleted, renamed or reconfigured meanwhile
            }
            List<Long> claimed = new ArrayList<>();
            for (long tick : due.ticks()) {
                if (store.claim(due.item(), due.trigger(), tick)) {
                    claimed.add(tick);
                }
            }
            if (claimed.isEmpty()) {
                LOGGER.fine(() -> "Missed ticks of " + due.trigger() + " of " + due.item() + " were run after all");
                continue;
            }
            missed.handledHere(due.item(), due.trigger(), claimed.get(claimed.size() - 1));
            LOGGER.info(() -> "Running " + due.trigger() + " of " + due.item() + " for " + claimed.size()
                    + " tick(s) its owner missed (gone before it ran them), the last at "
                    + new java.util.Date(claimed.get(claimed.size() - 1) * 60_000));
            try {
                trigger.run();
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, trigger.getClass().getName() + ".run() failed for " + due.item(), e);
            }
        }
    }

    @CheckForNull
    private static Trigger<?> triggerOf(String fullName, String descriptorId) {
        if (!(Jenkins.get().getItemByFullName(fullName) instanceof TriggeredItem item)) {
            return null;
        }
        for (Trigger<?> t : item.getTriggers().values()) {
            if (t != null && t.getDescriptor().getId().equals(descriptorId)) {
                return t;
            }
        }
        return null;
    }

    /** Catches up ticks missed by gone owners, every minute, on every controller (for the items it owns). */
    @Extension
    public static final class CatchUp extends PeriodicWork {
        @Override
        public long getRecurrencePeriod() {
            return MIN;
        }

        @Override
        public long getInitialDelay() {
            return MIN / 2; // mid-minute, away from the cron ticks
        }

        @Override
        protected void doRun() throws Exception {
            if (CATCH_UP_MINUTES <= 0 || !readyHere() || !(PersistenceStores.get() instanceof ClusterStore store)) {
                return;
            }
            ClusterTriggerCoordinator c = ExtensionList.lookupSingleton(ClusterTriggerCoordinator.class);
            try {
                c.catchUp(store, System.currentTimeMillis() / 60_000);
            } catch (IOException e) {
                LOGGER.log(Level.FINE, "Could not catch up missed trigger ticks; trying again in a minute", e);
            }
        }
    }

    /**
     * The controller (origin) owning items under a top-level name, among the given members.
     */
    @NonNull
    public static String ownerOf(@NonNull String topLevelName, @NonNull Collection<String> members) {
        String best = null;
        long bestScore = Long.MIN_VALUE;
        for (String m : members) {
            long score = hash(m + '\n' + topLevelName);
            if (best == null || score > bestScore || score == bestScore && m.compareTo(best) < 0) {
                best = m;
                bestScore = score;
            }
        }
        if (best == null) {
            throw new IllegalArgumentException("no members");
        }
        return best;
    }

    /**
     * For diagnostics (script console): the origin that owns an item now, according to this controller.
     */
    public static String currentOwnerOf(String fullName) throws IOException {
        if (!(PersistenceStores.get() instanceof ClusterStore store)) {
            return null;
        }
        return ownerOf(
                topLevel(fullName), readyMembers(store.members(), store.getOrigin(), readyHere(), store::aliveMembers));
    }

    static String topLevel(String fullName) {
        int slash = fullName.indexOf('/');
        return slash == -1 ? fullName : fullName.substring(0, slash);
    }

    private static long hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            long h = 0;
            for (int i = 0; i < 8; i++) {
                h = (h << 8) | (d[i] & 0xff);
            }
            return h;
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
