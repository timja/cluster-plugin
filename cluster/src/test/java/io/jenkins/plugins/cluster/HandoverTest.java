package io.jenkins.plugins.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import hudson.model.Result;
import hudson.slaves.DumbSlave;
import hudson.slaves.JNLPLauncher;
import io.jenkins.plugins.cluster.spi.InMemoryClusterStore;
import io.jenkins.plugins.cluster.spi.Keys;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** A Pipeline waiting for an agent only another controller has is handed over to that controller. */
@WithJenkins
class HandoverTest {

    @BeforeAll
    static void cluster() {
        ClusterFixture.activate();
    }

    @AfterAll
    static void noCluster() {
        ClusterFixture.deactivate();
    }

    private static InMemoryClusterStore store() {
        return ClusterFixture.store();
    }

    @Test
    void aPipelineWaitingForAnAgentOfAnotherControllerIsHandedOverAsSoonAsItWaits(JenkinsRule r) throws Exception {
        // an agent with the label is defined here, but connected to another controller
        DumbSlave agent =
                new DumbSlave("remote", Files.createTempDirectory("remote").toString(), new JNLPLauncher());
        agent.setLabelString("remote");
        r.jenkins.addNode(agent);
        store().as("other").channelConnected("remote");
        WorkflowJob p = r.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("node('remote') {}", true));
        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        String base = Keys.running(Keys.item(p)) + "/1";
        // within seconds of the node step waiting, not after a quarter of a minute
        ClusterFixture.await("the build to be handed over", () -> store().read(base + Handover.SUFFIX) != null);
        assertEquals("other", new String(store().read(base + Handover.SUFFIX).content(), StandardCharsets.UTF_8));
        r.waitForCompletion(b);
        // the copy here stopped, but the build carries on elsewhere: neither aborted, nor its handovers forgotten
        assertEquals(Result.NOT_BUILT, b.getResult());
        assertNotNull(
                store().read(base + Handover.HOPS), "the handover is counted until the build completes elsewhere");
    }
}
