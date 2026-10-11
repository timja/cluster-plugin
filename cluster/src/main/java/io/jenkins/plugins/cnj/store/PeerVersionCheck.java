package io.jenkins.plugins.cnj.store;

import hudson.init.InitMilestone;
import hudson.init.Initializer;
import java.io.IOException;
import java.util.List;
import java.util.logging.Logger;
import jenkins.persistence.PersistenceStores;

/**
 * Refuses to start next to peers too old to coexist with this version (Target 10, rolling upgrades): with
 * {@code -Dio.jenkins.plugins.cnj.store.minimumPeerProtocol=N}, this controller does not start while a controller speaking
 * an older protocol is alive. Upgrades raising the protocol replace every controller before such a version starts.
 */
public final class PeerVersionCheck {

    private static final Logger LOGGER = Logger.getLogger(PeerVersionCheck.class.getName());

    private PeerVersionCheck() {}

    @Initializer(after = InitMilestone.EXTENSIONS_AUGMENTED, before = InitMilestone.SYSTEM_CONFIG_LOADED) // the store exists from EXTENSIONS_AUGMENTED
    public static void check() throws IOException {
        int minimum = PostgresPersistenceStore.MINIMUM_PEER_PROTOCOL;
        if (minimum <= 0 || !(PersistenceStores.get() instanceof PostgresPersistenceStore store)) {
            return;
        }
        List<String> old = store.peersBelow(minimum);
        if (!old.isEmpty()) {
            String message = "This controller needs every peer to speak cluster protocol " + minimum + " or later, but "
                    + String.join(", ", old) + " is alive; replace it first";
            LOGGER.severe(message);
            throw new IllegalStateException(message);
        }
        LOGGER.info(() -> "Every live peer speaks cluster protocol " + minimum + " or later");
    }
}
