package io.jenkins.plugins.cluster.builds;

import hudson.Extension;
import hudson.model.Run;
import hudson.model.listeners.RunListener;
import io.jenkins.plugins.cluster.builds.BuildRecords.BuildId;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.persistence.PersistenceStores;

@Extension
public final class BuildRecordPublisher extends RunListener<Run<?, ?>> {

    /** As the class this was part of: logging configured for it applies. */
    private static final Logger LOGGER = Logger.getLogger(BuildRecords.class.getName());

    @Override
    public void onFinalized(Run<?, ?> run) {
        if (PersistenceStores.isApplyingRemoteChange() || !BuildRecords.isShared(run.getParent())) {
            return;
        }
        if (RunningBuilds.wasLost(run) || !RunningBuilds.owns(run)) {
            // another controller has taken it over and will publish it
            LOGGER.warning(() -> "Not publishing " + run + ": it is owned by another controller");
            RunningBuilds.lost(run); // fenced until discarded: a save of this copy meanwhile is not republished
            RunningBuilds.discard(run);
            return;
        }
        BuildRecords.complete(run);
    }

    @Override
    public void onDeleted(Run<?, ?> run) {
        if (PersistenceStores.isApplyingRemoteChange() || !BuildRecords.isShared(run.getParent())) {
            return;
        }
        try {
            BuildRecords.delete(run);
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Failed to delete stored " + run, e);
        }
        // completed here and not published yet (the store was unreachable): no retry publishes it now, so its
        // placeholder goes too, or it would show as running elsewhere, and be closed into a record once this
        // controller is gone
        BuildRecords.unpublished.remove(BuildId.of(run));
        RunningBuilds.Placeholder placeholder = RunningBuilds.ownedPlaceholder(run);
        if (placeholder != null && !run.isLogUpdated()) {
            try {
                RunningBuilds.finished(placeholder);
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.WARNING, "Failed to remove the placeholder of deleted " + run + "; will retry", e);
                BuildRecords.unfinished.add(placeholder);
            }
        }
    }
}
