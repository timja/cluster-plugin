package io.jenkins.plugins.cluster.builds;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Job;
import hudson.model.Run;
import java.io.File;
import java.io.IOException;
import java.util.Set;
import jenkins.model.lazy.BuildRecordSource;
import jenkins.util.ThrowingCallable;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Builds of a job made by other controllers ({@link BuildRecords}): listed with the local ones, and materialized from the
 * stores when first loaded. Without a cluster store, nothing (as without this source).
 */
@Restricted(NoExternalUse.class)
@Extension
public final class ClusterBuildRecordSource implements BuildRecordSource {

    @NonNull
    @Override
    public Set<Integer> numbers(@NonNull Job<?, ?> job) {
        return BuildRecords.isShared(job) ? BuildRecords.storedNumbers(job) : Set.of();
    }

    @Override
    public <R extends Run<?, ?>> R retrieve(
            @NonNull Job<?, ?> job, @NonNull File dir, @NonNull ThrowingCallable<R, IOException> load)
            throws IOException {
        if (!BuildRecords.isShared(job)) {
            return load.call();
        }
        if (!new File(dir, BuildRecords.RECORD).exists()) {
            // a build made by another controller, not yet seen here
            BuildRecords.materialize(job, dir);
        }
        R run = BuildRecords.loading(load);
        if (run != null) { // else not loadable (e.g. its snapshot is missing): core leaves it unloaded
            RunningBuilds.loaded(run);
        }
        return run;
    }

    @Override
    public boolean sharesNumbers(@NonNull Job<?, ?> job) {
        return BuildRecords.isShared(job);
    }
}
