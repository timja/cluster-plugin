package io.jenkins.plugins.cluster.postgresql;

import io.jenkins.plugins.cluster.ClusterStartup;
import io.jenkins.plugins.cluster.spi.ClusterMember;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import jenkins.persistence.PersistenceStore;
import jenkins.persistence.PersistenceStoreProvider;

/**
 * Activates {@link PostgresPersistenceStore} when {@code CLUSTER_POSTGRES_URL} (or the system property
 * {@code io.jenkins.plugins.cluster.postgresql.url}) is set. Credentials come from {@code CLUSTER_POSTGRES_USER} / {@code CLUSTER_POSTGRES_PASSWORD}.
 *
 * <p>Owns, for top-level items, folder children ({@code jobs/}) and multibranch children ({@code branches/}):
 * <ul>
 * <li>{@code config.xml}: item configuration (Target 1)
 * <li>{@code nextBuildNumber}: the cluster-wide build number sequence (Target 2)
 * <li>{@code scm-revision-hash.xml}, {@code scm-last-seen-revision-hash.xml}: branch indexing state,
 *     so whichever controller indexes compares against the same revisions (Target 2)
 * </ul>
 */
@Extension
public class PostgresPersistenceStoreProvider implements PersistenceStoreProvider {

    private static final Logger LOGGER = Logger.getLogger(PostgresPersistenceStoreProvider.class.getName());

    static final Pattern OWNED = Pattern.compile(
            "jobs/[^/]+(/(jobs|branches)/[^/]+)*/(config\\.xml|nextBuildNumber|scm-revision-hash\\.xml|scm-last-seen-revision-hash\\.xml"
                    // Target 15: sub-items configured in the job (promoted-builds' processes, matrix configurations)
                    + "|promotions/[^/]+/config\\.xml|configurations(/[^/]+/[^/]+)+/config\\.xml)");

    /**
     * Static state (Target 6): global configuration and descriptors at the root, users, nodes.
     * Per-controller files at the root are excluded, as are files mixing configuration with runtime state:
     * lockable-resources' (declared resources with each controller's waiting builds; Target 5 shares its locks).
     */
    static final Pattern STATIC = Pattern.compile(
            "users/[^/]+/config\\.xml|nodes/[^/]+/config\\.xml"
                    + "|(?!queue\\.xml$|nodeMonitors\\.xml$|hudson\\.model\\.UpdateCenter\\.xml$|jenkins\\.install\\.|.*FlowExecutionList\\.xml$"
                    + "|org\\.jenkins\\.plugins\\.lockableresources\\.LockableResourcesManager\\.xml$)[^/]+\\.xml");

    /** Whether static state is shared; on by default. */
    static final boolean SHARE_STATIC = !"false"
            .equals(System.getProperty(PostgresPersistenceStoreProvider.class.getPackageName() + ".staticState"));

    static boolean owns(String key) {
        return OWNED.matcher(key).matches()
                || SHARE_STATIC && STATIC.matcher(key).matches();
    }

    @CheckForNull
    @Override
    public PersistenceStore create() {
        String url = setting("url", "CLUSTER_POSTGRES_URL");
        if (url == null) {
            return null;
        }
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(ClusterMember.STARTUP_WAIT_SECONDS);
        long delay = 1000;
        while (true) {
            PostgresPersistenceStore store = null;
            try {
                store = new PostgresPersistenceStore(
                        url,
                        setting("user", "CLUSTER_POSTGRES_USER"),
                        setting("password", "CLUSTER_POSTGRES_PASSWORD"),
                        PostgresPersistenceStoreProvider::owns);
                // a controller leaving: its builds are adopted (or closed) and its queue items put back now (Target 14)
                PostgresPersistenceStore created = store;
                store.onMembersLeft(left -> ClusterStartup.membersLeft(created));
                ClusterStartup.beforeJoining(store); // Target 15: refuses a controller without the cluster's key material
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
    private static String setting(String property, String env) {
        String v = System.getProperty(PostgresPersistenceStoreProvider.class.getPackageName() + "." + property);
        return v != null ? v : System.getenv(env);
    }
}
