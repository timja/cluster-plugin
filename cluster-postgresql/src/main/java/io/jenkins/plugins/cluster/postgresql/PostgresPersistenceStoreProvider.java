package io.jenkins.plugins.cluster.postgresql;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import io.jenkins.plugins.cluster.ClusterStartup;
import io.jenkins.plugins.cluster.spi.ClusterMember;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.persistence.PersistenceStore;
import jenkins.persistence.PersistenceStoreProvider;

/**
 * Activates {@link PostgresPersistenceStore} when {@code CLUSTER_POSTGRES_URL} (or the system property
 * {@code io.jenkins.plugins.cluster.postgresql.url}) is set. Credentials come from {@code CLUSTER_POSTGRES_USER} / {@code CLUSTER_POSTGRES_PASSWORD}.
 *
 * <p>Which files the store holds is core's routing (its default shared set, and the cluster plugin's
 * {@link jenkins.persistence.SharedFiles} and {@link jenkins.persistence.LocalFiles}), not the store's.
 */
@Extension
public class PostgresPersistenceStoreProvider implements PersistenceStoreProvider {

    private static final Logger LOGGER = Logger.getLogger(PostgresPersistenceStoreProvider.class.getName());

    @CheckForNull
    @Override
    public PersistenceStore create() {
        String url = setting("url", "CLUSTER_POSTGRES_URL");
        if (url == null) {
            return null;
        }
        long deadline =
                System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(ClusterMember.STARTUP_WAIT_SECONDS);
        long delay = 1000;
        while (true) {
            PostgresPersistenceStore store = null;
            try {
                store = new PostgresPersistenceStore(
                        url,
                        setting("user", "CLUSTER_POSTGRES_USER"),
                        setting("password", "CLUSTER_POSTGRES_PASSWORD"));
                // a controller leaving: its builds are adopted (or closed) and its queue items put back now
                PostgresPersistenceStore created = store;
                store.onMembersLeft(left -> ClusterStartup.membersLeft(created));
                ClusterStartup.beforeJoining(store); // refuses a controller without the cluster's key material
                store.startHeartbeat();
                ClusterStartup.afterJoining(store); // only with -D…importHome=true
                return store;
            } catch (IOException e) {
                if (store != null) {
                    store.close();
                }
                if (System.nanoTime() > deadline) {
                    // Refusing to start without the store is safer than silently diverging from other controllers.
                    LOGGER.log(Level.SEVERE, "Cannot connect to persistence store " + url, e);
                    throw new IllegalStateException(e);
                }
                // the database may be starting, or briefly unreachable: Jenkins waits (it is not ready meanwhile)
                LOGGER.log(Level.WARNING, "Waiting for the persistence store " + url + ": " + e.getMessage());
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException x) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(x);
                }
                delay = Math.min(delay * 2, 10_000);
            }
        }
    }

    @CheckForNull
    static String setting(String property, String env) {
        String v = System.getProperty(PostgresPersistenceStoreProvider.class.getPackageName() + "." + property);
        return v != null ? v : System.getenv(env);
    }
}
