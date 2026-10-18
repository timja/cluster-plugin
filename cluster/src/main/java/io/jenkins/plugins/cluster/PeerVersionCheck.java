package io.jenkins.plugins.cluster;

import hudson.init.InitMilestone;
import hudson.init.Initializer;
import io.jenkins.plugins.cluster.spi.ClusterMember;
import io.jenkins.plugins.cluster.spi.ClusterStore;
import java.io.IOException;
import java.util.List;
import java.util.logging.Logger;
import jenkins.persistence.PersistenceStores;

/**
 * Refuses to start next to peers too old to coexist with this version (Target 10, rolling upgrades): with
 * {@code -Dio.jenkins.plugins.cluster.minimumPeerProtocol=N}, this controller does not start while a controller speaking
 * an older protocol is alive. Upgrades raising the protocol replace every controller before such a version starts.
 * The other way round, a controller older than the cluster's protocol floor (raised by a contracted schema migration,
 * which it would undo by writing the old form) refuses to start too. Two controllers started at the same moment can both
 * pass before either has heartbeated: rolling upgrades start one at a time.
 */
public final class PeerVersionCheck {

    private static final Logger LOGGER = Logger.getLogger(PeerVersionCheck.class.getName());

    private PeerVersionCheck() {}

    @Initializer(
            after = InitMilestone.EXTENSIONS_AUGMENTED,
            before = InitMilestone.SYSTEM_CONFIG_LOADED) // the store exists from EXTENSIONS_AUGMENTED
    public static void check() throws IOException {
        if (!(PersistenceStores.get() instanceof ClusterStore store)) {
            return;
        }
        int floor = store.protocolFloor();
        if (ClusterMember.PROTOCOL < floor) {
            String message = "This controller speaks cluster protocol " + ClusterMember.PROTOCOL
                    + ", but the cluster's schema needs " + floor
                    + " or later (a contracted migration); run a newer version";
            LOGGER.severe(message);
            leave(store);
            throw new IllegalStateException(message);
        }
        int minimum = ClusterMember.MINIMUM_PEER_PROTOCOL;
        if (minimum <= 0) {
            return;
        }
        List<String> old = store.peersBelow(minimum);
        if (!old.isEmpty()) {
            String message = "This controller needs every peer to speak cluster protocol " + minimum + " or later, but "
                    + String.join(", ", old) + " is alive; replace it first";
            LOGGER.severe(message);
            leave(store);
            throw new IllegalStateException(message);
        }
        LOGGER.info(() -> "Every live peer speaks cluster protocol " + minimum + " or later");
    }

    /**
     * Leaves the cluster before failing to start: a Jenkins that failed to start keeps running (its error page), and must
     * not go on heartbeating as a member, holding a share of the triggers and counting as a peer below the protocol.
     */
    static void leave(Object store) {
        if (store instanceof AutoCloseable c) {
            try {
                c.close();
            } catch (Exception x) {
                LOGGER.log(java.util.logging.Level.FINE, "Could not close the store", x);
            }
        }
    }
}
