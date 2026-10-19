package io.jenkins.plugins.cluster.agents;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Computer;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Label;
import hudson.slaves.DumbSlave;
import hudson.slaves.JNLPLauncher;
import io.jenkins.plugins.cluster.ClusterFixture;
import io.jenkins.plugins.cluster.spi.InMemoryClusterStore;
import io.jenkins.plugins.cluster.spi.Keys;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import jenkins.model.ComputersElsewhere;
import jenkins.persistence.PersistenceStore;
import net.sf.json.JSONObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestBuilder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * An agent connected to one controller is shown online on the others, with what its executors do there, while those
 * never schedule on it. {@code other} is another controller of the cluster, sharing the store with this one.
 */
@WithJenkins
class AgentsElsewhereTest {

    @BeforeAll
    static void cluster() {
        ClusterFixture.activate();
    }

    @AfterAll
    static void noCluster() {
        ClusterFixture.deactivate();
    }

    private JenkinsRule j;
    private InMemoryClusterStore store;
    private InMemoryClusterStore other;

    @BeforeEach
    void setUp(JenkinsRule j) {
        this.j = j;
        store = ClusterFixture.store();
        other = store.as("other");
    }

    /** Blocks a build until released. */
    private static final class Blocking extends TestBuilder {
        private final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);

        @Override
        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
                throws InterruptedException {
            return release.await(60, TimeUnit.SECONDS);
        }
    }

    private JSONObject published(String agent) throws Exception {
        PersistenceStore.Entry e = other.read(Keys.agentStatus(agent));
        return e == null ? null : JSONObject.fromObject(new String(e.content(), StandardCharsets.UTF_8));
    }

    @Test
    void whatAnAgentConnectedHereDoesIsPublishedAndWithdrawnWhenItDisconnects() throws Exception {
        DumbSlave agent = j.createOnlineSlave(Label.get("here"));
        String name = agent.getNodeName();
        ClusterFixture.await(
                "published idle",
                () -> published(name) != null
                        && published(name).getJSONArray("busy").isEmpty());
        JSONObject idle = published(name);
        assertEquals(store.getOrigin(), idle.getString("origin"));
        assertEquals(1, idle.getInt("executors"));

        FreeStyleProject p = j.createFreeStyleProject("p");
        p.setAssignedLabel(Label.get("here"));
        Blocking blocking = new Blocking();
        p.getBuildersList().add(blocking);
        FreeStyleBuild b = p.scheduleBuild2(0).waitForStart();
        ClusterFixture.await(
                "published busy", () -> published(name).getJSONArray("busy").size() == 1);
        JSONObject busy = published(name).getJSONArray("busy").getJSONObject(0);
        assertEquals("p", busy.getString("job"));
        assertEquals(b.getNumber(), busy.getInt("build"));
        assertTrue(busy.getLong("startedAt") > 0);

        blocking.release.countDown();
        j.waitForCompletion(b);
        ClusterFixture.await(
                "published idle again",
                () -> published(name).getJSONArray("busy").isEmpty());

        agent.toComputer().disconnect(null).get();
        ClusterFixture.await("withdrawn", () -> published(name) == null);
    }

    @Test
    void writesAreCoalescedAndOnlyMadeOnChange() throws Exception {
        DumbSlave agent = j.createOnlineSlave();
        String key = Keys.agentStatus(agent.getNodeName());
        List<Long> writes = new CopyOnWriteArrayList<>();
        try (AutoCloseable s = store.subscribe(e -> {
            if (e.key().equals(key) && e.kind() == PersistenceStore.ChangeEvent.Kind.WRITTEN) {
                writes.add(System.currentTimeMillis());
            }
        })) {
            ClusterFixture.await("published", () -> other.read(key) != null);
            long version = other.read(key).version();
            AgentsElsewhere elsewhere = AgentsElsewhere.get();
            for (int i = 0; i < 20; i++) {
                elsewhere.changed(agent.toComputer());
                elsewhere.publish(agent.getNodeName());
            }
            assertEquals(version, other.read(key).version(), "nothing changed: nothing written");

            // two changes in a row: the second is written no sooner than the interval after the first
            int before = writes.size();
            resize(agent, 2);
            ClusterFixture.await(
                    "2 executors", () -> published(agent.getNodeName()).getInt("executors") == 2);
            resize(agent, 3);
            ClusterFixture.await(
                    "3 executors", () -> published(agent.getNodeName()).getInt("executors") == 3);
            assertTrue(writes.size() - before <= 2, "one write per change: " + writes);
            long gap = writes.get(writes.size() - 1) - writes.get(writes.size() - 2);
            assertTrue(gap >= AgentsElsewhere.INTERVAL_MS - 100, "written " + gap + " ms apart");
        }
    }

    /** Reconfigures an agent with another number of executors, as its configuration page does. */
    private void resize(DumbSlave agent, int executors) throws Exception {
        DumbSlave now = new DumbSlave(agent.getNodeName(), agent.getRemoteFS(), agent.getLauncher());
        now.setNumExecutors(executors);
        now.setRetentionStrategy(agent.getRetentionStrategy());
        j.jenkins.getNodesObject().replaceNode(j.jenkins.getNode(agent.getNodeName()), now);
    }

    private static String status(String origin, int executors, String job, int build) {
        JSONObject o = new JSONObject();
        o.put("origin", origin);
        o.put("executors", executors);
        net.sf.json.JSONArray busy = new net.sf.json.JSONArray();
        if (job != null) {
            JSONObject b = new JSONObject();
            b.put("job", job);
            b.put("build", build);
            b.put("startedAt", System.currentTimeMillis());
            b.put("estimatedDuration", 60_000);
            busy.add(b);
        }
        o.put("busy", busy);
        return o.toString();
    }

    /** An agent that the other controller connects: this one refuses to launch it. */
    private Computer connectedOnOther(String name, String label) throws Exception {
        assertTrue(other.launchLeases().acquire(name) > 0);
        DumbSlave agent = j.createSlave(name, label, null);
        Computer c = agent.toComputer();
        ClusterFixture.await(
                "refused to launch",
                () -> c.getOfflineCause() instanceof LaunchLease.ConnectedElsewhere && !c.isConnecting());
        return c;
    }

    @Test
    void anAgentConnectedOnAnotherControllerIsShownOnlineWithWhatItRunsThere() throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("p");
        Computer c = connectedOnOther("remote", "remote");
        other.write(
                Keys.agentStatus("remote"),
                status("other", 2, "p", 7).getBytes(StandardCharsets.UTF_8),
                PersistenceStore.ANY);
        ClusterFixture.await("known here", () -> c.getConnectedElsewhere() != null);
        ComputersElsewhere.Connection e = c.getConnectedElsewhere();
        assertEquals("other", e.getWhere());
        assertEquals(2, e.getExecutors());
        assertEquals(1, e.countBusy());
        assertEquals(p, e.getBusy().get(0).getJob());
        assertEquals(7, e.getBusy().get(0).getBuild());
        assertTrue(c.isOffline(), "still offline for scheduling");
        assertEquals("symbol-computer", c.getIcon());
        assertThat(c.getTooltip(), not(containsString("offline")));

        String widget = j.createWebClient().goTo("").getElementById("executors").asNormalizedText();
        assertThat(widget, containsString("remote"));
        assertThat(widget, containsString("1/2"));
        assertThat(widget, not(containsString("offline")));

        // it finished there
        other.write(
                Keys.agentStatus("remote"),
                status("other", 2, null, 0).getBytes(StandardCharsets.UTF_8),
                PersistenceStore.ANY);
        ClusterFixture.await("idle", () -> c.getConnectedElsewhere().countBusy() == 0);

        // and disconnected there
        other.delete(Keys.agentStatus("remote"), PersistenceStore.ANY);
        ClusterFixture.await("offline", () -> c.getConnectedElsewhere() == null);
        assertNotNull(c.getOfflineCause());
    }

    @Test
    void anAgentWhoseControllerIsGoneIsOffline() throws Exception {
        // inbound: this controller does not launch it once the other is gone
        DumbSlave inbound = new DumbSlave("remote", j.createTmpDir().getPath(), new JNLPLauncher());
        j.jenkins.addNode(inbound);
        Computer c = inbound.toComputer();
        other.write(
                Keys.agentStatus("remote"),
                status("other", 1, null, 0).getBytes(StandardCharsets.UTF_8),
                PersistenceStore.ANY);
        ClusterFixture.await("known here", () -> c.getConnectedElsewhere() != null);
        store.gone("other", true);
        try {
            ClusterFixture.await("offline", () -> c.getConnectedElsewhere() == null);
            assertFalse(c.isConnected());
        } finally {
            store.gone("other", false);
        }
    }

    @Test
    void nothingIsScheduledHereOnAnAgentConnectedElsewhere() throws Exception {
        j.jenkins.setNumExecutors(0);
        Computer c = connectedOnOther("remote", "remote");
        other.write(
                Keys.agentStatus("remote"),
                status("other", 1, null, 0).getBytes(StandardCharsets.UTF_8),
                PersistenceStore.ANY);
        ClusterFixture.await("shown online", () -> c.getConnectedElsewhere() != null);
        FreeStyleProject p = j.createFreeStyleProject("q");
        p.setAssignedLabel(Label.get("remote"));
        AtomicInteger started = new AtomicInteger();
        p.getBuildersList().add(new TestBuilder() {
            @Override
            public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) {
                started.incrementAndGet();
                return true;
            }
        });
        p.scheduleBuild2(0);
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) { // the queue is maintained throughout
            j.jenkins.getQueue().maintain();
            assertTrue(c.isOffline());
            assertTrue(c.isIdle());
            assertNull(p.getLastBuild(), "never started here");
            Thread.sleep(100);
        }
        assertEquals(0, started.get());
        assertNotNull(c.getConnectedElsewhere(), "shown online throughout");
    }

    @Test
    void aStatusPublishedHereIsNotShownElsewhereOnlyHere() throws Exception {
        DumbSlave agent = j.createOnlineSlave();
        ClusterFixture.await("published", () -> published(agent.getNodeName()) != null);
        assertNull(agent.toComputer().getConnectedElsewhere(), "connected here: shown as it is");
        assertNull(AgentsElsewhere.get().connectedElsewhere(agent.toComputer()), "its own status is not elsewhere");
        assertFalse(AgentsElsewhere.published(j.jenkins.toComputer()));
    }

    @Test
    void keysOfOtherEntriesAreNotStatuses() {
        assertEquals("mock-1", AgentsElsewhere.agentOf("_nodes/mock-1/status"));
        assertNull(AgentsElsewhere.agentOf("_nodes/mock-1/launch"));
        assertNull(AgentsElsewhere.agentOf("_nodes/../status"));
        assertNull(AgentsElsewhere.agentOf("_nodes/a/b/status"));
        assertNull(AgentsElsewhere.agentOf("_items/x/status"));
    }
}
