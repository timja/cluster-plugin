package io.jenkins.plugins.cluster.pipeline;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.ExtensionList;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Queue;
import hudson.model.Result;
import io.jenkins.plugins.cluster.ClusterFixture;
import io.jenkins.plugins.cluster.builds.RunningBuilds;
import io.jenkins.plugins.cluster.spi.InMemoryClusterStore;
import io.jenkins.plugins.cluster.spi.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import jenkins.persistence.PersistenceStore;
import jenkins.persistence.RemoteChangeHandler;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.support.steps.build.BuildTriggerRelay;
import org.jenkinsci.plugins.workflow.support.steps.build.DownstreamBuildAction;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** {@code other} is another controller of the cluster, sharing the store with this one. */
@WithJenkins
class ClusterBuildTriggerRelayTest {

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
    private ClusterBuildTriggerRelay relay;
    private FreeStyleProject ds;
    private FreeStyleBuild ds1;

    @BeforeEach
    void setUp(JenkinsRule j) throws Exception {
        this.j = j;
        j.jenkins.setQuietPeriod(0);
        store = ClusterFixture.store();
        other = store.as("other");
        relay = ClusterBuildTriggerRelay.get();
        assertSame(relay, BuildTriggerRelay.get());
        ds = j.createFreeStyleProject("ds");
        ds1 = j.buildAndAssertSuccess(ds);
        ds.setQuietPeriod(9999); // what the step schedules is taken "elsewhere": it never runs here
    }

    @AfterEach
    void tearDown() {
        j.jenkins.getQueue().clear();
    }

    /** An upstream build waiting in its build step, and that step's node. */
    private record Waiting(WorkflowRun run, String nodeId) {
        String delivery(String id) {
            return Keys.delivery(
                    Keys.itemByUuid(run.getParent().getItemUuid()),
                    run.getNumber(),
                    ClusterBuildTriggerRelay.ID_PREFIX + nodeId + "-" + id);
        }
    }

    private Waiting waiting(String script) throws Exception {
        WorkflowJob us = j.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition(script, true));
        WorkflowRun b = us.scheduleBuild2(0).waitForStart();
        await().atMost(Duration.ofSeconds(60)).until(() -> j.jenkins.getQueue().getItems().length == 1);
        DownstreamBuildAction a = b.getAction(DownstreamBuildAction.class);
        return new Waiting(b, a.getDownstreamBuilds().get(0).getFlowNodeId());
    }

    /** Waits until this controller has handled the changes other controllers made so far. */
    private void applied() throws Exception {
        ClusterFixture.awaitApplied();
        relay.flush();
    }

    private static byte[] payload(
            String nodeId, BuildTriggerRelay.Event event, String downstream, String result, boolean waitForStart) {
        return ("nodeId=" + nodeId + "\nevent=" + event + "\ndownstream=" + downstream + "\n"
                        + (result == null ? "" : "result=" + result + "\n") + "waitForStart=" + waitForStart + "\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void returnAddressIsTheUpstreamBuild() {
        assertEquals(ds.getItemUuid() + "#1#3", relay.returnAddress(ds1, "3"));
        assertNull(relay.returnAddress(ds1, "../x"));
    }

    @Test
    void eventForAnUpstreamElsewhereIsStored() throws Exception {
        assertTrue(relay.relay("uuid-gone#4#12", false, ds1, BuildTriggerRelay.Event.COMPLETED));
        String running = Keys.running(Keys.itemByUuid("uuid-gone"));
        String child = store.children(running).iterator().next();
        assertTrue(child.startsWith("4.deliver-build-step-12-completed-"), child);
        String content = new String(store.read(running + "/" + child).content(), StandardCharsets.UTF_8);
        assertTrue(content.contains("downstream=ds\\#1") || content.contains("downstream=ds#1"), content);
        assertTrue(content.contains("result=SUCCESS"), content);
        assertTrue(relay.handles(running + "/" + child));
    }

    @Test
    void eventForAnUpstreamHereIsNotRelayed() throws Exception {
        Waiting w = waiting("build 'ds'");
        String us = w.run().getParent().getItemUuid();
        assertFalse(relay.relay(us + "#1#" + w.nodeId(), false, ds1, BuildTriggerRelay.Event.STARTED));
        assertTrue(
                store.children(Keys.running(Keys.itemByUuid(us))).stream()
                        .noneMatch(child -> child.contains(".deliver-")),
                "nothing delivered: the build's own placeholder only");
    }

    @Test
    void eventOfABuildTakenOverElsewhereIsLeftToTheNewOwner() throws Exception {
        Waiting w = waiting("build 'ds'");
        String us = w.run().getParent().getItemUuid();
        RunningBuilds.lost(ds1); // e.g. handed over to a controller with the agent it waits for
        assertTrue(relay.relay(us + "#1#" + w.nodeId(), false, ds1, BuildTriggerRelay.Event.COMPLETED));
        assertTrue(
                store.children(Keys.running(Keys.itemByUuid(us))).stream()
                        .noneMatch(child -> child.contains(".deliver-")),
                "nothing delivered: the new owner relays the outcome");
        assertTrue(w.run().isBuilding(), "the step still waits");
    }

    @Test
    void completionFromElsewhere() throws Exception {
        Waiting w = waiting("echo \"got ${build('ds').number}\"");
        String key = w.delivery("completed-1");
        other.write(
                key,
                payload(w.nodeId(), BuildTriggerRelay.Event.COMPLETED, "ds#1", "SUCCESS", false),
                PersistenceStore.ABSENT);
        assertEquals(
                List.of(relay),
                ExtensionList.lookup(RemoteChangeHandler.class).stream()
                        .filter(h -> h.handles(key))
                        .toList(),
                "only the relay handles a delivery");
        j.assertLogContains("got 1", j.assertBuildStatusSuccess(j.waitForCompletion(w.run())));
        await().atMost(Duration.ofSeconds(60)).until(() -> store.read(key) == null);
    }

    @Test
    void waitForStartFromElsewhere() throws Exception {
        Waiting w = waiting("echo \"started ${build(job: 'ds', waitForStart: true).number}\"");
        Queue.Item item = j.jenkins.getQueue().getItems()[0];
        assertTrue(relay.pinsToOrigin(item));
        String key = w.delivery("started-1");
        other.write(
                key, payload(w.nodeId(), BuildTriggerRelay.Event.STARTED, "ds#1", null, true), PersistenceStore.ABSENT);
        j.assertLogContains("started 1", j.assertBuildStatusSuccess(j.waitForCompletion(w.run())));
    }

    @Test
    void successIsRetriedUntilTheRecordIsHere() throws Exception {
        Waiting w = waiting("build(job: 'ds', propagate: false)");
        String key = w.delivery("completed-9");
        other.write(
                key,
                payload(w.nodeId(), BuildTriggerRelay.Event.COMPLETED, "ds#9", "SUCCESS", false),
                PersistenceStore.ABSENT);
        applied();
        assertNotNull(store.read(key), "kept for another try");
        assertTrue(w.run().isBuilding());
        // the failure of another downstream build needs no record
        String failed = w.delivery("completed-8");
        other.write(
                failed,
                payload(w.nodeId(), BuildTriggerRelay.Event.DELETED, "ds#8", null, false),
                PersistenceStore.ABSENT);
        j.assertBuildStatus(Result.FAILURE, j.waitForCompletion(w.run()));
        j.assertLogContains("ds #8 was deleted", w.run());
    }

    @Test
    void deliveredWhileNoControllerRanTheBuild() throws Exception {
        Waiting w = waiting("build 'ds'");
        String key = w.delivery("completed-1");
        // stored with no event for this controller, as one delivered before it ran the build
        store.write(
                key,
                payload(w.nodeId(), BuildTriggerRelay.Event.COMPLETED, "ds#1", "FAILURE", false),
                PersistenceStore.ABSENT);
        relay.handlePending(w.run()); // as when resumed here
        j.assertBuildStatus(Result.FAILURE, j.waitForCompletion(w.run()));
    }

    @Test
    void notForAnotherController() throws Exception {
        String key = Keys.delivery(
                Keys.itemByUuid("uuid-elsewhere"), 3, ClusterBuildTriggerRelay.ID_PREFIX + "5-completed-1");
        other.write(
                key,
                payload("5", BuildTriggerRelay.Event.COMPLETED, "ds#1", "SUCCESS", false),
                PersistenceStore.ABSENT);
        applied();
        assertNotNull(store.read(key), "left for the controller running it");
        assertFalse(relay.handles(Keys.placeholder(Keys.itemByUuid("u"), 3)));
        assertFalse(relay.handles(Keys.delivery(Keys.itemByUuid("u"), 3, "other-thing")));
    }
}
