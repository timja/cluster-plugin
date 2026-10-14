package io.jenkins.plugins.cnj.store;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.AbortException;
import hudson.Extension;
import java.io.File;
import java.util.logging.Logger;
import jenkins.persistence.PersistenceStores;
import jenkins.plugins.PluginArchiveListener;

/**
 * Plugins installed with the CLI's {@code install-plugin} from a file, URL or standard input (which bypasses the
 * update center) are treated as the plugin manager's installs are (Target 15): refused where those are
 * ({@code fixed} mode, the store plugin itself), otherwise recorded in the cluster's plugin set, so that every
 * controller gets them.
 */
@Extension
public final class ClusterCliPlugins implements PluginArchiveListener {

    private static final Logger LOGGER = Logger.getLogger(ClusterCliPlugins.class.getName());

    @Override
    public void beforeInstall(@NonNull String shortName, @NonNull File archive) throws AbortException {
        if (!(PersistenceStores.get() instanceof ClusterStore)) {
            return;
        }
        String refusal = ClusterPluginSet.refusal(ClusterPluginSet.MODE, new ClusterPluginSet.Change("install", shortName), ClusterPlugins.active());
        if (refusal != null) {
            LOGGER.info(() -> "Refused install-plugin " + shortName + ": " + refusal);
            throw new AbortException(refusal);
        }
    }

    @Override
    public void installed(@NonNull String shortName, @NonNull File archive) {
        if (ClusterPluginSet.MODE == ClusterPluginSet.Mode.cluster) {
            ClusterPlugins.installed(archive); // logs and carries on if it cannot share it
        }
    }
}
