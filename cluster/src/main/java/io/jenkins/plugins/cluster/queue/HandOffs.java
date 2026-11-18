package io.jenkins.plugins.cluster.queue;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Job;
import hudson.model.Queue;
import hudson.model.Run;
import io.jenkins.plugins.cluster.builds.BuildElsewhere;
import io.jenkins.plugins.cluster.spi.ClusterQueue;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * The hand-offs of items another controller took ({@link Queue#handOff}), completed as the build elsewhere starts and
 * completes (or the item is cancelled there), so that whoever waits on the item's future here (the CLI's
 * {@code build -s}, a {@code build} step) follows that build. An item put back and built here after all completes
 * them with its build here.
 */
final class HandOffs {

    /**
     * What follows one item: every hand-off of it here (an item re-created here after its claimant died is handed
     * off again when claimed elsewhere, with another left item), all told of its build.
     */
    private record Pending(
            List<Queue.HandOff> handOffs,
            long since,
            @CheckForNull String uuid,
            int number) {

        Pending and(Queue.HandOff handOff) {
            if (handOffs.contains(handOff)) {
                return this;
            }
            List<Queue.HandOff> all = new ArrayList<>(handOffs);
            all.add(handOff);
            return new Pending(List.copyOf(all), since, uuid, number);
        }

        Pending startedAs(@CheckForNull String uuid, int number) {
            return new Pending(handOffs, since, uuid, number);
        }

        void started(Queue.Executable view) {
            handOffs.forEach(h -> h.started(view));
        }

        void completed(Queue.Executable run) {
            handOffs.forEach(h -> h.completed(run));
        }

        void cancelled() {
            handOffs.forEach(Queue.HandOff::cancelled);
        }
    }

    /** By queue item id. */
    private static final Map<Long, Pending> pending = new ConcurrentHashMap<>();

    /** How long a hand-off is followed at most: the build elsewhere may never be heard of (its controller died). */
    static final long MAX_MILLIS = TimeUnit.DAYS.toMillis(1);

    private HandOffs() {}

    static void add(long id, @NonNull Queue.HandOff handOff) {
        follow(id, handOff);
    }

    /** A local item was folded into another one ({@code into}): its future follows that item. */
    static void folded(long id, long into, @NonNull Queue.HandOff handOff) {
        follow(into, handOff);
    }

    private static void follow(long id, Queue.HandOff handOff) {
        pending.merge(
                id,
                new Pending(List.of(handOff), System.currentTimeMillis(), null, 0),
                (followed, ignored) -> followed.and(handOff));
        prune();
    }

    static void cancelled(long id) {
        Pending p = pending.remove(id);
        if (p != null) {
            p.cancelled();
        }
    }

    /** The item started on another controller: its future's start condition gets that build (or its view). */
    static void startedElsewhere(@NonNull ClusterQueue.Record r) {
        Pending p = pending.get(r.id());
        if (p == null || r.buildNumber() <= 0 || p.number() > 0) {
            return;
        }
        Job<?, ?> job = SharedQueue.jobOf(r);
        if (job == null) {
            return;
        }
        pending.put(r.id(), p.startedAs(r.itemUuid(), r.buildNumber()));
        Run<?, ?> run = job.getBuildByNumber(r.buildNumber());
        if (run != null && !run.isLogUpdated()) {
            // completed already, and its record is here
            pending.remove(r.id());
            p.completed((Queue.Executable) run);
            return;
        }
        p.started(run != null ? (Queue.Executable) run : BuildElsewhere.view(job, r.buildNumber(), r.claimedBy()));
    }

    /** The item started here after all (put back, then claimed here): its future follows this build. */
    static void startedHere(long id, @NonNull Run<?, ?> run) {
        Pending p = pending.get(id);
        if (p != null && p.number() == 0) {
            pending.put(id, p.startedAs(run.getParent().getItemUuid(), run.getNumber()));
            p.started((Queue.Executable) run);
        }
    }

    static void completedHere(long id, @NonNull Run<?, ?> run) {
        Pending p = pending.get(id);
        if (p != null
                && run.getNumber() == p.number()
                && Objects.equals(run.getParent().getItemUuid(), p.uuid())) {
            pending.remove(id);
            p.completed((Queue.Executable) run);
        }
    }

    /**
     * The record of a build completed elsewhere was published here: the future of an item it was the build of
     * completes with it.
     */
    static void recorded(@NonNull Job<?, ?> job, int number) {
        if (pending.isEmpty()) {
            return;
        }
        String uuid = job.getItemUuid();
        for (Map.Entry<Long, Pending> e : pending.entrySet()) {
            Pending p = e.getValue();
            if (p.number() == number && Objects.equals(uuid, p.uuid())) {
                Run<?, ?> run = job.getBuildByNumber(number);
                if (run != null && !run.isLogUpdated() && pending.remove(e.getKey(), p)) {
                    p.completed((Queue.Executable) run);
                }
            }
        }
    }

    private static void prune() {
        long now = System.currentTimeMillis();
        pending.values().removeIf(p -> now - p.since() > MAX_MILLIS);
    }

    static void clear() {
        pending.clear();
    }

    /** The items followed. */
    static Set<Long> ids() {
        return Set.copyOf(pending.keySet());
    }

    /** How many are followed, for tests. */
    static int size() {
        return pending.size();
    }
}
