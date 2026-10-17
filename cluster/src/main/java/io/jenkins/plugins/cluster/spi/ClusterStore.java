package io.jenkins.plugins.cluster.spi;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import jenkins.persistence.PersistenceStore;
import jenkins.persistence.PersistenceStores;

/**
 * What the cluster needs of a {@link PersistenceStore} beyond core's key-value SPI (the coordination facets):
 * implemented by the store a backend plugin provides (such as the PostgreSQL one), versioned by the cluster
 * protocol ({@link ClusterMember#PROTOCOL}), not by core. A controller whose store does not implement it is not clustered: the features
 * that need it stay off, as without a store.
 */
public interface ClusterStore extends PersistenceStore, Membership, Claims, Channels, Counters, QueueMaintenance {

    /** Leases on running builds, with fenced writes. */
    @NonNull
    BuildLeases leases();

    /** The build queue shared by the controllers. */
    @NonNull
    ClusterQueue queue();

    /** Leases on the outbound connections of static agents. */
    @NonNull
    LaunchLeases launchLeases();

    /** The active store, if it is a cluster store. */
    @CheckForNull
    static ClusterStore get() {
        return PersistenceStores.get() instanceof ClusterStore s ? s : null;
    }
}
