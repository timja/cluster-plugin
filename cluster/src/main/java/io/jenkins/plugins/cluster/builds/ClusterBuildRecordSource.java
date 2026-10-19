package io.jenkins.plugins.cluster.builds;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.RunMap;
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
            switch (BuildRecords.materialize(job, dir, true)) {
                case WAITED_RESET -> {
                    return throughIndex(job, dir);
                }
                case WAITED -> {
                    R meanwhile = loadedMeanwhile(job, dir);
                    if (meanwhile != null) {
                        return meanwhile;
                    }
                }
                default -> {}
            }
        }
        R run = BuildRecords.loading(load);
        if (run != null) { // else not loadable (e.g. its snapshot is missing): core leaves it unloaded
            RunningBuilds.loaded(run);
        }
        return run;
    }

    /**
     * The build loaded through its job's build index, after a fetch waited for with the index lock released, during which
     * the build's index entry was reset (a load under the queue's lock gave up on it): loading it for the entry this load
     * is for, no longer the index's, would make two instances of one build. Loaded, if not yet, by core's {@link #retrieve}
     * of the current entry, which finds the build's record in place.
     */
    @SuppressWarnings("unchecked")
    private static <R extends Run<?, ?>> R throughIndex(Job<?, ?> job, File dir) {
        return (R) job.getBuildByNumber(Integer.parseInt(dir.getName()));
    }

    /**
     * The instance another load of the build made while this one waited for its fetch with the index lock released (it
     * waited for the same fetch), if any: this load then returns it rather than make a second one.
     */
    @SuppressWarnings("unchecked")
    @CheckForNull
    private static <R extends Run<?, ?>> R loadedMeanwhile(Job<?, ?> job, File dir) {
        RunMap<?> runs = BuildRecords.runsOf(job);
        return runs == null ? null : (R) runs.getLoadedBuilds().get(Integer.parseInt(dir.getName()));
    }

    @Override
    public boolean sharesNumbers(@NonNull Job<?, ?> job) {
        return BuildRecords.isShared(job);
    }
}
