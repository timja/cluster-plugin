package io.jenkins.plugins.cluster.agents;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.ExecutorListener;
import hudson.model.Job;
import hudson.model.Node;
import hudson.model.PeriodicWork;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.queue.WorkUnit;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.ComputerListener;
import hudson.slaves.EphemeralNode;
import hudson.slaves.OfflineCause;
import hudson.slaves.SlaveComputer;
import io.jenkins.plugins.cluster.spi.ClusterStore;
import io.jenkins.plugins.cluster.spi.Keys;
import io.jenkins.plugins.cluster.spi.Membership;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.ComputersElsewhere;
import jenkins.model.Jenkins;
import jenkins.model.NodeListener;
import jenkins.persistence.ConflictException;
import jenkins.persistence.PersistenceStore;
import jenkins.util.Timer;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Shows an agent connected to another controller of the cluster as online, with its executors as they are there,
 * rather than as offline: which controller connects an agent does not matter to the user. For display only: the agent
 * stays offline here, so this controller never schedules on it (its work goes to that controller through the shared
 * queue).
 *
 * <p>The controller an agent is connected to publishes what it is doing, {@link Keys#agentStatus}: its executors and,
 * for each busy one, the job and build it runs. That is written only when it changes, at most every
 * {@link #INTERVAL_MS} per agent, and removed when the agent goes offline there. The other controllers keep the latest
 * of these in memory, as the store's change events bring them. An agent whose publishing controller is gone is offline
 * again. Cloud agents are not published: they are only known to the controller that provisioned them.
 */
@Extension
@Restricted(NoExternalUse.class)
public final class AgentsElsewhere implements ComputersElsewhere {

    private static final Logger LOGGER = Logger.getLogger(AgentsElsewhere.class.getName());

    /** The shortest time between two writes of an agent's status. */
    static final long INTERVAL_MS = Long.getLong(AgentsElsewhere.class.getName() + ".intervalMs", 2_000);

    /** How long after a change its status is written at the soonest: an executor finishing is still busy when told. */
    static final long DELAY_MS = 500;

    /** How long who is alive is remembered. */
    private static final long MEMBERS_MS = 2_000;

    static AgentsElsewhere get() {
        return ExtensionList.lookupSingleton(AgentsElsewhere.class);
    }

    // ------------------------------------------------------------------ where the agent is connected

    /** What was last written here, by agent. */
    private final Map<String, Written> written = new ConcurrentHashMap<>();

    /** Agents whose status is due to be written. */
    private final Set<String> due = ConcurrentHashMap.newKeySet();

    private record Written(String content, long version, long at) {}

    /** Whether what the agent of this computer does is published when it is connected here. */
    static boolean published(@NonNull Computer c) {
        if (!(c instanceof SlaveComputer)) {
            return false;
        }
        Node node = c.getNode();
        return node != null && !(node instanceof AbstractCloudSlave) && !(node instanceof EphemeralNode);
    }

    /** Writes the status of an agent connected here soon, unless it was written less than {@link #INTERVAL_MS} ago. */
    void changed(@CheckForNull Computer c) {
        if (c == null || ClusterStore.get() == null || !published(c) || !due.add(c.getName())) {
            return;
        }
        Written w = written.get(c.getName());
        long wait = w == null ? DELAY_MS : Math.max(DELAY_MS, w.at + INTERVAL_MS - System.currentTimeMillis());
        Timer.get().schedule(() -> publish(c.getName()), wait, TimeUnit.MILLISECONDS);
    }

    /** Writes the status of an agent connected here, if it changed; withdraws it if it is no longer connected. */
    void publish(@NonNull String name) {
        due.remove(name);
        ClusterStore store = ClusterStore.get();
        if (store == null) {
            return;
        }
        Computer c = Jenkins.get().getComputer(name);
        if (c == null || !c.isConnected() || !published(c)) {
            withdraw(name);
            return;
        }
        String content = statusOf(store, c);
        Written w = written.get(name);
        if (w != null && w.content.equals(content)) {
            return;
        }
        try {
            long version =
                    store.write(Keys.agentStatus(name), content.getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
            written.put(name, new Written(content, version, System.currentTimeMillis()));
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Could not publish what " + name + " is doing", e);
        }
    }

    /** Removes the status this controller wrote of an agent, unless another controller wrote one since. */
    void withdraw(@NonNull String name) {
        Written w = written.remove(name);
        ClusterStore store = ClusterStore.get();
        if (w == null || store == null) {
            return;
        }
        try {
            store.delete(Keys.agentStatus(name), w.version);
        } catch (ConflictException e) {
            // another controller connects it now, and said so
        } catch (IOException e) {
            LOGGER.log(
                    Level.FINE,
                    "Could not withdraw what " + name + " was doing; it shows offline once this controller is gone",
                    e);
        }
    }

    /** Checks every agent connected here, for changes no event told of. */
    void publishAll() {
        for (Computer c : Jenkins.get().getComputers()) {
            if (c.isConnected() && published(c)) {
                changed(c);
            }
        }
        for (String name : written.keySet()) {
            Computer c = Jenkins.get().getComputer(name);
            if (c == null || !c.isConnected()) {
                withdraw(name);
            }
        }
    }

    @NonNull
    static String statusOf(@NonNull ClusterStore store, @NonNull Computer c) {
        JSONArray busy = new JSONArray();
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            for (Executor e : c.getExecutors()) {
                if (!e.isIdle()) {
                    busy.add(busyOf(e));
                }
            }
        }
        JSONObject o = new JSONObject();
        o.put("origin", store.getOrigin());
        o.put("executors", c.countExecutors());
        o.put("busy", busy);
        return o.toString();
    }

    private static JSONObject busyOf(Executor e) {
        JSONObject b = new JSONObject();
        Run<?, ?> run = null;
        for (Queue.Executable x = e.getCurrentExecutable(); x != null; x = x.getParentExecutable()) {
            if (x instanceof Run<?, ?> r) {
                run = r;
                break;
            }
        }
        if (run != null) {
            b.put("job", run.getParent().getFullName());
            b.put("build", run.getNumber());
            b.put("startedAt", run.getStartTimeInMillis());
            b.put("estimatedDuration", run.getEstimatedDuration());
        } else {
            WorkUnit wu = e.getCurrentWorkUnit();
            if (wu != null && wu.work.getOwnerTask() instanceof Job<?, ?> job) {
                b.put("job", job.getFullName()); // not started yet
            }
        }
        return b;
    }

    // ------------------------------------------------------------------ the other controllers

    /** The latest status published by another controller, by agent. */
    private final Map<String, Status> statuses = new ConcurrentHashMap<>();

    private record Status(
            @NonNull String origin, long version, @NonNull Connection connection) {}

    private volatile Map.Entry<Long, Map<String, Membership.Member>> members;

    @Override
    @CheckForNull
    public Connection connectedElsewhere(@NonNull Computer computer) {
        ClusterStore store = ClusterStore.get();
        if (store == null) {
            return null;
        }
        Status s = statuses.get(computer.getName());
        if (s == null || s.origin.equals(store.getOrigin())) {
            return null;
        }
        Membership.Member m = liveMembers(store).get(s.origin);
        if (m == null) {
            return null; // gone: offline, as it is
        }
        Connection c = s.connection;
        return m.name() == null ? c : new Connection(m.name(), c.getExecutors(), c.getBusy());
    }

    /** The live controllers by origin, read at most every {@link #MEMBERS_MS}: this is asked for each agent shown. */
    @NonNull
    private Map<String, Membership.Member> liveMembers(@NonNull ClusterStore store) {
        Map.Entry<Long, Map<String, Membership.Member>> e = members;
        long now = System.currentTimeMillis();
        if (e != null && now - e.getKey() < MEMBERS_MS) {
            return e.getValue();
        }
        Map<String, Membership.Member> live = new HashMap<>();
        try {
            if (store.canJudgeLiveness()) {
                for (Membership.Member m : store.members()) {
                    if (m.alive()) {
                        live.put(m.origin(), m);
                    }
                }
            }
        } catch (IOException x) {
            LOGGER.log(Level.FINE, "Could not tell which controllers are alive", x);
        }
        members = Map.entry(now, live);
        return live;
    }

    /** Takes in a change of an agent's status, as an event tells of it. */
    void changedElsewhere(@NonNull ClusterStore store, @NonNull String name) {
        try {
            PersistenceStore.Entry entry = store.read(Keys.agentStatus(name));
            if (entry == null) {
                statuses.remove(name);
                return;
            }
            Status s = parse(new String(entry.content(), StandardCharsets.UTF_8), entry.version());
            if (s == null) {
                statuses.remove(name);
                return;
            }
            statuses.merge(name, s, (old, now) -> now.version >= old.version ? now : old);
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Could not read what " + name + " is doing", e);
        }
    }

    @CheckForNull
    static Status parse(@NonNull String json, long version) {
        try {
            JSONObject o = JSONObject.fromObject(json);
            String origin = o.optString("origin", null);
            if (origin == null || origin.isEmpty()) {
                return null;
            }
            List<Busy> busy = new ArrayList<>();
            JSONArray a = o.optJSONArray("busy");
            if (a != null) {
                for (int i = 0; i < a.size(); i++) {
                    JSONObject b = a.getJSONObject(i);
                    busy.add(new Busy(
                            b.optString("job", null),
                            b.optInt("build"),
                            b.optLong("startedAt"),
                            b.optLong("estimatedDuration", -1)));
                }
            }
            return new Status(origin, version, new Connection(origin, o.optInt("executors"), busy));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The agent named in a status key, or null for other keys. */
    @CheckForNull
    static String agentOf(@NonNull String key) {
        String prefix = Keys.NODES + "/";
        if (!key.startsWith(prefix) || !key.endsWith(Keys.AGENT_STATUS)) {
            return null;
        }
        String name = key.substring(prefix.length(), key.length() - Keys.AGENT_STATUS.length());
        return Keys.isSafeName(name) ? name : null;
    }

    @Initializer(after = InitMilestone.JOB_LOADED)
    public static void start() {
        ClusterStore store = ClusterStore.get();
        if (store == null) {
            return;
        }
        AgentsElsewhere self = get();
        store.subscribe(e -> {
            String name = agentOf(e.key());
            if (name == null) {
                return;
            }
            if (store.getOrigin().equals(e.origin())) {
                // written or withdrawn here: whatever another controller said before is superseded
                self.statuses.computeIfPresent(name, (k, old) -> old.version < e.version() ? null : old);
            } else {
                Timer.get().submit(() -> self.changedElsewhere(store, name));
            }
        });
        Timer.get().submit(() -> {
            try {
                for (String name : store.children(Keys.NODES)) {
                    if (Keys.isSafeName(name)) {
                        self.changedElsewhere(store, name);
                    }
                }
            } catch (IOException e) {
                LOGGER.log(Level.FINE, "Could not read what agents connected elsewhere are doing", e);
            }
        });
    }

    /** Tells of agents connected and disconnected here. */
    @Extension
    @Restricted(NoExternalUse.class)
    public static final class Connections extends ComputerListener {
        @Override
        public void onOnline(Computer c, TaskListener listener) {
            get().changed(c);
        }

        @Override
        public void onOffline(@NonNull Computer c, @CheckForNull OfflineCause cause) {
            if (ClusterStore.get() != null) {
                get().publish(c.getName()); // withdrawn at once if it is no longer connected
            }
        }
    }

    /** Tells of executors of agents connected here starting and finishing work. */
    @Extension
    @Restricted(NoExternalUse.class)
    public static final class Executors implements ExecutorListener {
        @Override
        public void taskAccepted(Executor executor, Queue.Task task) {
            get().changed(executor.getOwner());
        }

        @Override
        public void taskStarted(Executor executor, Queue.Task task) {
            get().changed(executor.getOwner());
        }

        @Override
        public void taskCompleted(Executor executor, Queue.Task task, long durationMS) {
            get().changed(executor.getOwner());
        }

        @Override
        public void taskCompletedWithProblems(Executor executor, Queue.Task task, long durationMS, Throwable problems) {
            get().changed(executor.getOwner());
        }
    }

    /** Tells of agents reconfigured (such as their number of executors) or deleted. */
    @Extension
    @Restricted(NoExternalUse.class)
    public static final class Nodes extends NodeListener {
        @Override
        protected void onUpdated(@NonNull Node oldOne, @NonNull Node newOne) {
            get().changed(newOne.toComputer());
        }

        @Override
        protected void onDeleted(@NonNull Node node) {
            get().withdraw(node.getNodeName());
        }
    }

    /** Catches changes no event told of, such as an executor that went idle after it was last published. */
    @Extension
    @Restricted(NoExternalUse.class)
    public static final class Check extends PeriodicWork {
        @Override
        public long getRecurrencePeriod() {
            return TimeUnit.SECONDS.toMillis(10);
        }

        @Override
        protected void doRun() {
            if (ClusterStore.get() != null) {
                get().publishAll();
            }
        }
    }
}
