package io.jenkins.plugins.cluster;

import io.jenkins.plugins.cluster.spi.ClusterStore;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.triggers.Trigger;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Calendar;
import java.util.Collection;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.persistence.PersistenceStores;
import jenkins.triggers.TriggerCoordinator;
import jenkins.triggers.TriggeredItem;

/**
 * Runs each tick of each cron-style trigger on exactly one controller.
 *
 * <ul>
 * <li><b>Affinity:</b> the owner of an item is chosen by rendezvous hashing of its top-level item name over the
 *     live members, so a folder or multibranch project and everything in it stay on one controller, and polling
 *     and indexing keep comparing against that controller's state. When a member joins or leaves, only the items
 *     it gains or loses move.
 * <li><b>Claim:</b> the owner then claims the (item, trigger, minute) in the database. While controllers briefly
 *     disagree about membership, two may each think they own an item; only one claim succeeds.
 * </ul>
 * A tick may be missed while membership changes (nobody thinks it owns the item), never run twice.
 */
@Extension
public class ClusterTriggerCoordinator implements TriggerCoordinator {

    private static final Logger LOGGER = Logger.getLogger(ClusterTriggerCoordinator.class.getName());
    private static final long MEMBERS_CACHE_MILLIS = 2_000;

    private Set<String> members;
    private long membersFetched;

    @Override
    public boolean shouldRun(@NonNull TriggeredItem item, @NonNull Trigger<?> trigger, @NonNull Calendar tick) {
        if (!(PersistenceStores.get() instanceof ClusterStore store)) {
            return true;
        }
        String fullName = item.getFullName();
        try {
            String owner = ownerOf(topLevel(fullName), members(store));
            if (!store.getOrigin().equals(owner)) {
                LOGGER.fine(() -> fullName + " is owned by " + owner);
                return false;
            }
            boolean claimed = store.claim(fullName, trigger.getDescriptor().getId(), tick.getTimeInMillis() / 60_000);
            LOGGER.fine(() -> (claimed ? "Claimed " : "Already claimed: ")
                    + trigger.getDescriptor().getId() + " of " + fullName);
            return claimed;
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Cannot coordinate " + trigger + " of " + fullName + "; skipping this tick", e);
            return false;
        }
    }

    private synchronized Set<String> members(ClusterStore store) throws IOException {
        long now = System.currentTimeMillis();
        if (members == null || now - membersFetched > MEMBERS_CACHE_MILLIS) {
            members = store.aliveMembers();
            membersFetched = now;
        }
        return members;
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
        return ownerOf(topLevel(fullName), store.aliveMembers());
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
