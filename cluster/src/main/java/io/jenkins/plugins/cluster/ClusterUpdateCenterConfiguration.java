package io.jenkins.plugins.cluster;

import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.UpdateCenter;
import java.io.File;
import java.io.IOException;
import java.util.logging.Logger;
import jenkins.model.Jenkins;

/**
 * Shares every plugin the update center installs here (from an update site or an upload, dependencies included) with
 * the cluster ({@link ClusterPlugins#installed}). The update center has no listener for completed installs, but every
 * one of them moves its verified download into place through {@link UpdateCenter.UpdateCenterConfiguration#install}.
 */
public final class ClusterUpdateCenterConfiguration extends UpdateCenter.UpdateCenterConfiguration {

    private static final Logger LOGGER = Logger.getLogger(ClusterUpdateCenterConfiguration.class.getName());

    @Initializer(after = InitMilestone.PLUGINS_STARTED)
    public static void register() {
        if (ClusterPluginSet.MODE == ClusterPluginSet.Mode.cluster) {
            Jenkins.get().getUpdateCenter().configure(new ClusterUpdateCenterConfiguration());
        }
    }

    /**
     * Refuses a download the cluster cannot take (Target 15): a cluster plugin, which is part of the image (also as a
     * dependency of another plugin, or an upload), or a downgrade of what the cluster has. The install job fails, saying why.
     */
    @Override
    public void postValidate(UpdateCenter.DownloadJob job, File src) throws IOException {
        super.postValidate(job, src);
        if (job instanceof UpdateCenter.InstallationJob) {
            String refusal = ClusterPlugins.archiveRefusal(src);
            if (refusal != null) {
                LOGGER.info(() -> "Refused the install of " + job.getName() + ": " + refusal);
                throw new IOException(refusal);
            }
        }
    }

    @Override
    public void install(UpdateCenter.DownloadJob job, File src, File dst) throws IOException {
        if (job instanceof UpdateCenter.PluginDowngradeJob && ClusterPlugins.active()) {
            // restores a backup only this controller has (the plugin manager's downgrade button)
            throw new IOException(ClusterPluginSet.DOWNGRADE);
        }
        super.install(job, src, dst);
        if (job instanceof UpdateCenter.InstallationJob) { // not core upgrades
            ClusterPlugins.installed(dst);
        }
    }

    @Override
    public void upgrade(UpdateCenter.DownloadJob job, File src, File dst) throws IOException {
        super.upgrade(job, src, dst);
        if (job instanceof UpdateCenter.InstallationJob) {
            ClusterPlugins.installed(dst);
        }
    }
}
