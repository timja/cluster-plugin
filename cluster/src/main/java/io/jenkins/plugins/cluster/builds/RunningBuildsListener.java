package io.jenkins.plugins.cluster.builds;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Job;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.cluster.builds.RunningBuilds.NotYet;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.lazy.LazyBuildMixIn;
import jenkins.persistence.ConflictException;
import jenkins.persistence.PersistenceStores;
import jenkins.util.Timer;

/**
 * Records builds as running, before other listeners hear of their start (a higher ordinal): a queue item recorded
 * started by a controller gone since is put back into the queue when its build has neither placeholder nor record,
 * so a controller pausing after recording the start but before this would wake to run the build beside the one
 * built again elsewhere.
 */
@Extension(ordinal = 1)
public final class RunningBuildsListener extends RunListener<Run<?, ?>> {

    /** As the class this was part of: logging configured for it applies. */
    private static final Logger LOGGER = Logger.getLogger(RunningBuilds.class.getName());

    /**
     * A build this controller was running before it restarted with its home (e.g. a safe restart to apply plugin
     * changes) is loaded, and a Pipeline then resumed, from that home: first take its lease over from the previous
     * incarnation, as an adopter would, so that no other controller adopts it too. If another controller holds it
     * already (it adopted the build meanwhile), the local copy is not loaded: that controller's is the build.
     */
    @Override
    public boolean allowLoad(@NonNull Job<?, ?> job, int buildNumber) {
        if (PersistenceStores.isApplyingRemoteChange() || !BuildRecords.isShared(job)) {
            return true;
        }
        try {
            return RunningBuilds.reclaim(job, buildNumber);
        } catch (NotYet e) {
            LOGGER.info(() -> "Not resuming a build after a restart yet: " + e.getMessage());
            reclaimLater(job, buildNumber);
            return false;
        } catch (IOException | RuntimeException e) {
            // not loaded (nor resumed) without its lease: another controller may run it; looked at again later
            LOGGER.log(
                    Level.WARNING,
                    "Could not reclaim " + job.getFullName() + " #" + buildNumber
                            + " after a restart; trying again later",
                    e);
            reclaimLater(job, buildNumber);
            return false;
        }
    }

    /**
     * Tries to load a build not loaded because it could not be reclaimed yet, so that a Pipeline is resumed without
     * waiting for someone to open it. Only that build: the others of the job, perhaps running, keep their instances.
     */
    private static void reclaimLater(Job<?, ?> job, int number) {
        Timer.get()
                .schedule(
                        () -> {
                            if (job instanceof LazyBuildMixIn.LazyLoadingJob<?, ?> lazy) {
                                try (ACLContext ctx = ACL.as2(ACL.SYSTEM2)) {
                                    lazy.getLazyBuildMixIn()
                                            ._getRuns()
                                            .recognizeNumber(number); // declined when listed: not indexed
                                    job.getBuildByNumber(number); // a load failing again schedules another
                                }
                            }
                        },
                        RECLAIM_RETRY_SECONDS,
                        TimeUnit.SECONDS);
    }

    /** How long a build not reclaimed after a restart waits before it is tried again. */
    static /* non-final for tests */ long RECLAIM_RETRY_SECONDS = 30;

    /**
     * Records the result in the placeholder as soon as a build completes, before its record is published: should
     * this controller die in between, the build is then closed with that result instead of resumed elsewhere.
     */
    @Override
    public void onCompleted(Run<?, ?> run, @NonNull TaskListener listener) {
        Result result = run.getResult();
        if (result == null || PersistenceStores.isApplyingRemoteChange() || RunningBuilds.owned(run) == null) {
            return;
        }
        try {
            RunningBuilds.update(run, Map.of(RunningBuilds.RESULT, result.toString()));
        } catch (ConflictException e) {
            LOGGER.fine(() -> run + " completed after another controller took it over");
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not record the result of " + run + " in its placeholder", e);
        }
    }

    @Override
    public void onStarted(Run<?, ?> run, TaskListener listener) {
        if (PersistenceStores.isApplyingRemoteChange()
                || !BuildRecords.isShared(run.getParent())
                || RunningBuilds.owned(run) != null) {
            return; // not shared, or adopted or reclaimed here (owned already)
        }
        try {
            RunningBuilds.started(run);
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Failed to record " + run + " as running; will retry", e);
            RunningBuilds.unregistered.add(run);
        }
    }
}
