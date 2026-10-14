package io.jenkins.plugins.cluster;

import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.UpdateCenter;
import java.io.File;
import java.io.IOException;
import jenkins.model.Jenkins;

/**
 * Shares every plugin the update center installs here (from an update site or an upload, dependencies included) with
 * the cluster ({@link ClusterPlugins#installed}). The update center has no listener for completed installs, but every
 * one of them moves its verified download into place through {@link UpdateCenter.UpdateCenterConfiguration#install}.
 */
public final class ClusterUpdateCenterConfiguration extends UpdateCenter.UpdateCenterConfiguration {

    @Initializer(after = InitMilestone.PLUGINS_STARTED)
    public static void register() {
        if (ClusterPluginSet.MODE == ClusterPluginSet.Mode.cluster) {
            Jenkins.get().getUpdateCenter().configure(new ClusterUpdateCenterConfiguration());
        }
    }

    @Override
    public void install(UpdateCenter.DownloadJob job, File src, File dst) throws IOException {
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
