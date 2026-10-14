package io.jenkins.plugins.cluster.spi;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import jenkins.persistence.PersistenceStore;
import jenkins.persistence.PersistenceStores;

/**
 * What the cluster needs of a {@link PersistenceStore} beyond the core SPI: implemented by the store a backend plugin
 * provides (such as the PostgreSQL one). A controller whose store does not implement it is not clustered: the features
 * that need it stay off, as without a store.
 */
public interface ClusterStore extends PersistenceStore, Membership, Claims, Channels, Counters, QueueMaintenance {

    /** The active store, if it is a cluster store. */
    @CheckForNull
    static ClusterStore get() {
        return PersistenceStores.get() instanceof ClusterStore s ? s : null;
    }
}
