package io.jenkins.plugins.cnj.store;

import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.util.Secret;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;
import jenkins.persistence.ConflictException;
import jenkins.persistence.PersistenceStore;
import jenkins.persistence.PersistenceStores;
import jenkins.util.SystemProperties;

/**
 * Refuses to start a controller that does not have the cluster's key material (Target 15).
 *
 * <p>Every controller of a cluster must start with the same seeded keys ({@code secret.key}, {@code secrets/}): the
 * store holds secrets encrypted with them. A controller started without them would create keys of its own, could not
 * read the cluster's secrets, and would write secrets nobody else can read. So the first controller stores a value
 * encrypted with its key ({@link #KEY}); a controller that cannot decrypt it stops, saying why.
 * {@code -Dio.jenkins.plugins.cnj.store.KeyCheck.skip=true} skips the check.
 */
public final class KeyCheck {

    private static final Logger LOGGER = Logger.getLogger(KeyCheck.class.getName());

    static final String KEY = "cluster/key-check";
    static final String VALUE = "jenkins-cluster-key-check";

    private KeyCheck() {}

    @Initializer(after = InitMilestone.EXTENSIONS_AUGMENTED, before = InitMilestone.JOB_LOADED)
    public static void check() throws IOException {
        PersistenceStore store = PersistenceStores.get();
        if (!(store instanceof PostgresPersistenceStore) || SystemProperties.getBoolean(KeyCheck.class.getName() + ".skip")) {
            return;
        }
        PersistenceStore.Entry e = store.read(KEY);
        if (e == null) {
            try {
                store.write(KEY, Secret.fromString(VALUE).getEncryptedValue().getBytes(StandardCharsets.UTF_8), PersistenceStore.ABSENT);
                LOGGER.info("Recorded the cluster's key check: controllers started without this controller's keys will refuse to start");
            } catch (ConflictException raced) {
                check(); // another controller recorded it first: check against theirs
            }
            return;
        }
        Secret s = Secret.decrypt(new String(e.content(), StandardCharsets.UTF_8));
        if (s == null || !VALUE.equals(s.getPlainText())) {
            // leave the cluster before failing: a Jenkins that failed to start keeps running (its error page), and must
            // not go on heartbeating as a member
            ((PostgresPersistenceStore) store).close();
            throw new IllegalStateException("This controller does not have the key material of the cluster it shares a store with:"
                    + " it cannot decrypt the cluster's secrets, and would write secrets the others cannot read. Start it with"
                    + " the cluster's seeded keys (secret.key, secrets/; the chart's seed Secret), or set"
                    + " -Dio.jenkins.plugins.cnj.store.KeyCheck.skip=true to start anyway.");
        }
    }
}
