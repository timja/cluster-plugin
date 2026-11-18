package io.jenkins.plugins.cluster.locks;

import hudson.PluginWrapper;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Whether lockable-resources is installed. The extensions of this package that it does not name in their signature (a
 * listener, a periodic task) are loaded without it; they must check this before they touch a class that names one of
 * its, or every call fails with {@link NoClassDefFoundError}.
 */
@Restricted(NoExternalUse.class)
final class LockableResourcesPlugin {

    private LockableResourcesPlugin() {}

    static boolean isInstalled() {
        Jenkins j = Jenkins.getInstanceOrNull();
        PluginWrapper p = j == null ? null : j.getPluginManager().getPlugin("lockable-resources");
        return p != null && p.isActive();
    }
}
