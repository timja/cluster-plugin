package io.jenkins.plugins.cluster.builds;

import hudson.Extension;
import hudson.XmlFile;
import hudson.model.Run;
import hudson.model.Saveable;
import hudson.model.listeners.SaveableListener;
import io.jenkins.plugins.cluster.builds.BuildRecords.Manifest;
import io.jenkins.plugins.cluster.spi.ClusterStore;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.persistence.PersistenceStores;

@Extension
public final class BuildRecordRepublisher extends SaveableListener {

    /** As the class this was part of: logging configured for it applies. */
    private static final Logger LOGGER = Logger.getLogger(BuildRecords.class.getName());

    @Override
    public void onChange(Saveable o, XmlFile file) {
        if (!(o instanceof Run<?, ?> run)
                || PersistenceStores.isApplyingRemoteChange()
                || !BuildRecords.isShared(run.getParent())) {
            return;
        }
        if (RunningBuilds.wasDiscarded(run)) {
            // a copy of a build lost to another controller, discarded here but still referenced, saved its
            // build.xml
            // again: never published, and dropped again, as it would stand for the new owner's record here
            RunningBuilds.discardAgain(run);
            return;
        }
        if (run.isLogUpdated() || BuildRecords.LOADING.get() != null || RunningBuilds.wasLost(run)) {
            return;
        }
        try {
            String key = BuildRecords.recordKeyOf(run);
            ClusterStore store = BuildRecords.store();
            if (key != null && store != null && Manifest.filesAdded(run.getRootDir()) && store.read(key) != null) {
                BuildRecords.publish(run, true, true); // files were added too: the record alone would leave them out
            } else {
                BuildRecords.republish(run);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Failed to republish " + run, e);
        }
    }
}
