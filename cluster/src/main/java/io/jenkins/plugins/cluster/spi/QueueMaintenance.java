package io.jenkins.plugins.cluster.spi;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Housekeeping of the shared queue ({@link ClusterQueue}). */
public interface QueueMaintenance {
    /** Puts items claimed by gone controllers back into the queue, and forgets old ones; how many were put back. */
    int reclaimAndPrune() throws IOException;

    /** Items recorded as started, recently, by controllers which are gone. */
    @NonNull
    List<StartedItem> startedByGone() throws IOException;

    /** Puts a started item back into the queue (its build never began), or cancels it if an equal one waits. */
    void requeueStarted(long id) throws IOException;

    /**
     * Cancels the started items of an item (it was deleted), by its {@linkplain hudson.model.Item#getItemUuid identity}.
     * A rename or move does not change the identity, so needs no call. The caller cancels those of the items inside a
     * deleted folder one by one.
     */
    void cancelStartedOf(@NonNull String itemUuid) throws IOException;

    /** How many items are in each state ({@code WAITING}, {@code CLAIMED}). */
    @NonNull
    Map<String, Integer> queueCounts() throws IOException;

    /** A queue item recorded as started, with the number of its build; {@code task} is the full name, for display. */
    record StartedItem(long id, String itemUuid, String task, int buildNumber) {}
}

