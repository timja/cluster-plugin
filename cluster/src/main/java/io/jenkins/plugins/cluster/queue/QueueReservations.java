package io.jenkins.plugins.cluster.queue;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.model.Queue;
import hudson.model.queue.SubTask;
import hudson.slaves.Cloud;
import hudson.slaves.CloudProvisioningListener;
import hudson.slaves.NodeProvisioner;
import io.jenkins.plugins.cluster.agents.LaunchLease;
import io.jenkins.plugins.cluster.spi.ClusterQueue;
import io.jenkins.plugins.cluster.spi.ClusterStore;
import io.jenkins.plugins.cluster.spi.Keys;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.persistence.PersistenceStore;

/**
 * Items whose controller is starting cloud agents for them. With the shared queue, the controller an item was
 * scheduled on provisions for it (it alone counts it as demand), but starting an agent can take longer than the
 * origin's head start; without a reservation, another controller with a free executor would take the item, and the
 * agent started for it would have nothing to do. A reservation is a store entry ({@link Keys#queueReservation})
 * holding the controller's origin; others wait for it while that controller is alive, for at most
 * {@link SharedQueue#RESERVATION_MILLIS}. It is a hint, not a fence: a controller claiming the item at the moment it is reserved
 * may still take it, and the agent started for it then idles until its cloud's retention removes it.
 */
@Extension
public final class QueueReservations extends CloudProvisioningListener {

    /** As the class this was part of: logging configured for it applies. */
    private static final Logger LOGGER = Logger.getLogger(SharedQueue.class.getName());

    /** The origin of the controller holding a current reservation of an item, or null. */
    @CheckForNull
    static String holder(long id) throws IOException {
        ClusterStore store = ClusterStore.get();
        PersistenceStore.Entry e = store == null ? null : store.read(Keys.queueReservation(id));
        if (e == null) {
            return null;
        }
        String[] parts = new String(e.content(), StandardCharsets.UTF_8).split(" ", 2);
        try {
            if (parts.length < 2
                    || System.currentTimeMillis() - Long.parseLong(parts[1].trim()) > SharedQueue.RESERVATION_MILLIS) {
                return null;
            }
        } catch (NumberFormatException x) {
            return null;
        }
        return parts[0];
    }

    static void release(long id) {
        ClusterStore store = ClusterStore.get();
        if (store != null) {
            try {
                store.delete(Keys.queueReservation(id), PersistenceStore.ANY);
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.FINE, e, () -> "Could not release the reservation of queue item " + id);
            }
        }
    }

    @Override
    public void onStarted(Cloud cloud, hudson.model.Label label, Collection<NodeProvisioner.PlannedNode> plannedNodes) {
        ClusterQueue cq = SharedQueue.queue();
        ClusterStore store = ClusterStore.get();
        if (cq == null || store == null) {
            return;
        }
        int executors = plannedNodes.stream().mapToInt(p -> p.numExecutors).sum();
        String me = SharedQueue.origin();
        for (Queue.BuildableItem bi : Queue.getInstance().getBuildableItems()) {
            if (executors <= 0) {
                break;
            }
            // demand is counted per part of the task (as LoadStatistics does): any part needing this label
            int needing = 0;
            for (SubTask st : bi.task.getSubTasks()) {
                if (Objects.equals(label, bi.getAssignedLabelFor(st))) {
                    needing++;
                }
            }
            if (!SharedQueue.shared(bi.task) || needing == 0) {
                continue;
            }
            try {
                ClusterQueue.Record r = cq.get(bi.getId());
                if (r == null
                        || r.claimedBy() != null
                        || !SharedQueue.provisionsHere(r, System.currentTimeMillis(), LaunchLease.holdersOf(label))) {
                    continue;
                }
                String reservedBy = holder(bi.getId());
                if (reservedBy != null && (me.equals(reservedBy) || !SharedQueue.gone(reservedBy))) {
                    continue;
                }
                store.write(
                        Keys.queueReservation(bi.getId()),
                        (me + " " + System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8),
                        PersistenceStore.ANY);
                executors -= needing;
                LOGGER.fine(() -> "Reserved " + bi + " while starting an agent for it on " + cloud.name);
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.FINE, e, () -> "Could not reserve " + bi);
            }
        }
    }
}
