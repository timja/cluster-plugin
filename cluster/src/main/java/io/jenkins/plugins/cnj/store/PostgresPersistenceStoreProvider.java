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
 * <p>Owns item configuration: {@code config.xml} of top-level items, folder children ({@code jobs/})
 * and multibranch children ({@code branches/}).
 */
@Extension
public class PostgresPersistenceStoreProvider implements PersistenceStoreProvider {

    private static final Logger LOGGER = Logger.getLogger(PostgresPersistenceStoreProvider.class.getName());

    static final Pattern ITEM_CONFIG = Pattern.compile("jobs/[^/]+(/(jobs|branches)/[^/]+)*/config\\.xml");

    @CheckForNull
    @Override
    public PersistenceStore create() {
        String url = setting("url", "CNJ_STORE_URL");
        if (url == null) {
            return null;
        }
        try {
            return new PostgresPersistenceStore(url, setting("user", "CNJ_STORE_USER"), setting("password", "CNJ_STORE_PASSWORD"),
                    key -> ITEM_CONFIG.matcher(key).matches());
        } catch (IOException e) {
            // Refusing to start without the store is safer than silently diverging from other controllers.
            LOGGER.log(Level.SEVERE, "Cannot connect to persistence store " + url, e);
            throw new IllegalStateException(e);
        }
    }

    @CheckForNull
    private static String setting(String property, String env) {
        String v = System.getProperty(PostgresPersistenceStoreProvider.class.getPackageName() + "." + property);
        return v != null ? v : System.getenv(env);
    }
}
