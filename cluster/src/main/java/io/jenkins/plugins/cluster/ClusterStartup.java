package io.jenkins.plugins.cluster;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.plugins.cluster.spi.ClusterStore;
import io.jenkins.plugins.cluster.spi.QueueMaintenance;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.persistence.PersistenceStore;
import jenkins.persistence.RunningBuilds;

/**
 * What a store provider calls as its {@link ClusterStore} joins the cluster, so that every backend starts a controller
 * the same way.
 */
public final class ClusterStartup {

    private static final Logger LOGGER = Logger.getLogger(ClusterStartup.class.getName());

    private ClusterStartup() {}

    /**
     * Before the store's controller takes part in membership: refuses a controller without the cluster's key material
     * ({@link KeyCheck}), unless a file-based home is being imported.
     */
    public static void beforeJoining(@NonNull PersistenceStore store) throws IOException {
        if (!HomeImporter.IMPORT) {
            KeyCheck.verify(store);
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
