package io.jenkins.plugins.cluster.queue;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import io.jenkins.plugins.cluster.builds.RunningBuilds;
import io.jenkins.plugins.cluster.queue.SharedQueue.ClaimLost;
import io.jenkins.plugins.cluster.spi.ClusterQueue;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.util.Timer;

@Extension
public final class QueueStarts extends RunListener<Run<?, ?>> {

    /** As the class this was part of: logging configured for it applies. */
    private static final Logger LOGGER = Logger.getLogger(SharedQueue.class.getName());

    @Override
    public void onStarted(Run<?, ?> run, TaskListener listener) {
        ClusterQueue cq = SharedQueue.queue();
        long id = run.getQueueId();
        if (cq == null || id <= 0 || !(run.getParent() instanceof Queue.Task task) || !SharedQueue.shared(task)) {
            return;
        }
        HandOffs.startedHere(id, run);
        recordStart(cq, run, 0);
    }

    @Override
    public void onFinalized(Run<?, ?> run) {
        long id = run.getQueueId();
        // not with a copy stopped here because another controller took the build over: the future follows the
        // build there, and completes with the record it publishes
        if (id > 0 && SharedQueue.queue() != null && RunningBuilds.owns(run)) {
            HandOffs.completedHere(id, run);
        }
    }

    /**
     * Marks the item started, retrying while the store is unreachable: an item left claimed would be put back into
     * the queue (and built again) if this controller later went away.
     */
    private static void recordStart(ClusterQueue cq, Run<?, ?> run, int attempt) {
        long id = run.getQueueId();
        int number = run.getNumber();
        try {
            ClusterQueue.Record r = cq.get(id);
            if (r != null && !cq.started(id, number)) {
                ClusterQueue.Record now = cq.get(id);
                if (now == null || now.state() != ClusterQueue.State.STARTED || now.buildNumber() != number) {
                    claimLost(run, now);
                } // else this very build, recorded by the controller that started it (adopted here since, say)
            }
            QueueReservations.release(id);
        } catch (IOException | RuntimeException e) {
            if (attempt < 30) {
                LOGGER.log(Level.INFO, "Failed to record that queue item " + id + " started; retrying", e);
                Timer.get()
                        .schedule(
                                () -> recordStart(cq, run, attempt + 1),
                                Math.min(30, 1L << Math.min(attempt, 5)),
                                TimeUnit.SECONDS);
            } else {
                LOGGER.log(Level.WARNING, "Failed to record that queue item " + id + " started", e);
            }
        }
    }

    /**
     * Stops a build which started here for a queue item this controller no longer had claimed (its claim lapsed while
     * it was paused, say): another controller builds that item, or will.
     */
    private static void claimLost(Run<?, ?> run, @CheckForNull ClusterQueue.Record now) {
        LOGGER.warning(() -> run + " started for queue item " + run.getQueueId()
                + ", which this controller no longer had claimed ("
                + (now == null ? "gone" : now.state() + (now.claimedBy() == null ? "" : " by " + now.claimedBy()))
                + "); stopping it");
        hudson.model.Executor executor = run.getExecutor();
        if (executor != null) {
            executor.interrupt(hudson.model.Result.ABORTED, new ClaimLost());
        }
    }
}
