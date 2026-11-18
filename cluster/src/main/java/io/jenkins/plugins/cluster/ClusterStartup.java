package io.jenkins.plugins.cluster;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.lifecycle.RestartNotSupportedException;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.cluster.builds.RunningBuilds;
import io.jenkins.plugins.cluster.spi.ClusterMember;
import io.jenkins.plugins.cluster.spi.ClusterStore;
import io.jenkins.plugins.cluster.spi.QueueMaintenance;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.persistence.PersistenceStore;

/**
 * What a store provider calls as its {@link ClusterStore} joins the cluster, so that every backend starts a controller
 * the same way.
 */
public final class ClusterStartup {

    private static final Logger LOGGER = Logger.getLogger(ClusterStartup.class.getName());

    private ClusterStartup() {}

    /**
     * Before the store's controller takes part in membership: refuses a controller without the cluster's key material
     * ({@link KeyCheck}). That includes a file-based home being imported: into an empty store, its keys become the
     * cluster's; into a store with other keys, the secrets it imported could not be read by the cluster's controllers.
     */
    public static void beforeJoining(@NonNull PersistenceStore store) throws IOException {
        KeyCheck.verify(store);
    }

    /** Says once, at startup, if other controllers reach this one in the clear outside the cluster's own network. */
    @Initializer(after = InitMilestone.EXTENSIONS_AUGMENTED)
    public static void checkInternalUrl() {
        if (ClusterStore.get() != null) {
            Forwarding.warnIfExposed(ClusterMember.INTERNAL_URL);
        }
    }

    /** Once the store's controller takes part in membership: imports a file-based home, if asked to. */
    public static void afterJoining(@NonNull ClusterStore store) throws IOException {
        HomeImporter.importFiles(store);
    }

    private static final java.util.concurrent.atomic.AtomicBoolean restarting =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** How the process ends when Jenkins cannot restart itself: the container is then restarted. */
    static /* non-final for tests */ java.util.function.IntConsumer exit = System::exit;

    /**
     * What a store calls when it went back, restored to an earlier point in time while this controller kept running:
     * what this controller holds (the versions it read, its caches, builds it made since) may be newer than the store,
     * so it restarts, from the store as it is now, rather than going on or repairing itself in place. A safe restart if
     * Jenkins can restart itself (running builds finish first), else the process exits for its container to be
     * restarted. Once.
     *
     * @param why what the store saw, for the log
     */
    public static void storeWentBack(@NonNull String why) {
        Jenkins j = Jenkins.getInstanceOrNull();
        LOGGER.severe(() -> "Restarting this controller: " + why + ". It may hold state newer than the store's;"
                + " every controller of the cluster must restart after the store is restored.");
        if (j == null || !restarting.compareAndSet(false, true)) {
            return;
        }
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            j.safeRestart("The cluster's store was restored to an earlier point in time");
        } catch (RestartNotSupportedException | RuntimeException e) {
            LOGGER.log(Level.SEVERE, "Cannot restart Jenkins itself; exiting, for the container to be restarted", e);
            exit.accept(STORE_WENT_BACK_EXIT);
        }
    }

    /** The exit status when the store went back and Jenkins could not restart itself. */
    static final int STORE_WENT_BACK_EXIT = 75;

    /** For tests: as before the store went back. */
    static void restartedForTests() {
        restarting.set(false);
    }

    /**
     * When controllers are seen leaving the cluster: their builds are adopted (or closed) and their queue items put back
     * now, not at the next sweep.
     */
    public static void membersLeft(@NonNull QueueMaintenance queue) {
        RunningBuilds.sweepSoon();
        jenkins.util.Timer.get().submit(() -> {
            try {
                int n = queue.reclaimAndPrune();
                if (n > 0) {
                    LOGGER.info(() -> "Put " + n + " queue item(s) of controllers which left back into the queue");
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.WARNING, "Could not put back the queue items of controllers which left", e);
            }
        });
    }
}
