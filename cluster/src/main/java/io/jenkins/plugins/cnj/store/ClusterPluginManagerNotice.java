package io.jenkins.plugins.cnj.store;

import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.PageDecorator;
import jenkins.persistence.PersistenceStores;

/**
 * On the plugin manager's pages (through core's {@code PluginManager/notices.jelly}), says that changes made there
 * apply to every controller of the cluster, and shows each controller's progress applying them (Target 15).
 */
@Extension
public final class ClusterPluginManagerNotice extends PageDecorator {

    /** Whether to show the notice: a cluster store, with plugin changes shared (not {@code fixed} or {@code local}). */
    public boolean isActive() {
        return PersistenceStores.get() instanceof ClusterStore && ClusterPluginSet.MODE == ClusterPluginSet.Mode.cluster;
    }

    /** Whether plugin changes are refused here ({@code fixed} mode): the notice says so instead. */
    public boolean isFixed() {
        return PersistenceStores.get() instanceof ClusterStore && ClusterPluginSet.MODE == ClusterPluginSet.Mode.fixed;
    }

    public ClusterView getCluster() {
        return ExtensionList.lookupSingleton(ClusterView.class);
    }
}
