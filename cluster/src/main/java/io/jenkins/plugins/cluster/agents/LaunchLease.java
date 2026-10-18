package io.jenkins.plugins.cluster.agents;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.AbortException;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Computer;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.ComputerListener;
import hudson.slaves.EphemeralNode;
import hudson.slaves.OfflineCause;
import hudson.slaves.SlaveComputer;
import io.jenkins.plugins.cluster.spi.ClusterStore;
import io.jenkins.plugins.cluster.spi.LaunchLeases;
import io.jenkins.plugins.cluster.spi.Membership;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.model.NodeListener;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.export.Exported;

/**
 * Only one controller of the cluster connects a given agent that the controller launches itself (such as an SSH
 * agent): the one holding its {@linkplain LaunchLeases launch lease}. The others refuse to launch it while that holder
 * is alive, and keep it offline; their retention strategy tries again later, which is how a lease of a controller that
 * died is taken over. Inbound agents (which connect wherever the load balancer sends them) and cloud agents (kept by
 * the controller that provisioned them) are not leased.
 *
 * <p>A controller that may have looked gone to the others (its membership lapsed), or that finds its lease taken over,
 * disconnects the agent: another controller may be connecting it.
 */
@Extension
@Restricted(NoExternalUse.class)
public final class LaunchLease extends ComputerListener {

    private static final Logger LOGGER = Logger.getLogger(LaunchLease.class.getName());

    /** The epoch of each lease held here, by node name. */
    private final Map<String, Long> held = new ConcurrentHashMap<>();

    @CheckForNull
    static ClusterStore store() {
        return ClusterStore.get();
    }

    static LaunchLease get() {
        return ExtensionList.lookupSingleton(LaunchLease.class);
    }

    /** Whether the agent of this computer is launched by the controller, and not only by one of them. */
    static boolean isLeased(@NonNull Computer c) {
        if (!(c instanceof SlaveComputer sc) || !sc.isLaunchSupported()) {
            return false; // the built-in node, or an inbound agent
        }
        Node node = c.getNode();
        return node != null && !(node instanceof AbstractCloudSlave) && !(node instanceof EphemeralNode);
    }

    @Override
    public void preLaunch(Computer c, TaskListener taskListener) throws IOException, InterruptedException {
        ClusterStore store = store();
        if (store == null || !isLeased(c)) {
            return;
        }
        String name = c.getName();
        long epoch = store.launchLeases().acquire(name);
        if (epoch == 0) {
            String holder = holderName(store, name);
            // shown instead of a launch failure: nothing failed
            c.setOfflineCause(new ConnectedElsewhere(holder));
            throw new AbortException("Not launching " + name + ": it is connected on " + holder);
        }
        held.put(name, epoch);
    }

    /**
     * The origin of the live controller, other than this one, holding the launch lease of a node this controller has
     * but does not hold, or null.
     */
    @CheckForNull
    public static String holderElsewhere(@NonNull ClusterStore store, @NonNull Computer c) throws IOException {
        if (!isLeased(c) || get().holds(c.getName()) || !store.canJudgeLiveness()) {
            return null;
        }
        LaunchLeases.Holder h = store.launchLeases().holder(c.getName());
        return h == null
                        || h.origin().equals(store.getOrigin())
                        || !store.aliveMembers().contains(h.origin())
                ? null
                : h.origin();
    }

    /**
     * The live controllers, this one included, connecting leased agents that could run work for a label (any agent
     * taking unlabelled work, for null). Only they see whether those agents are busy: the others see them offline.
     */
    @NonNull
    public static Set<String> holdersOf(@CheckForNull Label label) throws IOException {
        ClusterStore store = store();
        if (store == null || !store.canJudgeLiveness()) {
            return Set.of();
        }
        Set<String> live = null;
        Set<String> holders = new TreeSet<>();
        Collection<Node> nodes = label == null ? Jenkins.get().getNodes() : label.getNodes();
        for (Node n : nodes) {
            Computer c = n.toComputer();
            if (c == null || !isLeased(c) || label == null && n.getMode() != Node.Mode.NORMAL) {
                continue;
            }
            LaunchLeases.Holder h = get().recentHolder(store, c.getName());
            if (h == null) {
                continue;
            }
            if (live == null) {
                live = store.aliveMembers();
            }
            if (live.contains(h.origin())) {
                holders.add(h.origin());
            }
        }
        return holders;
    }

    /** Holders read recently, by node: load statistics ask for every queue item, every few seconds. */
    private final Map<String, Map.Entry<Long, Optional<LaunchLeases.Holder>>> recent = new ConcurrentHashMap<>();

    @CheckForNull
    private LaunchLeases.Holder recentHolder(ClusterStore store, String node) throws IOException {
        long now = System.currentTimeMillis();
        Map.Entry<Long, Optional<LaunchLeases.Holder>> e = recent.get(node);
        if (e != null && now - e.getKey() < 2_000) {
            return e.getValue().orElse(null);
        }
        LaunchLeases.Holder h = store.launchLeases().holder(node);
        if (recent.size() > 10_000) {
            recent.clear();
        }
        recent.put(node, Map.entry(now, Optional.ofNullable(h)));
        return h;
    }

    /** The name of the controller holding a node's lease, for messages. */
    @NonNull
    public static String holderName(@NonNull ClusterStore store, @NonNull String node) {
        try {
            LaunchLeases.Holder h = store.launchLeases().holder(node);
            if (h == null) {
                return "another controller";
            }
            for (Membership.Member m : store.members()) {
                if (h.origin().equals(m.origin())) {
                    return m.name();
                }
            }
            return h.origin();
        } catch (IOException e) {
            return "another controller";
        }
    }

    @Override
    public void onLaunchFailure(Computer c, TaskListener taskListener) {
        release(c.getName()); // another controller may have better luck
    }

    @Override
    public void onOffline(@NonNull Computer c, @CheckForNull OfflineCause cause) {
        release(c.getName());
    }

    /** Whether this controller holds the launch lease of a node. */
    boolean holds(String node) {
        return held.containsKey(node);
    }

    void release(String node) {
        Long epoch = held.remove(node);
        ClusterStore store = store();
        if (epoch == null || store == null) {
            return;
        }
        try {
            store.launchLeases().release(node, epoch);
        } catch (IOException e) {
            LOGGER.log(
                    Level.WARNING,
                    "Could not release the launch lease of " + node
                            + "; other controllers take it over once this one is gone",
                    e);
        }
    }

    /**
     * Disconnects the agents whose lease this controller may no longer hold.
     *
     * @return how many were disconnected
     */
    int fence() throws IOException {
        ClusterStore store = store();
        if (store == null || held.isEmpty()) {
            return 0;
        }
        boolean lapsed = store.mayHaveLapsed();
        int n = 0;
        for (Map.Entry<String, Long> e : held.entrySet()) {
            String node = e.getKey();
            LaunchLeases.Holder h = lapsed ? null : store.launchLeases().holder(node);
            if (!lapsed && h != null && h.origin().equals(store.getOrigin()) && h.epoch() == e.getValue()) {
                continue;
            }
            held.remove(node, e.getValue());
            Computer c = Jenkins.get().getComputer(node);
            if (c != null && !c.isOffline()) {
                LOGGER.warning(() -> "Disconnecting " + node + ": "
                        + (lapsed
                                ? "this controller may have looked gone to the others"
                                : "another controller took its launch lease"));
                c.disconnect(new LeaseLost());
                n++;
            }
        }
        return n;
    }

    /** Why an agent is offline here: another controller connects it. */
    public static final class ConnectedElsewhere extends OfflineCause {
        private final String controller;

        ConnectedElsewhere(String controller) {
            this.controller = controller;
        }

        /** The name of the controller connecting the agent. */
        public String getController() {
            return controller;
        }

        @Exported(name = "description")
        @Override
        public String toString() {
            return "Connected on " + controller + ", another controller of the cluster";
        }
    }

    /** Why an agent was disconnected. */
    public static final class LeaseLost extends OfflineCause {
        @Override
        public String toString() {
            return "Another controller of the cluster may be connecting this agent";
        }
    }

    /** Checks the leases held here. */
    @Extension
    @Restricted(NoExternalUse.class)
    public static final class Fencing extends AsyncPeriodicWork {
        public Fencing() {
            super("Check the launch leases of agents");
        }

        @Override
        public long getRecurrencePeriod() {
            return TimeUnit.SECONDS.toMillis(15);
        }

        @Override
        protected void execute(TaskListener listener) throws IOException {
            get().fence();
        }
    }

    /** A deleted node's lease goes with it. */
    @Extension
    @Restricted(NoExternalUse.class)
    public static final class Deleted extends NodeListener {
        @Override
        protected void onDeleted(@NonNull Node node) {
            get().release(node.getNodeName());
        }
    }
}
