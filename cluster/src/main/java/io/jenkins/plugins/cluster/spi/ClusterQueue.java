package io.jenkins.plugins.cluster.spi;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

/**
 * The build queue shared by all controllers of a cluster ({@link ClusterStore#queue}).
 * Each controller mirrors it into its local {@link hudson.model.Queue} (the plugin's shared queue, through
 * {@code Queue.scheduleShared}/{@code updateShared}); an item may only start on the controller which
 * {@linkplain #claim claims} it, and the others {@linkplain hudson.model.Queue#handOff hand off} their copy.
 *
 * <p>Records of items which started or were cancelled are kept only so that controllers which have not applied that
 * yet learn it: a store should expire them some time later, at the soonest five minutes after (as long as the local
 * queue remembers items that left), so that they do not accumulate. Waiting and claimed records must never expire: an
 * item with no record may start anywhere.
 * That retention also bounds how long a controller can be down and still recognise items of its saved queue which ran
 * elsewhere meanwhile (it publishes again an item it finds no record of): a store should keep finished records much
 * longer than the minimum, such as a day.
 *
 * @since TODO
 */
public interface ClusterQueue {

    enum State {
        WAITING,
        CLAIMED,
        STARTED,
        CANCELLED
    }

    /**
     * An item in the shared queue.
     *
     * @param id unique across the cluster; also the local queue item id on every controller
     * @param itemUuid the {@linkplain hudson.model.Item#getItemUuid identity} of the job: what the item is found by
     * @param task full name of the job when scheduled, for display only (it may have been renamed since)
     * @param dedupKey items with the same key fold into one while waiting
     * @param actions persistable actions of the item, as XML
     * @param dueAt when the quiet period ends, in milliseconds since the epoch
     * @param origin the controller which accepted the item
     * @param pinnedTo if set, the only origin which may claim the item
     * @param claimedBy the origin which claimed it, if claimed or started
     * @param buildNumber once started
     */
    record Record(
            long id,
            @NonNull String itemUuid,
            @NonNull String task,
            @NonNull String dedupKey,
            @NonNull String actions,
            long dueAt,
            @NonNull State state,
            @NonNull String origin,
            @CheckForNull String pinnedTo,
            @CheckForNull String claimedBy,
            int buildNumber) {}

    /** A new unique id. */
    long nextId() throws IOException;

    /**
     * New unique ids, taken ahead of need (those never used are skipped numbers); stores should take them in one call.
     *
     * @param n how many, at least one
     */
    @NonNull
    default long[] nextIds(int n) throws IOException {
        long[] ids = new long[n];
        for (int i = 0; i < n; i++) {
            ids[i] = nextId();
        }
        return ids;
    }

    /**
     * Adds an item, unless an item with the same dedup key is waiting already.
     *
     * @return the stored record: the new one, or the existing one this item should fold into
     */
    @NonNull
    Record publish(@NonNull Record item) throws IOException;

    /**
     * Updates the actions and due time of a waiting item if its actions are still {@code expectedActions}: a fold
     * merges its causes into what it read, and two folds at once must not lose one another's. Stores must compare and
     * update atomically. The due time only moves earlier.
     *
     * @param pinTo if not null, pins the item to this origin, unless it is pinned already (a request folded into it
     *     must run where it was made)
     * @return false if the item is not waiting, or its actions changed meanwhile (read it again and merge again)
     */
    boolean update(
            long id, @NonNull String expectedActions, @NonNull String actions, long dueAt, @CheckForNull String pinTo)
            throws IOException;

    /** {@link #update(long, String, String, long, String)}, pinning nothing. */
    default boolean update(long id, @NonNull String expectedActions, @NonNull String actions, long dueAt)
            throws IOException {
        return update(id, expectedActions, actions, dueAt, null);
    }

    /**
     * Claims a waiting item for this controller, if it is still pinned as the claimer saw it: a request folded into it
     * meanwhile may have pinned it to another controller (see {@link #update(long, String, String, long, String)}).
     * Stores must compare and claim atomically.
     *
     * @param expectedPin the {@link Record#pinnedTo} the claimer read, null if it was not pinned
     * @return true if this controller now has it, false if it was claimed, started or cancelled elsewhere, or is now
     *     pinned otherwise
     */
    boolean claim(long id, @CheckForNull String expectedPin) throws IOException;

    /** {@link #claim(long, String)}, expecting the pin the item has when read now; for callers which checked nothing. */
    default boolean claim(long id) throws IOException {
        Record r = get(id);
        return r != null && claim(id, r.pinnedTo());
    }

    /** Records that an item claimed here started as the given build. */
    void started(long id, int buildNumber) throws IOException;

    /**
     * Cancels an item which has not started: a waiting one, or one claimed by this controller (whose copy here was
     * cancelled before it could start).
     *
     * @return false if it was neither
     */
    boolean cancel(long id) throws IOException;

    @CheckForNull
    Record get(long id) throws IOException;

    /** Items waiting or claimed, for mirroring at startup. */
    @NonNull
    List<Record> pending() throws IOException;

    /**
     * Receives every change of any record, in order, including this controller's own.
     * Listeners should not throw; one which does is logged, and neither stops the others getting the change nor makes
     * the change fail.
     */
    @NonNull
    AutoCloseable subscribe(@NonNull Consumer<Record> listener);
}
