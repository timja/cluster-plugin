package io.jenkins.plugins.cluster.builds;

import hudson.Extension;
import io.jenkins.plugins.cluster.spi.Resync;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/** Catches up with the store's build records of the jobs whose build index is loaded here. */
@Extension
@Restricted(NoExternalUse.class)
public final class BuildRecordsCatchUp extends Resync {
    @Override
    public void catchUp() {
        BuildRecords.catchUp();
    }
}
