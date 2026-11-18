package io.jenkins.plugins.cluster.builds;

import hudson.Extension;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.listeners.ItemListener;
import io.jenkins.plugins.cluster.spi.Keys;
import jenkins.persistence.PersistenceStores;
import jenkins.util.Timer;

/**
 * Removes everything stored about a job deleted here (not when applying a deletion made elsewhere: the controller
 * making it does this): its build records, permalinks, number sequence, placeholders and blobs. A job made again with
 * the name has another identity, and nothing of this. Done off the deleting thread; what fails is left to
 * {@link StoredOrphans}.
 */
@Extension
public final class ItemStoreCleanup extends ItemListener {
    @Override
    public void onDeleted(Item item) {
        // folders delete their children one by one, so each job gets here
        if (!(item instanceof Job<?, ?> job)
                || PersistenceStores.isApplyingRemoteChange()
                || !BuildRecords.isShared(job)) {
            return;
        }
        String prefix = Keys.item(job);
        String blobPrefix = Keys.blobs(job);
        String name = job.getFullName();
        Timer.get().submit(() -> BuildRecords.deleteStored(prefix, blobPrefix, name));
    }
}
