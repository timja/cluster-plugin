package io.jenkins.plugins.cluster;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import io.jenkins.plugins.cluster.builds.RunningBuilds;
import io.jenkins.plugins.cluster.spi.ClusterMember;
import io.jenkins.plugins.cluster.spi.ClusterStore;
import io.jenkins.plugins.cluster.spi.QueueMaintenance;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
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

    /**
     * When controllers are seen leaving the cluster: their builds are adopted (or closed) and their queue items put back
     * now, not at the next sweep (Target 14).
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
