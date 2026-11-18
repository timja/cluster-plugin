package io.jenkins.plugins.cluster.spi;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.ExtensionList;
import java.util.logging.Logger;

/** Resolves the {@link ClusterBlobStore} once for each active {@link ClusterStore}. */
final class BlobStores {

    private static final Logger LOGGER = Logger.getLogger(BlobStores.class.getName());

    /** The store the blob store was resolved for; the blob store goes with it. */
    private static volatile ClusterStore resolvedFor;

    private static volatile ClusterBlobStore resolved;

    private BlobStores() {}

    @CheckForNull
    static ClusterBlobStore get() {
        ClusterStore store = ClusterStore.get();
        if (store == null) {
            return null;
        }
        if (resolvedFor == store) {
            return resolved;
        }
        // the extension list is loaded before taking the lock: loading extensions takes a global lock, and threads
        // holding it may get here
        ExtensionList<ClusterBlobStoreProvider> providers = ExtensionList.lookup(ClusterBlobStoreProvider.class);
        providers.size();
        synchronized (BlobStores.class) {
            if (resolvedFor != store) {
                ClusterBlobStore blobs = null;
                for (ClusterBlobStoreProvider provider : providers) {
                    blobs = provider.create();
                    if (blobs != null) {
                        LOGGER.info(
                                () -> "Using blob store " + provider.getClass().getName());
                        break;
                    }
                }
                resolved = blobs;
                resolvedFor = store;
            }
            return resolved;
        }
    }
}
