package io.jenkins.plugins.cluster.builds;

import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.TaskListener;
import io.jenkins.plugins.cluster.spi.ClusterStore;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

@Extension
public final class RunningBuildsCleanup extends AsyncPeriodicWork {

    public RunningBuildsCleanup() {
        super("Stale running build placeholders");
    }

    @Override
    public long getRecurrencePeriod() {
        return TimeUnit.SECONDS.toMillis(RunningBuilds.CLEANUP_PERIOD);
    }

    @Override
    protected void execute(TaskListener listener) throws IOException {
        if (ClusterStore.get() != null) {
            RunningBuilds.registerPending();
            ExclusiveLeases.releasePending();
            RunningBuilds.checkOwnership();
            RunningBuilds.removeStale();
        }
    }

    @Override
    protected Level getNormalLoggingLevel() {
        return Level.FINE;
    }
}
