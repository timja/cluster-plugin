package io.jenkins.plugins.cluster.builds;

import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Run;
import hudson.model.TaskListener;
import io.jenkins.plugins.cluster.builds.BuildRecords.BuildId;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Retries {@link BuildRecords#complete} for builds whose publication failed. */
@Extension
public final class UnpublishedRetry extends AsyncPeriodicWork {

    /** As the class this was part of: logging configured for it applies. */
    private static final Logger LOGGER = Logger.getLogger(BuildRecords.class.getName());

    public UnpublishedRetry() {
        super("Completed builds not yet published");
    }

    @Override
    public long getRecurrencePeriod() {
        return TimeUnit.SECONDS.toMillis(15);
    }

    @Override
    protected void execute(TaskListener listener) {
        BuildRecords.listAgain();
        for (RunningBuilds.Placeholder p : List.copyOf(BuildRecords.unfinished)) {
            try {
                RunningBuilds.finished(p);
                BuildRecords.unfinished.remove(p);
            } catch (IOException | RuntimeException x) {
                LOGGER.log(Level.FINE, "Still cannot remove the placeholder " + p.getKey() + " of a deleted build", x);
            }
        }
        for (BuildId id : List.copyOf(BuildRecords.unpublished)) {
            Run<?, ?> run = id.resolve();
            if (run == null || RunningBuilds.wasLost(run)) {
                BuildRecords.unpublished.remove(id);
                continue;
            }
            LOGGER.info(() -> "Retrying the publication of " + run);
            BuildRecords.complete(run);
        }
    }

    @Override
    protected Level getNormalLoggingLevel() {
        return Level.FINE;
    }
}
