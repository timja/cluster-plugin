package io.jenkins.plugins.cluster;

import hudson.Extension;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.AdministrativeMonitor;
import java.util.logging.Logger;
import jenkins.model.ArtifactManagerConfiguration;
import jenkins.persistence.PersistenceStores;

/**
 * A cluster needs an artifact manager keeping artifacts and stashes in shared storage (Target 15). Without one, what
 * builds archive or stash stays in the build directory of the controller running them: a build adopted by another
 * controller loses its stashes, and a fresh home lacks the artifacts of builds that ran before. The reference setup is
 * azure-artifact-manager or artifact-manager-s3, matching the blob store plugin. Shown, and logged at startup, while none is configured.
 */
@Extension
public final class ArtifactManagerRequired extends AdministrativeMonitor {

    private static final Logger LOGGER = Logger.getLogger(ArtifactManagerRequired.class.getName());

    static boolean missing() {
        return PersistenceStores.get() != null
                && ArtifactManagerConfiguration.get().getArtifactManagerFactories().isEmpty();
    }

    @Override
    public String getDisplayName() {
        return "An artifact manager is required in a cluster";
    }

    @Override
    public boolean isActivated() {
        return missing();
    }

    @Initializer(after = InitMilestone.JOB_CONFIG_ADAPTED) // not after COMPLETED: that keeps the reactor from reaching it (JENKINS-37759)
    public static void check() {
        if (missing()) {
            LOGGER.severe("No artifact manager is configured: artifacts and stashes stay on the controller that made them,"
                    + " so builds adopted by another controller lose their stashes, and fresh controllers lack earlier"
                    + " builds' artifacts. Configure one keeping them in shared storage (e.g. azure-artifact-manager or artifact-manager-s3).");
        }
    }
}
