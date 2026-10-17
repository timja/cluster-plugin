package io.jenkins.plugins.cluster.pipeline;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.Job;
import hudson.model.Queue;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.util.DaemonThreadFactory;
import hudson.util.NamingThreadFactory;
import io.jenkins.plugins.cluster.spi.ClusterStore;
import io.jenkins.plugins.cluster.spi.Keys;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jenkins.model.ExternalBuild;
import jenkins.model.ExternalBuilds;
import jenkins.model.ItemUuids;
import jenkins.persistence.ConflictException;
import jenkins.persistence.PersistenceStore;
import jenkins.persistence.RemoteChangeHandler;
import jenkins.util.Timer;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionListener;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;
import org.jenkinsci.plugins.workflow.support.steps.build.BuildTriggerRelay;
import org.jenkinsci.plugins.workflow.support.steps.build.BuildTriggerStepExecution;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Brings the events of a downstream build to the {@code build} step waiting for it when the two run on different
 * controllers. The step's return address is its build, {@code <item uuid>#<number>#<flow node>}; an event for a step
 * whose build does not run here is stored as {@link Keys#delivery} below that build. Whichever controller runs the
 * upstream build completes the step: when told of the delivery, or when it resumes the build (after a restart, or
 * when it adopted it). A delivery is deleted once handled, and the rest when the upstream build completes.
 */
@Extension(optional = true)
@Restricted(NoExternalUse.class)
public final class ClusterBuildTriggerRelay implements BuildTriggerRelay, RemoteChangeHandler {

    private static final Logger LOGGER = Logger.getLogger(ClusterBuildTriggerRelay.class.getName());

    /** Delivery ids of this relay start with this. */
    static final String ID_PREFIX = "build-step-";

    private static final Pattern DELIVERY = Pattern.compile("running/(\\d+)\\.deliver-" + ID_PREFIX + "[A-Za-z0-9-]+");

    /** Retries of a delivery that cannot be handled yet, at most this far apart. */
    private static final long MAX_RETRY_SECONDS = 60;

    /** Handles deliveries off the store's event thread; a delivery may wait for a Pipeline to list its steps. */
    private final ExecutorService deliveries = Executors.newSingleThreadExecutor(
            new NamingThreadFactory(new DaemonThreadFactory(), "ClusterBuildTriggerRelay"));

    @CheckForNull
    static ClusterStore store() {
        return ClusterStore.get();
    }

    static ClusterBuildTriggerRelay get() {
        return ExtensionList.lookupSingleton(ClusterBuildTriggerRelay.class);
    }

    /** A parsed return address. */
    record Address(String uuid, int number, String nodeId) {
        @CheckForNull
        static Address parse(String address) {
            String[] parts = address.split("#");
            if (parts.length != 3 || parts[0].isEmpty() || !parts[2].matches("[A-Za-z0-9-]+")) {
                return null;
            }
            try {
                return new Address(parts[0], Integer.parseInt(parts[1]), parts[2]);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        String itemPrefix() {
            return Keys.itemByUuid(uuid);
        }
    }

    @CheckForNull
    @Override
    public String returnAddress(@NonNull Run<?, ?> upstream, @NonNull String nodeId) {
        if (store() == null || !nodeId.matches("[A-Za-z0-9-]+")) {
            return null;
        }
        String uuid = upstream.getParent().getItemUuid();
        return uuid == null || uuid.contains("#") ? null : uuid + "#" + upstream.getNumber() + "#" + nodeId;
    }

    /** The upstream build, if it runs on this controller. */
    @CheckForNull
    static Run<?, ?> runningHere(String uuid, int number) {
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            if (!(ItemUuids.get(uuid) instanceof Job<?, ?> job)) {
                return null;
            }
            Run<?, ?> run = job.getBuildByNumber(number);
            return run != null && run.isLogUpdated() && ExternalBuilds.get().get(job, number) == null ? run : null;
        }
    }

    @Override
    public boolean relay(
            @NonNull String returnAddress, boolean waitForStart, @NonNull Run<?, ?> downstream, @NonNull Event event) {
        ClusterStore store = store();
        Address a = Address.parse(returnAddress);
        if (store == null || a == null || runningHere(a.uuid(), a.number()) != null) {
            return false; // here: as on a single controller
        }
        String key = Keys.delivery(
                a.itemPrefix(),
                a.number(),
                ID_PREFIX + a.nodeId() + "-" + event.name().toLowerCase(Locale.ROOT) + "-"
                        + Integer.toHexString(downstream.getExternalizableId().hashCode()));
        Properties p = new Properties();
        p.setProperty("nodeId", a.nodeId());
        p.setProperty("event", event.name());
        p.setProperty("downstream", downstream.getExternalizableId());
        Result result = downstream.getResult();
        if (result != null) {
            p.setProperty("result", result.toString());
        }
        p.setProperty("waitForStart", String.valueOf(waitForStart));
        StringWriter w = new StringWriter();
        try {
            p.store(w, null);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        write(store, key, w.toString().getBytes(StandardCharsets.UTF_8), 0);
        LOGGER.fine(() -> "Relayed " + event + " of " + downstream + " to " + returnAddress);
        return true;
    }

    private void write(ClusterStore store, String key, byte[] content, int attempt) {
        try {
            store.write(key, content, PersistenceStore.ANY);
        } catch (IOException e) {
            if (attempt == 10) {
                LOGGER.log(Level.WARNING, "Gave up storing " + key, e);
                return;
            }
            LOGGER.log(Level.WARNING, "Could not store " + key + "; trying again", e);
            Timer.get().schedule(() -> write(store, key, content, attempt + 1), 5L * (attempt + 1), TimeUnit.SECONDS);
        }
    }

    @Override
    public boolean isTakenElsewhere(@NonNull Queue.LeftItem item) {
        return item.isTakenElsewhere();
    }

    @Override
    public boolean requestStop(@NonNull Job<?, ?> job, int number, @NonNull String by) {
        ExternalBuild build = ExternalBuilds.get().get(job, number);
        if (build == null) {
            return false;
        }
        try {
            build.requestStop(by);
            return true;
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not ask for " + job.getFullName() + " #" + number + " to stop", e);
            return false;
        }
    }

    @Override
    public boolean pinsToOrigin(@NonNull Queue.Item item) {
        return BuildTriggerRelay.waitsForStart(item);
    }

    // ------------------------------------------------------------------ inbound

    @Override
    public boolean handles(@NonNull String key) {
        Keys.ItemKey k = Keys.parse(key);
        return k != null && DELIVERY.matcher(k.rest()).matches();
    }

    @Override
    public void changed(@NonNull String key, boolean deleted) {
        if (!deleted) {
            deliveries.submit(() -> handle(key, 0));
        }
    }

    /** Waits until the deliveries handed over so far are handled, or put off (tests). */
    void flush() throws Exception {
        deliveries.submit(() -> {}).get(60, TimeUnit.SECONDS);
    }

    /** Completes the step a delivery is for, if its build runs here. */
    void handle(String key, int attempt) {
        ClusterStore store = store();
        Keys.ItemKey k = Keys.parse(key);
        Matcher m = k == null ? null : DELIVERY.matcher(k.rest());
        if (store == null || m == null || !m.matches()) {
            return;
        }
        Run<?, ?> upstream = runningHere(k.uuid(), Integer.parseInt(m.group(1)));
        if (upstream == null) {
            return; // whichever controller runs it handles it, now or when it adopts it
        }
        boolean done;
        PersistenceStore.Entry entry;
        try {
            entry = store.read(key);
            if (entry == null) {
                return; // handled already
            }
            Properties p = new Properties();
            p.load(new StringReader(new String(entry.content(), StandardCharsets.UTF_8)));
            String result = p.getProperty("result");
            try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
                done = BuildTriggerStepExecution.deliver(
                        upstream,
                        p.getProperty("nodeId", ""),
                        Event.valueOf(p.getProperty("event")),
                        p.getProperty("downstream", ""),
                        result == null ? null : Result.fromString(result),
                        Boolean.parseBoolean(p.getProperty("waitForStart")));
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not deliver " + key, e);
            done = false;
            entry = null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (done) {
            try {
                store.delete(key, entry.version());
            } catch (ConflictException e) {
                deliveries.submit(() -> handle(key, 0)); // delivered again meanwhile
            } catch (IOException e) {
                LOGGER.log(Level.FINE, "Could not delete " + key + "; it goes when the build completes", e);
            }
            return;
        }
        if (attempt == 30) {
            LOGGER.warning(() -> "Still cannot deliver " + key + " to " + upstream + "; trying again while it runs");
        }
        long delay = Math.min(MAX_RETRY_SECONDS, 2L * (attempt + 1));
        Timer.get().schedule(() -> deliveries.submit(() -> handle(key, attempt + 1)), delay, TimeUnit.SECONDS);
    }

    /** Handles what was delivered to a build while no controller ran it. */
    void handlePending(Run<?, ?> run) {
        ClusterStore store = store();
        String uuid = store == null ? null : run.getParent().getItemUuid();
        if (uuid == null) {
            return;
        }
        String running = Keys.running(Keys.itemByUuid(uuid));
        try {
            for (String child : store.children(running)) {
                String key = running + "/" + child;
                if (child.startsWith(run.getNumber() + ".deliver-" + ID_PREFIX) && handles(key)) {
                    deliveries.submit(() -> handle(key, 0));
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not list what was delivered to " + run, e);
        }
    }

    /** A build resumed here (after a restart, or adopted) gets what was delivered to it meanwhile. */
    @Extension(optional = true)
    @Restricted(NoExternalUse.class)
    public static final class Resumed extends FlowExecutionListener {
        @Override
        public void onResumed(@NonNull FlowExecution execution) {
            if (store() == null) {
                return;
            }
            try {
                if (execution.getOwner().getExecutable() instanceof Run<?, ?> run) {
                    get().handlePending(run);
                }
            } catch (IOException e) {
                LOGGER.log(Level.FINE, "Not a build", e);
            }
        }
    }

    /** Deliveries left for a completed build are of no use. */
    @Extension(optional = true)
    @Restricted(NoExternalUse.class)
    public static final class Completed extends RunListener<Run<?, ?>> {
        @Override
        public void onCompleted(Run<?, ?> run, @NonNull TaskListener listener) {
            ClusterStore store = store();
            String uuid = store == null || !(run instanceof FlowExecutionOwner.Executable)
                    ? null
                    : run.getParent().getItemUuid();
            if (uuid == null) {
                return;
            }
            Timer.get().submit(() -> {
                String running = Keys.running(Keys.itemByUuid(uuid));
                try {
                    for (String child : store.children(running)) {
                        if (child.startsWith(run.getNumber() + ".deliver-" + ID_PREFIX)) {
                            store.delete(running + "/" + child, PersistenceStore.ANY);
                        }
                    }
                } catch (IOException e) {
                    LOGGER.log(Level.FINE, "Could not clean up what was delivered to " + run, e);
                }
            });
        }
    }
}
