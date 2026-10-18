package io.jenkins.plugins.cluster.builds;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Job;
import java.util.ArrayList;
import java.util.List;
import jenkins.model.ExternalBuilds;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Builds of a job running on other controllers, as their placeholders say ({@link RunningBuilds}). Listed from what is
 * cached ({@link #running}: a job whose placeholders are not cached yet has them loaded in the background, and lists
 * none until then); but whether the job is building, and a build by number, are answered from the store when nothing
 * is cached yet (one round trip per job, which is then kept up to date), so that a decision such as refusing a rename
 * never takes a build elsewhere for none.
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

    @CheckForNull
    @Override
    public BuildElsewhere get(@NonNull Job<?, ?> job, int number) {
        if (!BuildRecords.isShared(job)) {
            return null;
        }
        for (RunningBuilds.Placeholder p : RunningBuilds.elsewhere(job)) {
            if (p.getNumber() == number) {
                return new BuildElsewhere(job, p);
            }
        }
        return null;
    }

    /** Also while a build completed here is not published yet: for the other controllers it is still running. */
    @Override
    public boolean isBuilding(@NonNull Job<?, ?> job) {
        return BuildRecords.isShared(job)
                && (BuildRecords.hasUnpublished(job)
                        || !RunningBuilds.elsewhere(job).isEmpty());
    }
}
