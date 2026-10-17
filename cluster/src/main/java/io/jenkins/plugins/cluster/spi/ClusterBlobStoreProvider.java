package io.jenkins.plugins.cluster.spi;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.ExtensionPoint;

/**
 * Supplies the {@link ClusterBlobStore}: implemented by blob store plugins (replaces core's former
 * {@code BlobStoreProvider}). Consulted once, after the {@link ClusterStore} is resolved; the first non-null wins.
 */
public interface ClusterBlobStoreProvider extends ExtensionPoint {

    /** The blob store to use, or null if this provider is not configured. */
    @CheckForNull
    ClusterBlobStore create();
}
