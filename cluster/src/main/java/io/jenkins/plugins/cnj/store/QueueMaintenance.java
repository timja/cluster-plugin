package io.jenkins.plugins.cnj.store;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Housekeeping of the shared queue ({@link jenkins.persistence.ClusterQueue}). */
public interface QueueMaintenance {
    /** Puts items claimed by gone controllers back into the queue, and forgets old ones; how many were put back. */
    int reclaimAndPrune() throws IOException;

    /** Items recorded as started, recently, by controllers which are gone. */
    @NonNull
    List<StartedItem> startedByGone() throws IOException;

    /** Puts a started item back into the queue (its build never began), or cancels it if an equal one waits. */
    void requeueStarted(long id) throws IOException;

    /** Cancels the started items of an item, and of the items inside it (it was deleted or renamed). */
    void cancelStartedOf(@NonNull String fullName) throws IOException;

    /** How many items are in each state ({@code WAITING}, {@code CLAIMED}). */
    @NonNull
    Map<String, Integer> queueCounts() throws IOException;

    /** A queue item recorded as started, with the number of its build. */
    record StartedItem(long id, String task, int buildNumber) {}
}

