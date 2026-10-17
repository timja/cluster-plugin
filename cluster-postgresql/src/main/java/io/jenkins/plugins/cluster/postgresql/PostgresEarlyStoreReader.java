package io.jenkins.plugins.cluster.postgresql;

import io.jenkins.plugins.cluster.spi.ClusterMember;
import io.jenkins.plugins.cluster.spi.EarlyStoreReader;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/** Reads the store before Jenkins sets it up, with the settings of {@link PostgresPersistenceStoreProvider}. */
@Extension
public class PostgresEarlyStoreReader implements EarlyStoreReader {

    private static final Logger LOGGER = Logger.getLogger(PostgresEarlyStoreReader.class.getName());

    @CheckForNull
    @Override
    public byte[] readEarly(@NonNull String key) throws IOException {
        String url = PostgresPersistenceStoreProvider.setting("url", "CLUSTER_POSTGRES_URL");
        if (url == null) {
            return null;
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(ClusterMember.STARTUP_WAIT_SECONDS);
        long delay = 1000;
        while (true) {
            try {
                return PostgresPersistenceStore.readEarly(url,
                        PostgresPersistenceStoreProvider.setting("user", "CLUSTER_POSTGRES_USER"),
                        PostgresPersistenceStoreProvider.setting("password", "CLUSTER_POSTGRES_PASSWORD"), key);
            } catch (SQLException e) {
                if (!PostgresPersistenceStore.isConnectionFailure(e) || System.nanoTime() > deadline) {
                    throw new IOException("Could not read " + key + " from " + url, e);
                }
                // the database may be starting, as when the store is set up
                LOGGER.warning(() -> "Waiting for the persistence store " + url + ": " + e.getMessage());
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException x) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException();
                }
                delay = Math.min(delay * 2, 10_000);
            }
        }
    }
}
