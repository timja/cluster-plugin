package io.jenkins.plugins.cluster.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Action;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.FreeStyleProject;
import hudson.model.Label;
import hudson.model.Queue;
import hudson.slaves.DumbSlave;
import hudson.slaves.OfflineCause;
import io.jenkins.plugins.cluster.ClusterFixture;
import io.jenkins.plugins.cluster.agents.LaunchLease;
import io.jenkins.plugins.cluster.spi.ClusterQueue;
import io.jenkins.plugins.cluster.spi.InMemoryClusterStore;
import java.util.List;
import jenkins.model.queue.QueueStartGate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * An agent connected by one controller (it holds its launch lease) is offline on the others: only that controller can
 * tell whether it is busy, so it provisions for the items its label serves, wherever they were scheduled.
 */
@WithJenkins
class LeasedAgentProvisioningTest {

    @BeforeAll
    static void cluster() {
        ClusterFixture.activate();
    }

    @AfterAll
    static void noCluster() {
        ClusterFixture.deactivate();
    }

    private JenkinsRule r;
    private InMemoryClusterStore store;
    private InMemoryClusterStore other;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
        store = ClusterFixture.store();
        other = store.as("other");
    }

    private Queue.BuildableItem awaitBuildable(FreeStyleProject p) throws Exception {
        ClusterFixture.await(
                "buildable and shared",
                () -> r.jenkins.getQueue().getItem(p) instanceof Queue.BuildableItem bi
                        && store.queue().get(bi.getId()) != null);
        return (Queue.BuildableItem) r.jenkins.getQueue().getItem(p);
    }

    @Test
    void anAgentConnectedElsewhereLeavesProvisioningForItsLabelToThatController() throws Exception {
        assertTrue(other.launchLeases().acquire("agent") > 0);
        DumbSlave agent = r.createSlave("agent", "leased", null);
        ClusterFixture.await(
                "refused to launch",
                () -> agent.toComputer().getOfflineCause() instanceof LaunchLease.ConnectedElsewhere);
        FreeStyleProject p = r.createFreeStyleProject("p");
        p.setAssignedLabel(Label.get("leased"));
        p.scheduleBuild2(0, new Cause.UserIdCause());
        Queue.BuildableItem item = awaitBuildable(p);

        // scheduled here, but the agent is busy or idle on the other controller, which alone can tell
        ClusterFixture.await("left to the controller connecting the agent", () -> !QueueStartGate.provisionsFor(item));
        assertEquals(0, Label.get("leased").loadStatistics.computeSnapshot().getQueueLength());

        other.gone("other", true); // its lease lapses: this controller provisions for its own item again
        ClusterFixture.await("provisioned for here", () -> QueueStartGate.provisionsFor(item));
    }

    @Test
    void theControllerConnectingTheAgentProvisionsForItemsScheduledElsewhere() throws Exception {
        DumbSlave agent = r.createOnlineSlave(Label.get("mine"));
        assertEquals(
                store.getOrigin(),
                store.launchLeases().holder(agent.getNodeName()).origin());
        // busy, as far as the queue is concerned; still connected, with its lease
        agent.toComputer().setTemporaryOfflineCause(new OfflineCause.ByCLI("busy"));
        FreeStyleProject p = r.createFreeStyleProject("q");
        p.setAssignedLabel(Label.get("mine"));
        long id = other.queue().nextId();
        List<Action> actions = List.of(new CauseAction(new Cause.RemoteCause("elsewhere", "note")));
        other.queue()
                .publish(new ClusterQueue.Record(
                        id,
                        p.getItemUuid(),
                        p.getFullName(),
                        SharedQueue.dedupKey(p, actions),
                        SharedQueue.toXml(actions),
                        System.currentTimeMillis(),
                        ClusterQueue.State.WAITING,
                        "other",
                        null,
                        null,
                        0));
        Queue.BuildableItem item = awaitBuildable(p);
        assertEquals(id, item.getId());
        assertInstanceOf(Queue.BuildableItem.class, item);
        // its origin is alive, and would provision for it, but sees the agent offline
        ClusterFixture.await("provisioned for here", () -> QueueStartGate.provisionsFor(item));
        assertEquals(1, Label.get("mine").loadStatistics.computeSnapshot().getQueueLength());
    }
}
