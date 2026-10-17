package io.jenkins.plugins.cluster.spi;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ExtensionPoint;
import java.io.IOException;

/**
 * Reads the store before Jenkins has set it up (that is from {@code EXTENSIONS_AUGMENTED}), for what a controller
 * needs in place before it loads anything: the cluster's runtime-installed plugins. Implemented by the store plugin,
 * without joining the cluster (no membership, no heartbeat).
 */
public interface EarlyStoreReader extends ExtensionPoint {
    /** The stored content of {@code key}, or null if it is not stored, or this store is not configured here. */
    @CheckForNull
    byte[] readEarly(@NonNull String key) throws IOException;
}
