package io.jenkins.plugins.cluster.builds;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Job;
import java.util.ArrayList;
import java.util.List;
import jenkins.model.ExternalBuilds;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Builds of a job running on other controllers, as their placeholders say ({@link RunningBuilds}), from what is cached:
 * a job whose placeholders are not cached yet has them loaded in the background, and shows none until then.
 */
@Restricted(NoExternalUse.class)
@Extension
public final class ClusterExternalBuilds implements ExternalBuilds {

    @NonNull
    @Override
    public List<BuildElsewhere> running(@NonNull Job<?, ?> job) {
        if (!BuildRecords.isShared(job)) {
            return List.of();
        }
        List<RunningBuilds.Placeholder> elsewhere = RunningBuilds.elsewhereCached(job);
        if (elsewhere == null || elsewhere.isEmpty()) {
            return List.of();
        }
        List<BuildElsewhere> builds = new ArrayList<>(elsewhere.size());
        for (RunningBuilds.Placeholder p : elsewhere) {
            builds.add(new BuildElsewhere(job, p));
        }
        return builds;
    }

    /** Also while a build completed here is not published yet: for the other controllers it is still running. */
    @Override
    public boolean isBuilding(@NonNull Job<?, ?> job) {
        return BuildRecords.isShared(job)
                && (BuildRecords.hasUnpublished(job) || !running(job).isEmpty());
    }
}
