package io.jenkins.plugins.cluster.azure;

import io.jenkins.plugins.cluster.spi.ClusterMember;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.persistence.BlobStore;
import jenkins.persistence.BlobStoreProvider;

/**
 * Configures an {@link AzureBlobStore} from the environment:
 * {@code CLUSTER_AZURE_BLOB_CONNECTION_STRING} (an Azure Storage connection string, e.g. for Azurite) and
 * {@code CLUSTER_AZURE_BLOB_CONTAINER} (default {@code jenkins}).
 * System properties {@code io.jenkins.plugins.cluster.azure.connectionString} and {@code ...container} take precedence.
 */
@Extension
public class AzureBlobStoreProvider implements BlobStoreProvider {

    private static final Logger LOGGER = Logger.getLogger(AzureBlobStoreProvider.class.getName());

    @CheckForNull
    @Override
    public BlobStore create() {
        String connectionString = setting("connectionString", "CLUSTER_AZURE_BLOB_CONNECTION_STRING");
        if (connectionString == null) {
            return null;
        }
        String container = setting("container", "CLUSTER_AZURE_BLOB_CONTAINER");
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(ClusterMember.STARTUP_WAIT_SECONDS);
        long delay = 1000;
        while (true) {
            try {
                return new AzureBlobStore(connectionString, container == null ? "jenkins" : container);
            } catch (RuntimeException e) {
                if (System.nanoTime() > deadline) {
                    // as for the persistence store: refuse to run with history silently unshared
                    LOGGER.log(Level.SEVERE, "Cannot connect to blob store", e);
                    throw new IllegalStateException(e);
                }
                LOGGER.log(Level.WARNING, "Waiting for the blob store: " + e.getMessage());
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
        String v = System.getProperty(AzureBlobStoreProvider.class.getPackageName() + "." + property);
        return v != null ? v : System.getenv(env);
    }
}
