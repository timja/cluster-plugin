package io.jenkins.plugins.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import hudson.slaves.DumbSlave;
import hudson.slaves.JNLPLauncher;
import io.jenkins.plugins.cluster.spi.InMemoryClusterStore;
import io.jenkins.plugins.cluster.spi.Keys;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.CpsFlowExecution;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
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
        p.setDefinition(new CpsFlowDefinition("try { node('remote') {} } finally { node('finally') {} }", true));
        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        String base = Keys.running(Keys.item(p)) + "/1";
        // within seconds of the node step waiting, not after a quarter of a minute
        ClusterFixture.await("the build to be handed over", () -> store().read(base + Handover.SUFFIX) != null);
        assertEquals("other", new String(store().read(base + Handover.SUFFIX).content(), StandardCharsets.UTF_8));
        // the copy here stops where it is, and is let go of: its executor freed, the copy discarded
        ClusterFixture.await(
                "the copy here to be let go of", () -> b.getExecutor() == null && p.getBuildByNumber(1) == null);
        // the build carries on elsewhere: nothing more of it ran here (no finally block), nor did it complete
        assertFalse(((CpsFlowExecution) b.getExecution()).isComplete(), "the program did not run to its end");
        assertNull(b.getResult(), "not completed here");
        assertTrue(Completions.of("p").isEmpty(), "no listener was told it completed: " + Completions.of("p"));
        assertTrue(r.jenkins.getQueue().isEmpty(), "nothing of it waits here");
        assertNotNull(
                store().read(base + Handover.HOPS), "the handover is counted until the build completes elsewhere");
        // its log is closed here, so that its file is not held open
        TaskListener log = b.asFlowExecutionOwner().getListener();
        log.getLogger().println("after it was let go of");
        assertTrue(log.getLogger().checkError(), "its log is closed");
    }

    /** What listeners were told of completed builds, by job. */
    @TestExtension
    public static final class Completions extends RunListener<Run<?, ?>> {
        static final List<String> events = new CopyOnWriteArrayList<>();

        static List<String> of(String job) {
            return events.stream().filter(e -> e.startsWith(job + " ")).toList();
        }

        @Override
        public void onCompleted(Run<?, ?> run, @NonNull TaskListener listener) {
            events.add(run.getParent().getFullName() + " completed " + run.getResult());
        }

        @Override
        public void onFinalized(Run<?, ?> run) {
            events.add(run.getParent().getFullName() + " finalized");
        }
    }
}
