package io.jenkins.plugins.cluster.agents;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Computer;
import hudson.slaves.DumbSlave;
import hudson.slaves.JNLPLauncher;
import io.jenkins.plugins.cluster.ClusterFixture;
import io.jenkins.plugins.cluster.spi.InMemoryClusterStore;
import io.jenkins.plugins.cluster.spi.LaunchLeases;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** {@code other} is another controller of the cluster, sharing the store with this one. */
@WithJenkins
class LaunchLeaseTest {

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

    private static void awaitOnline(Computer c) {
        await().atMost(Duration.ofSeconds(60)).until(c::isOnline);
    }

    @Test
    void launchedHereHoldsTheLease() throws Exception {
        DumbSlave agent = j.createOnlineSlave();
        LaunchLeases.Holder h = store.launchLeases().holder(agent.getNodeName());
        assertEquals(store.getOrigin(), h.origin());
        assertEquals(0, other.launchLeases().acquire(agent.getNodeName()), "nobody else launches it");

        agent.toComputer().disconnect(null).get();
        await().atMost(Duration.ofSeconds(60)).until(() -> store.launchLeases().holder(agent.getNodeName()) == null);
    }

    @Test
    void notLaunchedWhileAnotherLiveControllerHoldsIt() throws Exception {
        other.launchLeases().acquire("agent");
        DumbSlave agent = j.createSlave("agent", null, null);
        Computer c = agent.toComputer();
        try {
            c.connect(false).get();
        } catch (Exception expected) {
            // the launch failed
        }
        assertTrue(c.isOffline());
        assertThat(c.getLog(), containsString("Not launching agent: it is connected on other"));
        LaunchLease.ConnectedElsewhere why =
                assertInstanceOf(LaunchLease.ConnectedElsewhere.class, c.getOfflineCause());
        assertEquals("other", why.getController(), "shown where it is connected, not as a launch failure");
        assertEquals("other", store.launchLeases().holder("agent").origin());

        store.gone("other", true); // it died: the next try takes its lease over
        c.connect(false).get();
        awaitOnline(c);
        assertEquals(store.getOrigin(), store.launchLeases().holder("agent").origin());
    }

    @Test
    void disconnectedWhenTheLeaseIsTakenOver() throws Exception {
        DumbSlave agent = j.createOnlineSlave();
        LaunchLease lease = LaunchLease.get();
        assertEquals(0, lease.fence());
        assertTrue(lease.holds(agent.getNodeName()));

        // this controller looked gone to the others, and one of them took the lease
        store.gone(store.getOrigin(), true);
        assertTrue(other.launchLeases().acquire(agent.getNodeName()) > 0);
        store.gone(store.getOrigin(), false);
        assertEquals(1, lease.fence());
        await().atMost(Duration.ofSeconds(60)).until(() -> agent.toComputer().isOffline());
        assertInstanceOf(LaunchLease.LeaseLost.class, agent.toComputer().getOfflineCause());
        assertFalse(lease.holds(agent.getNodeName()));
        assertEquals("other", store.launchLeases().holder(agent.getNodeName()).origin(), "theirs is kept");
    }

    @Test
    void disconnectedWhenThisControllerMayHaveLapsed() throws Exception {
        DumbSlave agent = j.createOnlineSlave();
        store.gone(store.getOrigin(), true); // as the others may have seen it
        try {
            assertEquals(1, LaunchLease.get().fence());
            await().atMost(Duration.ofSeconds(60))
                    .until(() -> agent.toComputer().isOffline());
        } finally {
            store.gone(store.getOrigin(), false);
        }
    }

    @Test
    void deletedNodeReleasesItsLease() throws Exception {
        DumbSlave agent = j.createOnlineSlave();
        String name = agent.getNodeName();
        j.jenkins.removeNode(agent);
        await().atMost(Duration.ofSeconds(60)).until(() -> store.launchLeases().holder(name) == null);
    }

    @Test
    void inboundAgentsAreNotLeased() throws Exception {
        DumbSlave inbound = new DumbSlave("inbound", j.createTmpDir().getPath(), new JNLPLauncher());
        j.jenkins.addNode(inbound);
        assertFalse(LaunchLease.isLeased(inbound.toComputer()));
        assertFalse(LaunchLease.isLeased(j.jenkins.toComputer()));
        assertNull(store.launchLeases().holder("inbound"));
    }
}
