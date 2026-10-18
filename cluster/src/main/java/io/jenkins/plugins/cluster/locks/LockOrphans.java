package io.jenkins.plugins.cluster.locks;

import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.TaskListener;
import io.jenkins.plugins.cluster.spi.ClusterStore;
import io.jenkins.plugins.cluster.spi.Keys;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import jenkins.persistence.ConflictException;
import jenkins.persistence.PersistenceStore;
import jenkins.util.ClusterSingleton;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Releases locks held by builds that no longer run anywhere: their controller is gone, and the build is neither
 * running nor waiting to be adopted ({@link Keys#placeholder} is gone too), and remote locks (lockable-resources'
 * remote lock API) of a controller that is gone. Reservations belong to users, and stay.
 */
@Extension(optional = true)
@ClusterSingleton
@Restricted(NoExternalUse.class)
public final class LockOrphans extends AsyncPeriodicWork {

    private static final Logger LOGGER = Logger.getLogger(LockOrphans.class.getName());

    public LockOrphans() {
        super("Release locks of builds gone with their controller");
    }

    @Override
    public long getRecurrencePeriod() {
        return TimeUnit.SECONDS.toMillis(60);
    }

    @Override
    protected void execute(TaskListener listener) throws IOException {
        ClusterStore store = ClusterLocks.store();
        if (store == null || !store.canJudgeLiveness()) {
            return;
        }
        sweep(store);
    }

    /** @return how many locks were released */
    static int sweep(ClusterStore store) throws IOException {
        Set<String> alive = store.aliveMembers();
        int released = 0;
        for (String segment : store.children(ClusterLocks.LOCKS)) {
            String name = ClusterLocks.decode(segment);
            String key = ClusterLocks.key(name);
            PersistenceStore.Entry entry = store.read(key);
            ClusterLocks.Holder h = ClusterLocks.Holder.parse(entry);
            if (entry == null || h == null || h.isReservation() || h.origin() == null || alive.contains(h.origin())) {
                continue;
            }
            if (h.isRemote()) {
                // a remote lock lives in its controller's memory only, and went with it
                if (release(store, name, key, entry, h)) {
                    released++;
                }
                continue;
            }
            String build = h.build();
            int hash = build == null ? -1 : build.lastIndexOf('#');
            if (hash <= 0) {
                continue; // not a build with an identity: cannot tell whether it runs, so leave it
            }
            int number;
            try {
                number = Integer.parseInt(build.substring(hash + 1));
            } catch (NumberFormatException e) {
                continue;
            }
            if (store.read(Keys.placeholder(Keys.itemByUuid(build.substring(0, hash)), number)) != null) {
                continue; // still running, or being adopted, somewhere
            }
            if (release(store, name, key, entry, h)) {
                released++;
            }
        }
        return released;
    }

    private static boolean release(
            ClusterStore store, String name, String key, PersistenceStore.Entry entry, ClusterLocks.Holder h)
            throws IOException {
        try {
            store.delete(key, entry.version());
        } catch (ConflictException e) {
            return false; // changed meanwhile: not the orphan any more
        }
        LOGGER.info(() -> "Released " + name + ", held by " + h.describe() + " which is gone");
        ClusterLocks.get().changed(key, true);
        return true;
    }
}
