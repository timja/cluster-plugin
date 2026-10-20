package io.jenkins.plugins.cluster;

import hudson.Extension;
import hudson.ExtensionList;
import io.jenkins.plugins.cluster.spi.ClusterStore;
import jenkins.persistence.PersistenceStores;
import jenkins.plugins.PluginManagerNotice;

/**
 * On the plugin manager's pages (through core's {@code PluginManager/notices.jelly}), says that changes made there
 * apply to every controller of the cluster, and shows each controller's progress applying them.
 */
@Extension
public final class ClusterPluginManagerNotice extends PluginManagerNotice {

    @Override
    public boolean isVisible() {
        return isActive() || isFixed();
    }

    /** Whether to show the notice: a cluster store, with plugin changes shared (not {@code fixed} or {@code local}). */
    public boolean isActive() {
        return PersistenceStores.get() instanceof ClusterStore
                && ClusterPluginSet.MODE == ClusterPluginSet.Mode.cluster;
    }

    /** Whether plugin changes are refused here ({@code fixed} mode): the notice says so instead. */
    public boolean isFixed() {
        return PersistenceStores.get() instanceof ClusterStore && ClusterPluginSet.MODE == ClusterPluginSet.Mode.fixed;
    }

    public ClusterView getCluster() {
        return ExtensionList.lookupSingleton(ClusterView.class);
    }
}
