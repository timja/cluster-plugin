package io.jenkins.plugins.cluster.spi;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.persistence.ConflictException;

/**
 * A {@link ClusterStore} held in memory, for tests. {@link #as} gives views with other origins, standing in for other
 * controllers sharing the store: they share entries, leases, the queue and membership. Events are delivered
 * synchronously, after the change, on the thread making it, in commit order.
 *
 * <p>Every origin a view was made for is alive until {@link #gone} says otherwise; liveness can always be judged.
 */
public class InMemoryClusterStore implements ClusterStore {

    private static final Logger LOGGER = Logger.getLogger(InMemoryClusterStore.class.getName());

    /** What all views share. */
    private static final class Shared {
        final Map<String, Entry> entries = new TreeMap<>();
        final List<Consumer<ChangeEvent>> listeners = new CopyOnWriteArrayList<>();
        final Queue<ChangeEvent> outbox = new ArrayDeque<>();
        final Object delivering = new Object();
        Thread draining;
        final Map<String, Lease> leases = new TreeMap<>();
        final Map<String, Lease> launchLeases = new TreeMap<>();
        final Map<Long, ClusterQueue.Record> queue = new TreeMap<>();
        final List<Consumer<ClusterQueue.Record>> queueListeners = new CopyOnWriteArrayList<>();
        final Queue<ClusterQueue.Record> queueOutbox = new ArrayDeque<>();
        final Object deliveringQueue = new Object();
        Thread drainingQueue;
        final Set<String> origins = new TreeSet<>();
        final Set<String> gone = new TreeSet<>();
        /** How many times each origin was taken for gone. */
        final Map<String, Long> lapses = new HashMap<>();

        final Map<String, String> claims = new HashMap<>();
        final Map<String, String> channels = new TreeMap<>();
        final Map<String, Map<String, Integer>> counters = new TreeMap<>();
        /** Whether change events are lost instead of delivered, as a store's listener can lose them. */
        volatile boolean losing;
        /** Versions, epochs and ids come from one counter, so that none repeats (nor, likely, one of an earlier store). */
        long clock = (System.currentTimeMillis() % 1_000_000) * 1_000_000;
    }

    private record Lease(String owner, long epoch) {}

    /**
     * For tests: told of some operations, and may fail them by throwing (an {@link IOException} too, as a store that
     * cannot be reached would): {@code children} once the children are listed, {@code release} before a build lease is
     * released.
     */
    public static volatile Hook hook;

    /** See {@link #hook}. */
    @FunctionalInterface
    public interface Hook {
        void on(@NonNull String operation, @NonNull String key) throws IOException;
    }

    private static void hook(String operation, String key) {
        Hook h = hook;
        if (h != null) {
            try {
                h.on(operation, key);
            } catch (IOException e) {
                throw InMemoryClusterStore.<RuntimeException>sneaky(e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T sneaky(Throwable t) throws T {
        throw (T) t;
    }

    private final Shared shared;
    private final String origin;

    public InMemoryClusterStore() {
        this(new Shared(), UUID.randomUUID().toString());
    }

    private InMemoryClusterStore(Shared shared, String origin) {
        this.shared = shared;
        this.origin = origin;
        synchronized (shared) {
            shared.origins.add(origin);
        }
    }

    /** A view of the same store whose changes carry another origin, as if made by another controller. */
    @NonNull
    public InMemoryClusterStore as(@NonNull String origin) {
        return new InMemoryClusterStore(shared, origin);
    }

    /** Takes an origin for gone (or alive again): its leases lapse, its claimed queue items may be put back. */
    public void gone(@NonNull String origin, boolean gone) {
        synchronized (shared) {
            if (gone) {
                if (shared.gone.add(origin)) {
                    shared.lapses.merge(origin, 1L, Long::sum);
                }
            } else {
                shared.gone.remove(origin);
            }
        }
    }

    private boolean alive(String o) {
        return !shared.gone.contains(o);
    }

    @NonNull
    @Override
    public String getOrigin() {
        return origin;
    }

    // ------------------------------------------------------------------ key-value store

    @CheckForNull
    @Override
    public Entry read(@NonNull String key) {
        synchronized (shared) {
            Entry e = shared.entries.get(key);
            return e == null ? null : new Entry(e.content().clone(), e.version());
        }
    }

    @Override
    public long write(@NonNull String key, @NonNull byte[] content, long expectedVersion) throws ConflictException {
        long version;
        synchronized (shared) {
            version = commitWrite(key, content, expectedVersion);
        }
        deliver();
        return version;
    }

    /** Under the data lock: writes, and queues the change for delivery. */
    private long commitWrite(String key, byte[] content, long expectedVersion) throws ConflictException {
        Entry current = shared.entries.get(key);
        long actual = current == null ? ABSENT : current.version();
        if (expectedVersion != ANY && expectedVersion != actual) {
            throw new ConflictException(key, expectedVersion, actual);
        }
        if (current != null && Arrays.equals(current.content(), content)) {
            return actual;
        }
        long version = ++shared.clock;
        shared.entries.put(key, new Entry(content.clone(), version));
        shared.outbox.add(new ChangeEvent(ChangeEvent.Kind.WRITTEN, key, null, version, origin));
        return version;
    }

    @Override
    public void delete(@NonNull String key, long expectedVersion) throws ConflictException {
        synchronized (shared) {
            Entry current = shared.entries.get(key);
            if (current == null) {
                return;
            }
            if (expectedVersion != ANY && expectedVersion != current.version()) {
                throw new ConflictException(key, expectedVersion, current.version());
            }
            shared.entries.remove(key);
            shared.outbox.add(new ChangeEvent(ChangeEvent.Kind.DELETED, key, null, ++shared.clock, origin));
        }
        deliver();
    }

    @NonNull
    @Override
    public Set<String> children(@NonNull String parentKey) {
        String prefix = parentKey + "/";
        Set<String> names = new TreeSet<>();
        synchronized (shared) {
            for (String key : shared.entries.keySet()) {
                if (key.startsWith(prefix)) {
                    String rest = key.substring(prefix.length());
                    int slash = rest.indexOf('/');
                    names.add(slash == -1 ? rest : rest.substring(0, slash));
                }
            }
        }
        hook("children", parentKey);
        return names;
    }

    @Override
    public int deleteAll(@NonNull String prefix) {
        int n = 0;
        synchronized (shared) {
            for (String key : List.copyOf(shared.entries.keySet())) {
                if (key.startsWith(prefix + "/")) {
                    shared.entries.remove(key);
                    shared.outbox.add(new ChangeEvent(ChangeEvent.Kind.DELETED, key, null, ++shared.clock, origin));
                    n++;
                }
            }
        }
        deliver();
        return n;
    }

    @NonNull
    @Override
    public List<Moved> move(
            @NonNull String fromPrefix, @NonNull String toPrefix, @NonNull List<Precondition> preconditions)
            throws ConflictException {
        List<Moved> moved = new ArrayList<>();
        synchronized (shared) {
            for (Precondition p : preconditions) {
                Entry e = shared.entries.get(p.key());
                long actual = e == null ? ABSENT : e.version();
                long expected = p.expectedVersion();
                if (!(expected == ANY || (expected == PRESENT ? actual != ABSENT : actual == expected))) {
                    throw new ConflictException(p.key(), expected, actual);
                }
            }
            for (Map.Entry<String, Entry> e : shared.entries.entrySet()) {
                if (e.getKey().equals(toPrefix) || e.getKey().startsWith(toPrefix + "/")) {
                    throw new ConflictException(e.getKey(), ABSENT, e.getValue().version());
                }
            }
            List<String> keys = new ArrayList<>();
            for (String key : shared.entries.keySet()) {
                if (key.equals(fromPrefix) || key.startsWith(fromPrefix + "/")) {
                    keys.add(key);
                }
            }
            // shallower keys first, so that an item's configuration arrives before the entries below it
            keys.sort((a, b) -> a.length() != b.length() ? Integer.compare(a.length(), b.length()) : a.compareTo(b));
            for (String key : keys) {
                String newKey = toPrefix + key.substring(fromPrefix.length());
                long version = ++shared.clock;
                Entry old = shared.entries.remove(key);
                shared.entries.put(newKey, new Entry(old.content(), version));
                moved.add(new Moved(key, old.version(), newKey, version));
                shared.outbox.add(new ChangeEvent(ChangeEvent.Kind.MOVED, newKey, key, version, origin));
            }
        }
        deliver();
        return moved;
    }

    @Override
    public boolean storesConfidentialKeys() {
        return true;
    }

    @NonNull
    @Override
    public AutoCloseable subscribe(@NonNull Consumer<ChangeEvent> listener) {
        shared.listeners.add(listener);
        return () -> shared.listeners.remove(listener);
    }

    /**
     * Loses the change events of every change made until called again with false (entries and queue records), as a
     * listener that was disconnected for longer than the store kept its events would.
     */
    public void loseEvents(boolean lose) {
        shared.losing = lose;
    }

    /**
     * Delivers the changes made so far in the order they were made: two threads committing one after the other must
     * not have their changes delivered the other way round.
     */
    private void deliver() {
        synchronized (shared.delivering) {
            if (shared.draining == Thread.currentThread()) {
                return; // a listener made a change: the loop below delivers it once every listener had the current one
            }
            shared.draining = Thread.currentThread();
            try {
                while (true) {
                    ChangeEvent e;
                    synchronized (shared) {
                        e = shared.outbox.poll();
                    }
                    if (e == null) {
                        return;
                    }
                    if (shared.losing) {
                        continue;
                    }
                    for (Consumer<ChangeEvent> l : shared.listeners) {
                        try {
                            l.accept(e);
                        } catch (RuntimeException | LinkageError x) {
                            LOGGER.log(Level.WARNING, "A listener failed to handle " + e, x);
                        }
                    }
                }
            } finally {
                shared.draining = null;
            }
        }
    }

    /** Snapshot of the stored keys and versions, for assertions. */
    @NonNull
    public Map<String, Long> versions() {
        Map<String, Long> r = new TreeMap<>();
        synchronized (shared) {
            shared.entries.forEach((k, v) -> r.put(k, v.version()));
        }
        return r;
    }

    // ------------------------------------------------------------------ leases

    private final BuildLeases leases = new BuildLeases() {
        @Override
        public long acquire(@NonNull String leaseKey) {
            synchronized (shared) {
                return take(shared.leases, leaseKey);
            }
        }

        @Override
        public boolean holds(@NonNull String leaseKey, long epoch) throws IOException {
            synchronized (shared) {
                if (!alive(origin)) {
                    // as a store whose controller's membership may have lapsed: it cannot vouch for the lease
                    throw new IOException("The membership of " + origin + " may have lapsed");
                }
                Lease l = shared.leases.get(leaseKey);
                return l != null && l.owner().equals(origin) && l.epoch() == epoch;
            }
        }

        @Override
        public void release(@NonNull String leaseKey, long epoch) {
            hook("release", leaseKey);
            synchronized (shared) {
                Lease l = shared.leases.get(leaseKey);
                if (l != null && l.owner().equals(origin) && l.epoch() == epoch) {
                    shared.leases.remove(leaseKey);
                }
            }
        }

        /** The check and the write in one step, as a store doing both in one transaction would. */
        @Override
        public long writeIfHeld(
                @NonNull String leaseKey,
                long epoch,
                @NonNull String key,
                @NonNull byte[] content,
                long expectedVersion)
                throws IOException {
            long version;
            synchronized (shared) {
                if (!holds(leaseKey, epoch)) {
                    throw new LeaseLostException(leaseKey, epoch);
                }
                version = commitWrite(key, content, expectedVersion);
            }
            deliver();
            return version;
        }
    };

    /** Under the data lock: takes a lease if free, lapsed, or held by this origin already. */
    private long take(Map<String, Lease> table, String key) {
        Lease l = table.get(key);
        if (l != null && l.owner().equals(origin)) {
            return l.epoch();
        }
        if (l != null && alive(l.owner())) {
            return 0;
        }
        long epoch = ++shared.clock;
        table.put(key, new Lease(origin, epoch));
        return epoch;
    }

    @NonNull
    @Override
    public BuildLeases leases() {
        return leases;
    }

    private final LaunchLeases launchLeases = new LaunchLeases() {
        @Override
        public long acquire(@NonNull String node) {
            synchronized (shared) {
                return take(shared.launchLeases, node);
            }
        }

        @CheckForNull
        @Override
        public Holder holder(@NonNull String node) {
            synchronized (shared) {
                Lease l = shared.launchLeases.get(node);
                return l == null ? null : new Holder(l.owner(), l.epoch());
            }
        }

        @Override
        public void release(@NonNull String node, long epoch) {
            synchronized (shared) {
                Lease l = shared.launchLeases.get(node);
                if (l != null && l.owner().equals(origin) && l.epoch() == epoch) {
                    shared.launchLeases.remove(node);
                }
            }
        }
    };

    @NonNull
    @Override
    public LaunchLeases launchLeases() {
        return launchLeases;
    }

    // ------------------------------------------------------------------ the shared queue

    private final ClusterQueue queue = new ClusterQueue() {
        private Record with(Record r, State state, String claimedBy, int buildNumber) {
            return new Record(
                    r.id(),
                    r.itemUuid(),
                    r.task(),
                    r.dedupKey(),
                    r.actions(),
                    r.dueAt(),
                    state,
                    r.origin(),
                    r.pinnedTo(),
                    claimedBy,
                    buildNumber);
        }

        /** Under the data lock: stores a record and queues it for delivery. */
        private void put(Record r) {
            shared.queue.put(r.id(), r);
            shared.queueOutbox.add(r);
        }

        @Override
        public long nextId() {
            synchronized (shared) {
                return ++shared.clock;
            }
        }

        @NonNull
        @Override
        public Record publish(@NonNull Record item) {
            synchronized (shared) {
                Record same = shared.queue.get(item.id());
                if (same != null) {
                    return same;
                }
                for (Record r : shared.queue.values()) {
                    if (r.state() == State.WAITING && r.dedupKey().equals(item.dedupKey())) {
                        return r;
                    }
                }
                put(item);
            }
            deliverQueue();
            return item;
        }

        @Override
        public boolean update(
                long id,
                @NonNull String expectedActions,
                @NonNull String actions,
                long dueAt,
                @CheckForNull String pinTo) {
            synchronized (shared) {
                Record r = shared.queue.get(id);
                if (r == null || r.state() != State.WAITING || !r.actions().equals(expectedActions)) {
                    return false;
                }
                Record next = new Record(
                        r.id(),
                        r.itemUuid(),
                        r.task(),
                        r.dedupKey(),
                        actions,
                        Math.min(r.dueAt(), dueAt),
                        r.state(),
                        r.origin(),
                        r.pinnedTo() != null ? r.pinnedTo() : pinTo,
                        r.claimedBy(),
                        r.buildNumber());
                if (next.equals(r)) {
                    return true;
                }
                put(next);
            }
            deliverQueue();
            return true;
        }

        @Override
        public boolean claim(long id, @CheckForNull String expectedPin) {
            synchronized (shared) {
                Record r = shared.queue.get(id);
                if (r == null || r.state() != State.WAITING) {
                    return r != null && origin.equals(r.claimedBy());
                }
                if (!Objects.equals(r.pinnedTo(), expectedPin)) {
                    return false;
                }
                put(with(r, State.CLAIMED, origin, 0));
            }
            deliverQueue();
            return true;
        }

        @Override
        public boolean started(long id, int buildNumber) {
            synchronized (shared) {
                Record r = shared.queue.get(id);
                if (r == null
                        || !origin.equals(r.claimedBy())
                        || !(r.state() == State.CLAIMED
                                || r.state() == State.STARTED && r.buildNumber() == buildNumber)) {
                    return false;
                }
                put(with(r, State.STARTED, origin, buildNumber));
            }
            deliverQueue();
            return true;
        }

        @Override
        public boolean cancel(long id) {
            synchronized (shared) {
                Record r = shared.queue.get(id);
                if (r == null
                        || r.state() != State.WAITING
                                && !(r.state() == State.CLAIMED && origin.equals(r.claimedBy()))) {
                    return false;
                }
                put(with(r, State.CANCELLED, null, 0));
            }
            deliverQueue();
            return true;
        }

        @CheckForNull
        @Override
        public Record get(long id) {
            synchronized (shared) {
                return shared.queue.get(id);
            }
        }

        @NonNull
        @Override
        public List<Record> pending() {
            synchronized (shared) {
                return shared.queue.values().stream()
                        .filter(r -> r.state() == State.WAITING || r.state() == State.CLAIMED)
                        .toList();
            }
        }

        @NonNull
        @Override
        public AutoCloseable subscribe(@NonNull Consumer<Record> listener) {
            shared.queueListeners.add(listener);
            return () -> shared.queueListeners.remove(listener);
        }
    };

    private void deliverQueue() {
        synchronized (shared.deliveringQueue) {
            if (shared.drainingQueue == Thread.currentThread()) {
                return;
            }
            shared.drainingQueue = Thread.currentThread();
            try {
                while (true) {
                    ClusterQueue.Record r;
                    synchronized (shared) {
                        r = shared.queueOutbox.poll();
                    }
                    if (r == null) {
                        return;
                    }
                    if (shared.losing) {
                        continue;
                    }
                    for (Consumer<ClusterQueue.Record> l : shared.queueListeners) {
                        try {
                            l.accept(r);
                        } catch (RuntimeException | LinkageError x) {
                            LOGGER.log(Level.WARNING, "A listener failed to handle " + r, x);
                        }
                    }
                }
            } finally {
                shared.drainingQueue = null;
            }
        }
    }

    @NonNull
    @Override
    public ClusterQueue queue() {
        return queue;
    }

    @Override
    public int reclaimAndPrune() {
        int n = 0;
        synchronized (shared) {
            for (ClusterQueue.Record r : List.copyOf(shared.queue.values())) {
                if (r.state() == ClusterQueue.State.CLAIMED && !alive(r.claimedBy())) {
                    boolean equalWaits = shared.queue.values().stream()
                            .anyMatch(o -> o.state() == ClusterQueue.State.WAITING
                                    && o.dedupKey().equals(r.dedupKey()));
                    ClusterQueue.State state = equalWaits ? ClusterQueue.State.CANCELLED : ClusterQueue.State.WAITING;
                    ClusterQueue.Record next = new ClusterQueue.Record(
                            r.id(),
                            r.itemUuid(),
                            r.task(),
                            r.dedupKey(),
                            r.actions(),
                            r.dueAt(),
                            state,
                            r.origin(),
                            r.pinnedTo(),
                            null,
                            0);
                    shared.queue.put(r.id(), next);
                    shared.queueOutbox.add(next);
                    if (!equalWaits) {
                        n++;
                    }
                }
            }
        }
        deliverQueue();
        return n;
    }

    @NonNull
    @Override
    public List<StartedItem> startedByGone() {
        synchronized (shared) {
            return shared.queue.values().stream()
                    .filter(r ->
                            r.state() == ClusterQueue.State.STARTED && r.buildNumber() > 0 && !alive(r.claimedBy()))
                    .map(r -> new StartedItem(r.id(), r.itemUuid(), r.task(), r.buildNumber()))
                    .toList();
        }
    }

    @Override
    public void requeueStarted(long id) {
        synchronized (shared) {
            ClusterQueue.Record r = shared.queue.get(id);
            if (r == null || r.state() != ClusterQueue.State.STARTED) {
                return;
            }
            boolean equalWaits = shared.queue.values().stream()
                    .anyMatch(o -> o.state() == ClusterQueue.State.WAITING
                            && o.dedupKey().equals(r.dedupKey()));
            ClusterQueue.Record next = new ClusterQueue.Record(
                    r.id(),
                    r.itemUuid(),
                    r.task(),
                    r.dedupKey(),
                    r.actions(),
                    r.dueAt(),
                    equalWaits ? ClusterQueue.State.CANCELLED : ClusterQueue.State.WAITING,
                    r.origin(),
                    r.pinnedTo(),
                    null,
                    0);
            shared.queue.put(id, next);
            shared.queueOutbox.add(next);
        }
        deliverQueue();
    }

    @Override
    public void cancelStartedOf(@NonNull String itemUuid) {
        synchronized (shared) {
            for (ClusterQueue.Record r : List.copyOf(shared.queue.values())) {
                if (r.state() == ClusterQueue.State.STARTED && r.itemUuid().equals(itemUuid)) {
                    shared.queue.put(
                            r.id(),
                            new ClusterQueue.Record(
                                    r.id(),
                                    r.itemUuid(),
                                    r.task(),
                                    r.dedupKey(),
                                    r.actions(),
                                    r.dueAt(),
                                    ClusterQueue.State.CANCELLED,
                                    r.origin(),
                                    r.pinnedTo(),
                                    r.claimedBy(),
                                    r.buildNumber()));
                }
            }
        }
    }

    @NonNull
    @Override
    public Map<String, Integer> queueCounts() {
        Map<String, Integer> counts = new TreeMap<>();
        synchronized (shared) {
            for (ClusterQueue.Record r : shared.queue.values()) {
                if (r.state() == ClusterQueue.State.WAITING || r.state() == ClusterQueue.State.CLAIMED) {
                    counts.merge(r.state().name(), 1, Integer::sum);
                }
            }
        }
        return counts;
    }

    // ------------------------------------------------------------------ membership, claims, channels, counters

    @NonNull
    @Override
    public List<Member> members() {
        List<Member> members = new ArrayList<>();
        synchronized (shared) {
            for (String o : shared.origins) {
                members.add(new Member(
                        o, o, null, System.currentTimeMillis(), 0, alive(o), null, ClusterMember.PROTOCOL, null, null));
            }
        }
        return members;
    }

    @NonNull
    @Override
    public Set<String> aliveMembers() {
        synchronized (shared) {
            Set<String> alive = new TreeSet<>(shared.origins);
            alive.removeAll(shared.gone);
            alive.add(origin);
            return alive;
        }
    }

    @CheckForNull
    @Override
    public String urlOf(@NonNull String name) {
        return null;
    }

    @CheckForNull
    @Override
    public String urlOfOrigin(@NonNull String origin) {
        return null;
    }

    @NonNull
    @Override
    public List<String> peersBelow(int minimum) {
        return List.of();
    }

    @Override
    public boolean mayHaveLapsed() {
        synchronized (shared) {
            return !alive(origin);
        }
    }

    @Override
    public long lapseCount() {
        synchronized (shared) {
            return shared.lapses.getOrDefault(origin, 0L);
        }
    }

    @Override
    public boolean canJudgeLiveness() {
        return true;
    }

    @NonNull
    @Override
    public Map<String, byte[]> runningPlaceholders() {
        Map<String, byte[]> result = new LinkedHashMap<>();
        synchronized (shared) {
            shared.entries.forEach((k, v) -> {
                if (k.matches(Keys.ITEMS + "/[^/]+/running/[0-9]+\\.xml")) {
                    result.put(k, v.content().clone());
                }
            });
        }
        return result;
    }

    @Override
    public boolean claim(@NonNull String item, @NonNull String trigger, long tick) {
        synchronized (shared) {
            return shared.claims.putIfAbsent(item + "\u0000" + trigger + "\u0000" + tick, origin) == null;
        }
    }

    @CheckForNull
    @Override
    public String lastClaimant(@NonNull String item, @NonNull String trigger) {
        synchronized (shared) {
            String prefix = item + "\u0000" + trigger + "\u0000";
            long last = Long.MIN_VALUE;
            String claimant = null;
            for (Map.Entry<String, String> e : shared.claims.entrySet()) {
                if (e.getKey().startsWith(prefix)) {
                    long tick = Long.parseLong(e.getKey().substring(prefix.length()));
                    if (tick > last) {
                        last = tick;
                        claimant = e.getValue();
                    }
                }
            }
            return claimant;
        }
    }

    @Override
    public void channelConnected(@NonNull String agent) {
        synchronized (shared) {
            shared.channels.put(agent, origin);
        }
    }

    @Override
    public void channelDisconnected(@NonNull String agent) {
        synchronized (shared) {
            shared.channels.remove(agent, origin);
        }
    }

    @CheckForNull
    @Override
    public String[] channelHolder(@NonNull String agent) {
        synchronized (shared) {
            String o = shared.channels.get(agent);
            return o == null || o.equals(origin) || !alive(o) ? null : new String[] {o, null, o};
        }
    }

    @NonNull
    @Override
    public Map<String, String> channels() {
        synchronized (shared) {
            return new TreeMap<>(shared.channels);
        }
    }

    @Override
    public boolean addWithinCaps(@NonNull Map<String, Integer> caps, int delta) {
        synchronized (shared) {
            for (Map.Entry<String, Integer> cap : caps.entrySet()) {
                int sum = shared.counters.getOrDefault(cap.getKey(), Map.of()).entrySet().stream()
                        .filter(e -> alive(e.getKey()))
                        .mapToInt(Map.Entry::getValue)
                        .sum();
                if (sum + delta > cap.getValue()) {
                    return false;
                }
            }
            for (String scope : caps.keySet()) {
                shared.counters
                        .computeIfAbsent(scope, k -> new TreeMap<>())
                        .merge(origin, delta, (a, b) -> Math.max(0, a + b));
            }
            return true;
        }
    }

    @Override
    public void subtractFromCounters(@NonNull Set<String> scopes, int delta) {
        synchronized (shared) {
            for (String scope : scopes) {
                shared.counters
                        .computeIfAbsent(scope, k -> new TreeMap<>())
                        .merge(origin, -delta, (a, b) -> Math.max(0, a + b));
            }
        }
    }
}
