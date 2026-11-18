package io.jenkins.plugins.cluster.locks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.cluster.ClusterFixture;
import io.jenkins.plugins.cluster.spi.InMemoryClusterStore;
import io.jenkins.plugins.cluster.spi.Keys;
import java.nio.charset.StandardCharsets;
import java.util.List;
import jenkins.persistence.PersistenceStore;
import org.jenkins.plugins.lockableresources.LockableResource;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.jenkins.plugins.lockableresources.ResourceState;
import org.jenkins.plugins.lockableresources.ResourceStateStore;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * {@code other} is another controller of the cluster, sharing the store with this one. Across real controllers, killed
 * and restarted, see {@code LocksAcrossControllersTest} in cluster-postgresql.
 */
@WithJenkins
class ClusterResourceStatesTest {

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
    private ClusterResourceStates states;
    private LockableResourcesManager lrm;

    @BeforeEach
    void setUp(JenkinsRule j) {
        this.j = j;
        store = ClusterFixture.store();
        other = store.as("other");
        states = ClusterResourceStates.instance();
        assertSame(states, ResourceStateStore.get());
        lrm = LockableResourcesManager.get();
        lrm.addResource(new LockableResource("res"), true);
        lrm.addResource(new LockableResource("res2"), true);
    }

    private static byte[] heldBy(String build, String origin) {
        return ClusterResourceStates.toBytes(ResourceState.FREE.withBuild(build, null), origin);
    }

    private ResourceState inStore(String resource) throws Exception {
        PersistenceStore.Entry e = store.read(ClusterResourceStates.key(resource));
        return e == null ? null : ClusterResourceStates.parse(e.content());
    }

    /** Waits until this controller has read the changes other controllers made so far. */
    private void applied() throws Exception {
        ClusterFixture.awaitApplied();
        states.flush();
    }

    @Test
    void aResourceHeldByABuildOfAnotherControllerIsWaitedFor() throws Exception {
        other.write(ClusterResourceStates.key("res"), heldBy("elsewhere#3", "other"), PersistenceStore.ABSENT);
        applied();
        LockableResource res = lrm.fromName("res");
        assertTrue(res.isLocked());
        assertNull(res.getBuild(), "not a build this controller knows");

        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("lock('res') { semaphore 'inside' }", true));
        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        j.waitForMessage("is locked by elsewhere#3", b);

        other.delete(ClusterResourceStates.key("res"), PersistenceStore.ANY);
        applied();
        SemaphoreStep.waitForStart("inside/1", b);
        assertEquals(
                b.getExternalizableId(),
                inStore("res").getBuild(),
                "held by the build, by its id: whichever controller runs it");
        SemaphoreStep.success("inside/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(b));
        assertNull(inStore("res"), "released");
    }

    @Test
    void takingSeveralResourcesTakesNoneIfOneIsHeldElsewhere() throws Exception {
        other.write(ClusterResourceStates.key("res2"), heldBy("elsewhere#3", "other"), PersistenceStore.ABSENT);
        applied();
        LockableResource res = lrm.fromName("res");
        LockableResource res2 = lrm.fromName("res2");
        synchronized (LockableResourcesManager.syncResources) {
            assertFalse(states.update(List.of(res2, res), s -> s.isFree() ? s.withBuild("here#1", null) : null));
        }
        assertNull(inStore("res"), "res, taken first, was given back");
        assertTrue(res.isFree());
        assertEquals("elsewhere#3", states.get(res2).getBuild());
    }

    @Test
    void aChangeWorkedOutOnAStaleViewIsWorkedOutAgain() throws Exception {
        LockableResource res = lrm.fromName("res");
        assertTrue(lrm.reserve(List.of(res), "alice", null));
        // stolen on another controller, whose change this one may not have heard of yet
        long version = store.read(ClusterResourceStates.key("res")).version();
        other.write(
                ClusterResourceStates.key("res"),
                ClusterResourceStates.toBytes(ResourceState.FREE.withReservation("bob", "mine", true), "other"),
                version);
        lrm.steal(List.of(res), "carol", "now mine");
        applied();
        assertEquals("carol", inStore("res").getReservedBy(), "worked out again from bob's: carol's now");
        assertEquals("carol", res.getReservedBy());
        lrm.unreserve(List.of(res));
        applied();
        assertNull(inStore("res"));
        assertTrue(res.isFree());
    }

    @Test
    void resourcesOfBuildsGoneWithTheirControllerAreFreed() throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        String item = Keys.itemByUuid(p.getItemUuid());
        other.write(ClusterResourceStates.key("res"), heldBy("p#7", "dead"), PersistenceStore.ABSENT);
        other.write(Keys.placeholder(item, 7), "placeholder".getBytes(StandardCharsets.UTF_8), PersistenceStore.ABSENT);
        applied();
        assertEquals(0, LockOrphans.sweep(store), "its controller is gone, but the build may be adopted");
        assertNotNull(inStore("res"));

        other.delete(Keys.placeholder(item, 7), PersistenceStore.ANY);
        other.write(ClusterResourceStates.key("res2"), heldBy("p#8", "other"), PersistenceStore.ABSENT);
        assertEquals(1, LockOrphans.sweep(store), "res; the controller that took res2 is alive");
        store.gone("other", true);
        assertEquals(1, LockOrphans.sweep(store));
        applied();
        assertNull(inStore("res"));
        assertTrue(lrm.fromName("res").isFree());
    }

    @Test
    void reservationsAreNotFreed() throws Exception {
        other.write(
                ClusterResourceStates.key("res"),
                ClusterResourceStates.toBytes(ResourceState.FREE.withReservation("alice", null, false), "dead"),
                PersistenceStore.ABSENT);
        assertEquals(0, LockOrphans.sweep(store));
    }
}
