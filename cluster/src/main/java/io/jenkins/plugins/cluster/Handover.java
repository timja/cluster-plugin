package io.jenkins.plugins.cluster;

import io.jenkins.plugins.cluster.spi.ClusterStore;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.Computer;
import hudson.model.Job;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.Run;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.persistence.PersistenceStore;
import jenkins.persistence.PersistenceStores;
import jenkins.persistence.RunningBuilds;
import jenkins.util.Timer;

/**
 * Moves a Pipeline to the controller holding the agent it waits for (Target 12). A {@code node('label')} step whose label
 * no executor or cloud of this controller can serve, while an agent with that label is connected to another live
 * controller, waits forever where it is; after {@link #WAIT_SECONDS} the build is handed over: its latest save point is
 * shipped, a request {@code .../running/<N>.handover} naming the target is written, and its lease released
 * ({@link RunningBuilds#handOver}). The target takes it over ({@link RunningBuilds#takeOver}) and resumes it from that
 * save point, where the {@code node} step asks for an executor again, now one it has.
 */
public final class Handover {

    private static final Logger LOGGER = Logger.getLogger(Handover.class.getName());
    static final String SUFFIX = ".handover";
    static final long WAIT_SECONDS = Long.getLong(Handover.class.getName() + ".waitSeconds", 15);
    private static final String PLACEHOLDER_TASK = "org.jenkinsci.plugins.workflow.support.steps.ExecutorStepExecution$PlaceholderTask";

    private Handover() {}

    @Initializer(after = InitMilestone.JOB_LOADED)
    public static void start() {
        if (!(PersistenceStores.get() instanceof ClusterStore store)) {
            return;
        }
        store.subscribe(e -> {
            if (e.kind() == PersistenceStore.ChangeEvent.Kind.WRITTEN && e.key().endsWith(SUFFIX) && !store.getOrigin().equals(e.origin())) {
                Timer.get().submit(() -> takeOver(store, e.key()));
            }
        });
        Timer.get().scheduleWithFixedDelay(Handover::offer, 5, 5, TimeUnit.SECONDS);
    }

    /** Hands over builds waiting for a label only another controller has. */
    static void offer() {
        if (!(PersistenceStores.get() instanceof ClusterStore store)) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Queue.Item item : Queue.getInstance().getItems()) {
            if (!item.task.getClass().getName().equals(PLACEHOLDER_TASK) || now - item.getInQueueSince() < TimeUnit.SECONDS.toMillis(WAIT_SECONDS)) {
                continue;
            }
            Label label = item.getAssignedLabel();
            if (label == null || servableHere(label) || !(item.task.getOwnerExecutable() instanceof Run<?, ?> run)) {
                continue;
            }
            try {
                String target = holderOf(store, label);
                if (target == null || RunningBuilds.ownedPlaceholder(run) == null) {
                    continue;
                }
                String reason = staysHere(run, item);
                if (reason != null) {
                    LOGGER.fine(() -> "Not handing " + run + " over for '" + label + "': " + reason);
                    continue;
                }
                String base = PersistenceStores.keyFor(run.getParent().getRootDir()) + "/running/" + run.getNumber();
                if (!countHop(store, base + HOPS)) {
                    LOGGER.warning(() -> run + " waits for '" + label + "', which only another controller has, but it was handed over "
                            + MAX_HOPS + " times already (node blocks needing agents of different controllers); it waits here");
                    continue;
                }
                String key = base + SUFFIX;
                LOGGER.info(() -> "Handing " + run + " over: it waits for '" + label + "', which only another controller has");
                SavePoints.flush(run);
                store.write(key, target.getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
                RunningBuilds.handOver(run);
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.WARNING, "Could not hand " + run + " over", e);
            }
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
                store.write(key, String.valueOf(hops + 1).getBytes(StandardCharsets.UTF_8), e == null ? PersistenceStore.ABSENT : e.version());
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
            if (PersistenceStores.get() instanceof ClusterStore store) {
                String job = PersistenceStores.keyFor(run.getParent().getRootDir());
                if (job != null) {
                    try {
                        if (store.read(job + "/running/" + run.getNumber() + HOPS) != null) {
                            store.delete(job + "/running/" + run.getNumber() + HOPS, PersistenceStore.ANY);
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
            String jobKey = path.substring(0, path.lastIndexOf("/running/"));
            String fullName = jobKey.replaceFirst("^jobs/", "").replace("/jobs/", "/");
            Job<?, ?> job = Jenkins.get().getItemByFullName(fullName, Job.class);
            if (job == null) {
                return;
            }
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
