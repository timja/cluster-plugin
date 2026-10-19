package io.jenkins.plugins.cluster.queue;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Job;

/** Builds completed elsewhere complete the futures of the items they were the builds of. */
@Extension
public final class RecordedElsewhere implements io.jenkins.plugins.cluster.builds.BuildRecords.RecordListener {
    @Override
    public void recordedElsewhere(@NonNull Job<?, ?> job, int number) {
        HandOffs.recorded(job, number);
    }
}
