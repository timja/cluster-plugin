package io.jenkins.plugins.cnj.store;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import jenkins.persistence.PersistenceStore;
import jenkins.persistence.PersistenceStoreProvider;

/**
 * Activates {@link PostgresPersistenceStore} when {@code CNJ_STORE_URL} (or the system property
 * {@code io.jenkins.plugins.cnj.store.url}) is set. Credentials come from {@code CNJ_STORE_USER} / {@code CNJ_STORE_PASSWORD}.
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
            "jobs/[^/]+(/(jobs|branches)/[^/]+)*/(config\\.xml|nextBuildNumber|scm-revision-hash\\.xml|scm-last-seen-revision-hash\\.xml)");

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
        String url = setting("url", "CNJ_STORE_URL");
        if (url == null) {
            return null;
        }
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(STARTUP_WAIT_SECONDS);
        long delay = 1000;
        while (true) {
            PostgresPersistenceStore store = null;
            try {
                store = new PostgresPersistenceStore(
                        url,
                        setting("user", "CNJ_STORE_USER"),
                        setting("password", "CNJ_STORE_PASSWORD"),
                        PostgresPersistenceStoreProvider::owns);
                store.startHeartbeat();
                HomeImporter.importFiles(store); // only with -D…importHome=true
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

    /** How long a starting controller waits for its persistence store (and blob store) before failing to start. */
    static final long STARTUP_WAIT_SECONDS = Long.getLong(PostgresPersistenceStoreProvider.class.getPackageName() + ".startupWaitSeconds", 600);

    @CheckForNull
    private static String setting(String property, String env) {
        String v = System.getProperty(PostgresPersistenceStoreProvider.class.getPackageName() + "." + property);
        return v != null ? v : System.getenv(env);
    }
}
