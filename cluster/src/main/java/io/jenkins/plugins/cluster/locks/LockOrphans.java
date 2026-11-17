package io.jenkins.plugins.cluster.locks;

import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Job;
import hudson.model.TaskListener;
import io.jenkins.plugins.cluster.spi.ClusterStore;
import io.jenkins.plugins.cluster.spi.Keys;
import java.io.IOException;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.persistence.ConflictException;
import jenkins.persistence.PersistenceStore;
import jenkins.util.ClusterSingleton;
import org.jenkins.plugins.lockableresources.ResourceState;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Frees resources held by builds that no longer run anywhere, and by remote locks (lockable-resources' remote lock API)
 * of a controller that is gone: the controller that took them is gone, and the build is neither running nor waiting to
 * be adopted ({@link Keys#placeholder} is gone too). Reservations belong to users, and stay.
 */
@Extension(optional = true)
@ClusterSingleton
@Restricted(NoExternalUse.class)
public final class LockOrphans extends AsyncPeriodicWork {

    private static final Logger LOGGER = Logger.getLogger(LockOrphans.class.getName());

    public LockOrphans() {
        super("Free resources held by builds gone with their controller");
    }

    @Override
    public long getRecurrencePeriod() {
        return TimeUnit.SECONDS.toMillis(60);
    }

    @Override
    protected void execute(TaskListener listener) throws IOException {
        if (!LockableResourcesPlugin.isInstalled()) {
            return;
        }
        ClusterStore store = ClusterResourceStates.store();
        if (store == null || !store.canJudgeLiveness()) {
            return;
        }
        sweep(store);
    }

    /** @return how many resources were freed of a holder */
    static int sweep(ClusterStore store) throws IOException {
        Set<String> alive = store.aliveMembers();
        int freed = 0;
        for (String segment : store.children(ClusterResourceStates.LOCKS)) {
            String key = ClusterResourceStates.LOCKS + "/" + segment;
            PersistenceStore.Entry entry = store.read(key);
            if (entry == null) {
                continue;
            }
            Properties p = ClusterResourceStates.properties(entry.content());
            ResourceState s = ClusterResourceStates.parse(entry.content());
            String origin = p == null ? null : p.getProperty("origin");
            if (s == null || origin == null || alive.contains(origin)) {
                continue;
            }
            ResourceState next = s;
            if (s.getRemoteLock() != null) {
                next = next.withRemoteLock(null); // it lived in its controller's memory, and went with it
            }
            String build = s.getBuild();
            if (build != null && !runsSomewhere(store, build)) {
                next = next.withoutBuild();
            }
            if (next.equals(s)) {
                continue;
            }
            try {
                if (next.isFree()) {
                    store.delete(key, entry.version());
                } else {
                    store.write(key, ClusterResourceStates.toBytes(next, origin), entry.version());
                }
            } catch (ConflictException e) {
                continue; // changed meanwhile: not the orphan any more
            }
            String name = ClusterResourceStates.name(key);
            LOGGER.info(() -> "Freed " + name + " of " + s + ", gone with controller " + origin);
            ClusterResourceStates.instance().changed(key, next.isFree());
            freed++;
        }
        return freed;
    }

    /** Whether a build (by externalizable id) is running, or waiting to be adopted, on some controller. */
    private static boolean runsSomewhere(ClusterStore store, String build) throws IOException {
        int hash = build.lastIndexOf('#');
        if (hash <= 0) {
            return true; // not a build id: cannot tell, so leave it
        }
        int number;
        try {
            number = Integer.parseInt(build.substring(hash + 1));
        } catch (NumberFormatException e) {
            return true;
        }
        Job<?, ?> job = Jenkins.get().getItemByFullName(build.substring(0, hash), Job.class);
        return job != null && store.read(Keys.placeholder(Keys.itemByUuid(job.getItemUuid()), number)) != null;
    }
}
