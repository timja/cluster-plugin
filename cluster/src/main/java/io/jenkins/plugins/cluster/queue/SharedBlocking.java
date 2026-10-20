package io.jenkins.plugins.cluster.queue;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.model.Job;
import hudson.model.Queue;
import hudson.model.queue.CauseOfBlockage;
import hudson.model.queue.QueueTaskDispatcher;
import io.jenkins.plugins.cluster.builds.RunningBuilds;
import io.jenkins.plugins.cluster.spi.ClusterStore;
import java.util.List;
import java.util.Locale;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Extends "block build when upstream/downstream project is building" to builds running on other controllers (their
 * placeholders, {@link RunningBuilds}), saying where; builds queued anywhere are already in this controller's queue
 * ({@link SharedQueue}). Answers from what is cached (under the queue lock): a project whose builds elsewhere are not
 * known yet holds the item until they are.
 */
@Restricted(NoExternalUse.class)
@Extension
public final class SharedBlocking extends QueueTaskDispatcher {

    @CheckForNull
    @Override
    public CauseOfBlockage canRun(Queue.Item item) {
        if (!(item.task instanceof AbstractProject<?, ?> p) || ClusterStore.get() == null) {
            return null;
        }
        if (p.blockBuildWhenUpstreamBuilding()) {
            CauseOfBlockage cause = runningElsewhere(p, p.getTransitiveUpstreamProjects(), "Upstream");
            if (cause != null) {
                return cause;
            }
        }
        if (p.blockBuildWhenDownstreamBuilding()) {
            return runningElsewhere(p, p.getTransitiveDownstreamProjects(), "Downstream");
        }
        return null;
    }

    @CheckForNull
    @SuppressWarnings("rawtypes")
    private static CauseOfBlockage runningElsewhere(
            AbstractProject<?, ?> queued, Iterable<AbstractProject> jobs, String which) {
        for (Job<?, ?> job : (Iterable<Job<?, ?>>) (Iterable) jobs) {
            if (job == queued) {
                continue; // in a cycle: as here, its own builds do not block it (one at a time is the exclusive lease's
                // job)
            }
            List<RunningBuilds.Placeholder> running = RunningBuilds.elsewhereCached(job);
            if (running == null) {
                return new SharedQueue.Blocked("Checking whether " + which.toLowerCase(Locale.ENGLISH) + " project "
                        + job.getFullDisplayName() + " is building on another controller");
            }
            if (!running.isEmpty()) {
                RunningBuilds.Placeholder b = running.get(0);
                return new SharedQueue.Blocked(which + " project " + job.getFullDisplayName() + " is building on "
                        + b.getController() + " (" + b.getDisplayName() + ")");
            }
        }
        return null;
    }
}
