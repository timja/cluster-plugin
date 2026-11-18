package io.jenkins.plugins.cluster.builds;

import hudson.Extension;
import io.jenkins.plugins.cluster.spi.Resync;
import java.io.IOException;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/** Catches up with the store's placeholders of the jobs cached here. */
@Extension
@Restricted(NoExternalUse.class)
public final class RunningBuildsCatchUp extends Resync {
    @Override
    public void catchUp() throws IOException {
        RunningBuilds.catchUp();
    }
}
