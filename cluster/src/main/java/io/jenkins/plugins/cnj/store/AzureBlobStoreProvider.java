package io.jenkins.plugins.cnj.store;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.persistence.BlobStore;
import jenkins.persistence.BlobStoreProvider;

/**
 * Configures an {@link AzureBlobStore} from the environment:
 * {@code CNJ_BLOB_CONNECTION_STRING} (an Azure Storage connection string, e.g. for Azurite) and
 * {@code CNJ_BLOB_CONTAINER} (default {@code jenkins}).
 * System properties {@code io.jenkins.plugins.cnj.store.blobConnectionString} and {@code ...blobContainer} take precedence.
 */
@Extension
public class AzureBlobStoreProvider implements BlobStoreProvider {

    private static final Logger LOGGER = Logger.getLogger(AzureBlobStoreProvider.class.getName());

    @CheckForNull
    @Override
    public BlobStore create() {
        String connectionString = setting("blobConnectionString", "CNJ_BLOB_CONNECTION_STRING");
        if (connectionString == null) {
            return null;
        }
        String container = setting("blobContainer", "CNJ_BLOB_CONTAINER");
        try {
            return new AzureBlobStore(connectionString, container == null ? "jenkins" : container);
        } catch (RuntimeException e) {
            // as for the persistence store: refuse to run with history silently unshared
            LOGGER.log(Level.SEVERE, "Cannot connect to blob store", e);
            throw new IllegalStateException(e);
        }
    }

    @CheckForNull
    private static String setting(String property, String env) {
        String v = System.getProperty(AzureBlobStoreProvider.class.getPackageName() + "." + property);
        return v != null ? v : System.getenv(env);
    }
}
