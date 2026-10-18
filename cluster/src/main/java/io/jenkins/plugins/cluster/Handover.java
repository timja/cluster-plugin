package io.jenkins.plugins.cluster;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.Computer;
import hudson.model.Job;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.queue.QueueListener;
import io.jenkins.plugins.cluster.builds.BuildRecords;
import io.jenkins.plugins.cluster.builds.RunningBuilds;
import io.jenkins.plugins.cluster.spi.ClusterStore;
import io.jenkins.plugins.cluster.spi.Keys;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.ItemUuids;
import jenkins.model.Jenkins;
import jenkins.persistence.PersistenceStore;
import jenkins.util.Timer;

/**
 * Moves a Pipeline to the controller holding the agent it waits for (Target 12). A {@code node('label')} step whose label
 * no executor or cloud of this controller can serve, while an agent with that label is connected to another live
 * controller, waits forever where it is; {@link #WAIT_SECONDS} after it entered the queue (for the other {@code node}
 * blocks of a {@code parallel} to enter it too) the build is handed over: its latest save point is shipped, a request
 * {@code .../running/<N>.handover} naming the target is written, and its lease released
 * ({@link RunningBuilds#handOver}). That is looked at when the step enters the queue, and again every few seconds for
 * steps still waiting (an agent with the label may connect to another controller later). The target takes it over
 * ({@link RunningBuilds#takeOver}) and resumes it from that save point, where the {@code node} step asks for an executor
 * again, now one it has.
 */
public final class Handover {

    private static final Logger LOGGER = Logger.getLogger(Handover.class.getName());
    static final String SUFFIX = ".handover";
    static final long WAIT_SECONDS = Long.getLong(Handover.class.getName() + ".waitSeconds", 2);
    private static final String PLACEHOLDER_TASK =
            "org.jenkinsci.plugins.workflow.support.steps.ExecutorStepExecution$PlaceholderTask";

    private Handover() {}

    @Initializer(after = InitMilestone.JOB_LOADED)
    public static void start() {
        ClusterStore store = ClusterStore.get();
        if (store == null) {
            return;
        }
        store.subscribe(e -> {
            if (e.kind() == PersistenceStore.ChangeEvent.Kind.WRITTEN
                    && e.key().endsWith(SUFFIX)
                    && !store.getOrigin().equals(e.origin())) {
                Timer.get().submit(() -> takeOver(store, e.key()));
            }
        });
        Timer.get().scheduleWithFixedDelay(Handover::offer, 5, 5, TimeUnit.SECONDS);
    }

    /** Hands over builds waiting for a label only another controller has. */
    static void offer() {
        ClusterStore store = ClusterStore.get();
        if (store == null) {
            return;
        }
        for (Queue.Item item : Queue.getInstance().getItems()) {
            offer(store, item);
        }
    }

    /** Hands over the build of a {@code node} step waiting in the queue, if it waits for a label only another controller has. */
    private static synchronized void offer(ClusterStore store, Queue.Item item) {
        if (!isPlaceholder(item)
                || System.currentTimeMillis() - item.getInQueueSince() < TimeUnit.SECONDS.toMillis(WAIT_SECONDS)) {
            return;
        }
        Label label = item.getAssignedLabel();
        if (label == null || servableHere(label) || !(item.task.getOwnerExecutable() instanceof Run<?, ?> run)) {
            return;
        }
        try {
            String target = holderOf(store, label);
            if (target == null || RunningBuilds.ownedPlaceholder(run) == null) {
                return; // nobody to hand it to, or handed over already (by the sweep or the queue listener)
            }
            String reason = staysHere(run, item);
            if (reason != null) {
                LOGGER.fine(() -> "Not handing " + run + " over for '" + label + "': " + reason);
                return;
            }
            String base = Keys.running(Keys.item(run.getParent())) + "/" + run.getNumber();
            if (!countHop(store, base + HOPS)) {
                LOGGER.warning(() -> run + " waits for '" + label
                        + "', which only another controller has, but it was handed over " + MAX_HOPS
                        + " times already (node blocks needing agents of different controllers); it waits here");
                return;
            }
            String key = base + SUFFIX;
            LOGGER.info(
                    () -> "Handing " + run + " over: it waits for '" + label + "', which only another controller has");
            SavePoints.flush(run);
            store.write(key, target.getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
            RunningBuilds.handOver(run);
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not hand " + run + " over", e);
        }
    }

    private static boolean isPlaceholder(Queue.Item item) {
        return item.task.getClass().getName().equals(PLACEHOLDER_TASK);
    }

    /**
     * Looks at a {@code node} step as soon as it has waited {@link #WAIT_SECONDS} in the queue, rather than at the next
     * sweep: a build waiting for an agent of another controller is handed over at once.
     */
    @hudson.Extension
    public static final class Waiting extends QueueListener {
        @Override
        public void onEnterWaiting(Queue.WaitingItem wi) {
            ClusterStore store = ClusterStore.get();
            if (store == null || !isPlaceholder(wi)) {
                return;
            }
            long id = wi.getId();
            Timer.get()
                    .schedule(
                            () -> {
                                Queue.Item item = Queue.getInstance().getItem(id);
                                if (item != null) {
                                    offer(store, item);
                                }
                            },
                            TimeUnit.SECONDS.toMillis(WAIT_SECONDS) + 1,
                            TimeUnit.MILLISECONDS);
        }
    }

    /** Stored count of a build's handovers. */
    static final String HOPS = ".hops";
    /** How many times one build may be handed over (Target 15): bounded, so it never moves back and forth forever. */
    static final int MAX_HOPS = Integer.getInteger(Handover.class.getName() + ".maxHops", 4);

    /**
     * Why a build waiting for a label only another controller has must stay here anyway (Target 15), or null: it runs
     * other {@code node} blocks on this controller's agents (handing it over would interrupt them), or waits for
     * another one this controller can serve (nested or parallel blocks needing agents of different controllers would
     * otherwise send it back and forth).
     */
    @CheckForNull
    static String staysHere(Run<?, ?> run, Queue.Item waiting) {
        for (Computer c : Jenkins.get().getComputers()) {
            for (hudson.model.Executor e : c.getExecutors()) {
                hudson.model.queue.WorkUnit w = e.getCurrentWorkUnit();
                if (w != null && w.work.getOwnerExecutable() == run) {
                    return "it runs a node block on " + (c.getName().isEmpty() ? "the built-in node" : c.getName());
                }
            }
        }
        for (Queue.Item other : Queue.getInstance().getItems()) {
            if (other != waiting && other.task.getOwnerExecutable() == run) {
                Label l = other.getAssignedLabel();
                if (l == null || servableHere(l)) {
                    return "it also waits for '" + l + "', which this controller has";
                }
            }
        }
        return null;
    }

    /** Counts a handover of a build; false if it was handed over {@link #MAX_HOPS} times already. */
    private static boolean countHop(ClusterStore store, String key) throws IOException {
        for (int attempt = 0; attempt < 10; attempt++) {
            PersistenceStore.Entry e = store.read(key);
            int hops = e == null ? 0 : Integer.parseInt(new String(e.content(), StandardCharsets.UTF_8).trim());
            if (hops >= MAX_HOPS) {
                return false;
            }
            try {
                store.write(
                        key,
                        String.valueOf(hops + 1).getBytes(StandardCharsets.UTF_8),
                        e == null ? PersistenceStore.ABSENT : e.version());
                return true;
            } catch (jenkins.persistence.ConflictException again) {
                // counted by another at the same moment: count again
            }
        }
        return false;
    }

    /** Forgets a build's handover count once it is done. */
    @hudson.Extension
    public static final class Done extends hudson.model.listeners.RunListener<Run<?, ?>> {
        @Override
        public void onFinalized(Run<?, ?> run) {
            ClusterStore store = ClusterStore.get();
            // not for a copy stopped here because another controller took the build over: that one counts on
            if (store != null && BuildRecords.isShared(run.getParent()) && RunningBuilds.owns(run)) {
                String hops = Keys.running(Keys.item(run.getParent())) + "/" + run.getNumber() + HOPS;
                {
                    try {
                        if (store.read(hops) != null) {
                            store.delete(hops, PersistenceStore.ANY);
                        }
                    } catch (IOException | RuntimeException e) {
                        LOGGER.log(Level.FINE, "Could not forget the handovers of " + run, e);
                    }
                }
            }
        }
    }

    /** Whether some executor or cloud of this controller can (eventually) run work with this label. */
    private static boolean servableHere(Label label) {
        if (!label.getClouds().isEmpty()) {
            return true;
        }
        for (Node n : label.getNodes()) {
            Computer c = n.toComputer();
            if (c != null && c.isOnline() && c.getNumExecutors() > 0) {
                return true;
            }
        }
        return false;
    }

    /** The origin of a live controller holding the channel of an agent with this label, or null. */
    @CheckForNull
    private static String holderOf(ClusterStore store, Label label) throws IOException {
        for (Node n : label.getNodes()) {
            String[] holder = store.channelHolder(n.getNodeName());
            if (holder != null) {
                return holder[2];
            }
        }
        return null;
    }

    /** The target side: take over a build handed over to this controller, retrying until its lease is free. */
    static void takeOver(ClusterStore store, String key) {
        try {
            PersistenceStore.Entry e = store.read(key);
            if (e == null || !store.getOrigin().equals(new String(e.content(), StandardCharsets.UTF_8))) {
                return; // not for us
            }
            String path = key.substring(0, key.length() - SUFFIX.length());
            int slash = path.lastIndexOf('/');
            int number = Integer.parseInt(path.substring(slash + 1));
            Keys.ItemKey item = Keys.parse(path);
            Job<?, ?> job;
            try (hudson.security.ACLContext ignored = hudson.security.ACL.as2(hudson.security.ACL.SYSTEM2)) {
                job = item == null ? null : ItemUuids.get(item.uuid()) instanceof Job<?, ?> j ? j : null;
            }
            if (job == null) {
                return;
            }
            String fullName = job.getFullName();
            for (int attempt = 0; attempt < 30; attempt++) {
                if (RunningBuilds.takeOver(job, number)) {
                    LOGGER.info(() -> "Took over " + fullName + " #" + number);
                    store.delete(key, PersistenceStore.ANY);
                    return;
                }
                Thread.sleep(1000);
            }
            LOGGER.warning(() -> "Could not take over " + fullName + " #" + number + " handed over to this controller");
        } catch (InterruptedException x) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException x) {
            LOGGER.log(Level.WARNING, "Could not take over " + key, x);
        }
    }
}
