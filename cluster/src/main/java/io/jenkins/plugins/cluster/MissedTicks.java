package io.jenkins.plugins.cluster;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * What this controller saw of each trigger tick it left to another controller (Target 15, ticks of a gone owner): the
 * owner it left it to, kept for a bounded window. Once that owner is found gone (or not ready), the controller owning
 * the item now runs the trigger once for the ticks it missed, unless a later tick of the same trigger was handled since.
 *
 * <p>Pure bookkeeping, so that it can be tested without a store: {@link ClusterTriggerCoordinator} records into it and
 * claims and runs what {@link #due} answers.
 */
final class MissedTicks {

    /** The ticks of one trigger of one item to catch up, all at once. */
    record Due(String item, String trigger, List<Long> ticks) {}

    private record Key(String item, String trigger) {}

    /** Per trigger: tick (epoch minute) → the origin it was left to. */
    private final Map<Key, TreeMap<Long, String>> deferred = new HashMap<>();

    /** Per trigger: the latest tick this controller handled as its owner (claimed it, or found it claimed). */
    private final Map<Key, Long> handled = new HashMap<>();

    private final long windowMinutes;
    private final int maxEntries;
    private int entries;

    MissedTicks(long windowMinutes, int maxEntries) {
        this.windowMinutes = windowMinutes;
        this.maxEntries = maxEntries;
    }

    /** A tick of a trigger left to {@code owner}. */
    synchronized void leftTo(@NonNull String item, @NonNull String trigger, long tick, @NonNull String owner) {
        if (windowMinutes <= 0 || entries >= maxEntries) {
            return; // catching up is off, or too much is pending: such ticks are skipped, as before
        }
        if (deferred.computeIfAbsent(new Key(item, trigger), k -> new TreeMap<>())
                        .put(tick, owner)
                == null) {
            entries++;
        }
    }

    /** A tick of a trigger this controller owned (it ran it, or another controller had claimed it already). */
    synchronized void handledHere(@NonNull String item, @NonNull String trigger, long tick) {
        handled.merge(new Key(item, trigger), tick, Math::max);
    }

    /**
     * The ticks to catch up now, which are forgotten: each tick at least a minute old and within the window, left to a
     * controller no longer among {@code ready}, of an item {@code self} owns now, with no later tick of the same trigger
     * handled since (here, or left to a controller still ready). Ticks of items owned by another controller now are kept
     * (until the window ends), in case the controllers disagree about the owner for a while.
     *
     * @param nowMinute the current epoch minute
     * @param ready the ready members now
     * @param ownerOf the owner now of an item, by its full name
     */
    @NonNull
    synchronized List<Due> due(
            long nowMinute,
            @NonNull Set<String> ready,
            @NonNull String self,
            @NonNull Function<String, String> ownerOf) {
        List<Due> result = new ArrayList<>();
        long oldest = nowMinute - windowMinutes;
        for (Iterator<Map.Entry<Key, TreeMap<Long, String>>> it =
                        deferred.entrySet().iterator();
                it.hasNext(); ) {
            Map.Entry<Key, TreeMap<Long, String>> e = it.next();
            TreeMap<Long, String> ticks = e.getValue();
            int before = ticks.size();
            ticks.headMap(oldest, false).clear();
            entries -= before - ticks.size();
            List<Long> missed = new ArrayList<>();
            long latestKept = Long.MIN_VALUE; // the latest tick left to a controller still ready
            for (Map.Entry<Long, String> t : ticks.headMap(nowMinute, false).entrySet()) {
                if (ready.contains(t.getValue())) {
                    latestKept = Math.max(latestKept, t.getKey());
                } else {
                    missed.add(t.getKey());
                }
            }
            if (!missed.isEmpty() && self.equals(ownerOf.apply(e.getKey().item()))) {
                long lastMissed = missed.get(missed.size() - 1);
                long handledHere = handled.getOrDefault(e.getKey(), Long.MIN_VALUE);
                if (handledHere < lastMissed && latestKept < lastMissed) {
                    result.add(new Due(e.getKey().item(), e.getKey().trigger(), List.copyOf(missed)));
                }
                // either way they are dealt with: run now, or covered by a later tick
                for (Long t : missed) {
                    ticks.remove(t);
                }
                entries -= missed.size();
            }
            if (ticks.isEmpty()) {
                it.remove();
            }
        }
        handled.values().removeIf(t -> t < oldest);
        return result;
    }

    synchronized int size() {
        return entries;
    }
}
