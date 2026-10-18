package io.jenkins.plugins.cluster.locks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Result;
import io.jenkins.plugins.cluster.ClusterFixture;
import io.jenkins.plugins.cluster.spi.InMemoryClusterStore;
import io.jenkins.plugins.cluster.spi.Keys;
import java.nio.charset.StandardCharsets;
import java.util.List;
import jenkins.persistence.PersistenceStore;
import org.jenkins.plugins.lockableresources.LockCoordinator;
import org.jenkins.plugins.lockableresources.LockableResource;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;
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

/** {@code other} is another controller of the cluster, sharing the store with this one. */
@WithJenkins
class ClusterLocksTest {

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
    private ClusterLocks locks;
    private LockableResourcesManager lrm;

    @BeforeEach
    void setUp(JenkinsRule j) {
        this.j = j;
        store = ClusterFixture.store();
        other = store.as("other");
        locks = ClusterLocks.get();
        assertSame(locks, LockCoordinator.get());
        lrm = LockableResourcesManager.get();
        lrm.addResource(new LockableResource("res"), true);
    }

    private static byte[] lockOf(String build, String origin) {
        return new ClusterLocks.Holder(
                        ClusterLocks.Holder.LOCK, build, build, null, null, false, origin, "controller-" + origin)
                .toBytes();
    }

    private static byte[] reservationOf(String user, String origin) {
        return ClusterLocks.Holder.reservation(user, "testing", true, origin).toBytes();
    }

    /** Waits until this controller has applied the changes other controllers made so far. */
    private void applied() throws Exception {
        ClusterFixture.awaitApplied();
        locks.flush();
    }

    /** Tells this controller about a change late, as a store may deliver an event after a newer change. */
    private void tellLate(String resource, boolean deleted) throws Exception {
        locks.changed(ClusterLocks.key(resource), deleted);
        locks.flush();
    }

    private ClusterLocks.Holder holder(String resource) throws Exception {
        return ClusterLocks.Holder.parse(store.read(ClusterLocks.key(resource)));
    }

    private WorkflowRun lockInside(String name) throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class, name);
        p.setDefinition(new CpsFlowDefinition("lock('res') { semaphore 'inside' }", true));
        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("inside/1", b);
        return b;
    }

    @Test
    void lockHeldElsewhereIsWaitedFor() throws Exception {
        other.write(ClusterLocks.key("res"), lockOf("uuid-x#3", "other"), PersistenceStore.ABSENT);
        applied();
        LockableResource res = lrm.fromName("res");
        assertEquals("uuid-x#3 on controller-other", res.getHeldElsewhereBy());

        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("lock('res') { echo 'inside' }", true));
        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        j.waitForMessage("is not free, waiting for execution", b);

        other.delete(ClusterLocks.key("res"), PersistenceStore.ANY);
        applied();
        j.assertBuildStatusSuccess(j.waitForCompletion(b));
        assertNull(store.read(ClusterLocks.key("res")), "released");
    }

    @Test
    void resourcesAreTakenInTheOrderOfTheirNames() {
        List<LockableResource> asked =
                List.of(new LockableResource("b"), new LockableResource("c"), new LockableResource("a"));
        assertEquals(
                List.of("a", "b", "c"),
                ClusterLocks.inOrder(asked).stream()
                        .map(LockableResource::getName)
                        .toList());
    }

    @Test
    void aControllerLockingResourcesAnotherHoldsTheFirstOfGivesBackNothingTaken() throws Exception {
        lrm.addResource(new LockableResource("res2"), true);
        // another controller holds the first of the two by name: none is taken here, so nothing is given back either
        other.write(ClusterLocks.key("res"), lockOf("uuid-x#3", "other"), PersistenceStore.ABSENT);
        applied();
        List<String> changes = new java.util.concurrent.CopyOnWriteArrayList<>();
        try (AutoCloseable sub = store.subscribe(e -> changes.add(e.key()))) {
            assertFalse(locks.tryReserve(List.of(lrm.fromName("res2"), lrm.fromName("res")), "alice", "testing"));
        }
        assertNull(store.read(ClusterLocks.key("res2")), "never taken");
        assertEquals(List.of(), changes, "nothing written and given back: no event for the others to react to");
    }

    @Test
    void lockIsExclusive() throws Exception {
        WorkflowRun b = lockInside("p");
        ClusterLocks.Holder h = holder("res");
        assertNotNull(h);
        assertTrue(h.isLockOf(b));
        assertEquals(store.getOrigin(), h.origin());
        assertThrowsConflict(
                () -> other.write(ClusterLocks.key("res"), lockOf("uuid-y#1", "other"), PersistenceStore.ABSENT));
        SemaphoreStep.success("inside/1", null);
        j.waitForCompletion(b);
        assertNull(store.read(ClusterLocks.key("res")));
    }

    private static void assertThrowsConflict(org.junit.jupiter.api.function.Executable e) {
        org.junit.jupiter.api.Assertions.assertThrows(jenkins.persistence.ConflictException.class, e);
    }

    @Test
    void lateDeletionIsIgnored() throws Exception {
        // another controller released it, and this one took it since: the deletion arrives only now
        WorkflowRun b = lockInside("p");
        tellLate("res", true);
        assertSame(b, lrm.fromName("res").getBuild(), "still held here");
        assertTrue(holder("res").isLockOf(b), "and still in the store");
        SemaphoreStep.success("inside/1", null);
        j.waitForCompletion(b);
    }

    @Test
    void freedElsewhereUnlocksHere() throws Exception {
        WorkflowRun b = lockInside("p");
        other.delete(ClusterLocks.key("res"), PersistenceStore.ANY); // unlocked on the resources page there
        applied();
        assertNull(lrm.fromName("res").getBuild());
        SemaphoreStep.success("inside/1", null);
        j.waitForCompletion(b);
    }

    @Test
    void stolenElsewhere() throws Exception {
        WorkflowRun b = lockInside("p");
        other.write(ClusterLocks.key("res"), reservationOf("alice", "other"), PersistenceStore.ANY);
        applied();
        LockableResource res = lrm.fromName("res");
        assertNull(res.getBuild());
        assertEquals("alice", res.getReservedBy());
        assertTrue(res.isStolen());
        SemaphoreStep.success("inside/1", null);
        j.waitForCompletion(b);
        assertEquals("alice", holder("res").user(), "the build's release keeps the reservation");
    }

    @Test
    void releaseKeepsAStealNotYetSeen() throws Exception {
        WorkflowRun b = lockInside("p");
        other.write(ClusterLocks.key("res"), reservationOf("alice", "other"), PersistenceStore.ANY);
        SemaphoreStep.success("inside/1", null); // released before the steal's event arrives
        j.waitForCompletion(b);
        assertEquals("alice", holder("res").user());
    }

    @Test
    void reservations() throws Exception {
        LockableResource res = lrm.fromName("res");
        assertTrue(lrm.reserve(List.of(res), "bob", null));
        assertEquals("bob", holder("res").user());
        lrm.unreserve(List.of(res));
        assertNull(store.read(ClusterLocks.key("res")));

        other.write(ClusterLocks.key("res"), reservationOf("alice", "other"), PersistenceStore.ABSENT);
        assertFalse(lrm.reserve(List.of(res), "bob", null), "reserved elsewhere");
        assertEquals("alice", res.getReservedBy());
        other.delete(ClusterLocks.key("res"), PersistenceStore.ANY);
        applied();
        assertNull(res.getReservedBy());
    }

    @Test
    void resetFreesOnlyWhatWasShown() throws Exception {
        other.write(ClusterLocks.key("res"), lockOf("uuid-x#3", "other"), PersistenceStore.ABSENT);
        applied();
        // meanwhile x#3 released it and y#1 took it, not applied here yet (applying waits for the resources' monitor)
        synchronized (LockableResourcesManager.syncResources) {
            other.delete(ClusterLocks.key("res"), PersistenceStore.ANY);
            other.write(ClusterLocks.key("res"), lockOf("uuid-y#1", "other"), PersistenceStore.ABSENT);
            lrm.reset(List.of(lrm.fromName("res")));
        }
        applied();
        locks.flush();
        assertEquals("uuid-y#1", holder("res").build());
        assertEquals("uuid-y#1 on controller-other", lrm.fromName("res").getHeldElsewhereBy());
    }

    @Test
    void orphanedLocksAreReleasedByBuildIdentity() throws Exception {
        // a branch project's build: its directory name is not its item name, which no longer matters
        String item = Keys.itemByUuid("uuid-org-repo-feature%2Fx");
        other.write(ClusterLocks.key("res"), lockOf("uuid-org-repo-feature%2Fx#7", "dead"), PersistenceStore.ABSENT);
        other.write(Keys.placeholder(item, 7), "placeholder".getBytes(StandardCharsets.UTF_8), PersistenceStore.ABSENT);
        applied();
        assertEquals(0, LockOrphans.sweep(store), "its controller is gone, but the build may be adopted");
        assertNotNull(store.read(ClusterLocks.key("res")));

        other.delete(Keys.placeholder(item, 7), PersistenceStore.ANY);
        other.write(ClusterLocks.key("res2"), lockOf("uuid-z#1", "other"), PersistenceStore.ABSENT);
        assertEquals(1, LockOrphans.sweep(store), "res; the holder of res2 is alive");
        store.gone("other", true);
        assertEquals(1, LockOrphans.sweep(store));
        locks.flush();
        assertNull(store.read(ClusterLocks.key("res")));
        assertNull(lrm.fromName("res").getHeldElsewhereBy());
    }

    @Test
    void reservationsAreNotOrphaned() throws Exception {
        other.write(ClusterLocks.key("res"), reservationOf("alice", "dead"), PersistenceStore.ABSENT);
        assertEquals(0, LockOrphans.sweep(store));
    }

    /**
     * Resumes a build here as an adopted one, its lock as another controller left it. Applying that change here
     * waits for the resources' monitor, so the adoption finds the lock as it was left.
     */
    private void adopted(WorkflowRun b, byte[] lock) throws Exception {
        synchronized (LockableResourcesManager.syncResources) {
            other.write(ClusterLocks.key("res"), lock, PersistenceStore.ANY);
            new LockAdoption().onResumed(b.getExecution());
        }
    }

    @Test
    void adoptedBuildTakesItsLockOver() throws Exception {
        WorkflowRun b = lockInside("p");
        // as if b had run on a controller now gone, and was adopted here
        adopted(b, lockOf(ClusterLocks.buildId(b), "gone"));
        assertEquals(store.getOrigin(), holder("res").origin());
        SemaphoreStep.success("inside/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(b));
    }

    @Test
    void adoptedBuildThatLostItsLockStops() throws Exception {
        WorkflowRun b = lockInside("p");
        adopted(b, lockOf("uuid-y#1", "other"));
        j.assertBuildStatus(Result.FAILURE, j.waitForCompletion(b));
        j.assertLogContains("Lost the lock on res to uuid-y#1 on controller-other", b);
        assertEquals("uuid-y#1", holder("res").build(), "the other holder keeps it");
    }

    @Test
    void definitionsAreShared() throws Exception {
        LockableResource made = new LockableResource("made/here");
        made.setDescription("from the page");
        lrm.addResource(made, true);
        locks.definitionChanged(made);
        assertNotNull(store.read(Keys.lockMeta(ClusterLocks.encode("made/here"))));

        LockableResource def = new LockableResource("there");
        def.setNote("a note");
        other.write(
                Keys.lockMeta("there"),
                jenkins.model.Jenkins.XSTREAM2.toXML(def).getBytes(StandardCharsets.UTF_8),
                PersistenceStore.ANY);
        applied();
        assertEquals("a note", lrm.fromName("there").getNote());

        other.delete(Keys.lockMeta("there"), PersistenceStore.ANY);
        applied();
        assertNull(lrm.fromName("there"));
    }

    @Test
    void handlesOnlyItsKeys() {
        assertTrue(locks.handles(ClusterLocks.key("a b/c")));
        assertTrue(locks.handles(Keys.lockMeta("x")));
        assertFalse(locks.handles("_items/u/running/1.xml"));
        assertFalse(locks.handles("_locksmith/x"));
    }

    @Test
    void aStepWaitingForALockTakenElsewhereNamesItsHolder() throws Exception {
        // taken on another controller, and not known here yet (its change has not arrived): res looks free
        store.write(ClusterLocks.key("res"), lockOf("uuid-x#3", "other"), PersistenceStore.ABSENT);
        assertNull(lrm.fromName("res").getHeldElsewhereBy());
        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("lock('res') { echo 'inside' }", true));
        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        j.waitForMessage("[Resource: res] is not free, waiting for execution ...", b);
        j.assertLogContains("The resource [res] is locked by uuid-x#3 on controller-other.", b);

        other.delete(ClusterLocks.key("res"), PersistenceStore.ANY);
        applied();
        j.assertBuildStatusSuccess(j.waitForCompletion(b));
    }

    // ------------------------------------------------------------------ remote lock API

    private static org.jenkins.plugins.lockableresources.remote.RemoteLockRecord remoteLock(boolean skipIfLocked) {
        return org.jenkins.plugins.lockableresources.remote.RemoteLockManager.get()
                .enqueue(
                        new org.jenkins.plugins.lockableresources.remote.RemoteLockRequest(
                                "res", null, 0, null, false, "SEQUENTIAL", skipIfLocked, null, 0, 0, "MINUTES", null),
                        "client-1");
    }

    private void exposeRes() {
        lrm.setRemoteApiEnabled(true);
        lrm.setExposeLabel("remote-ok");
        lrm.fromName("res").setLabelsFromString("remote-ok");
    }

    @Test
    void remoteLockIsExclusive() throws Exception {
        exposeRes();
        var record = remoteLock(false);
        assertEquals(org.jenkins.plugins.lockableresources.remote.RemoteLockState.ACQUIRED, record.getState());
        assertTrue(
                record.getLockId().startsWith(io.jenkins.plugins.cluster.spi.ClusterMember.NAME + "~"),
                "names the controller holding it: " + record.getLockId());
        ClusterLocks.Holder h = holder("res");
        assertNotNull(h);
        assertTrue(h.isRemoteLockOf(record.getLockId()));
        assertThrowsConflict(
                () -> other.write(ClusterLocks.key("res"), lockOf("uuid-y#1", "other"), PersistenceStore.ABSENT));
        tellLate("res", false); // its own lock: kept
        assertEquals(record.getLockId(), lrm.fromName("res").getRemoteLockedBy());

        org.jenkins.plugins.lockableresources.remote.RemoteLockManager.get().release(record.getLockId());
        assertNull(store.read(ClusterLocks.key("res")), "released");
    }

    @Test
    void remoteLockWaitsForALockTakenElsewhere() throws Exception {
        exposeRes();
        // not known here yet: res looks free, the store refuses
        store.write(ClusterLocks.key("res"), lockOf("uuid-x#3", "other"), PersistenceStore.ABSENT);
        var record = remoteLock(false);
        assertEquals(org.jenkins.plugins.lockableresources.remote.RemoteLockState.QUEUED, record.getState());
        assertEquals("uuid-x#3 on controller-other", lrm.fromName("res").getHeldElsewhereBy());
        assertEquals(
                org.jenkins.plugins.lockableresources.remote.RemoteLockState.SKIPPED,
                remoteLock(true).getState());

        other.delete(ClusterLocks.key("res"), PersistenceStore.ANY);
        applied();
        ClusterFixture.await(
                "the queued remote lock to be granted",
                () -> record.getState() == org.jenkins.plugins.lockableresources.remote.RemoteLockState.ACQUIRED);
        assertTrue(holder("res").isRemoteLockOf(record.getLockId()));
    }

    @Test
    void remoteLockFreedOrTakenElsewhereEndsHere() throws Exception {
        exposeRes();
        var record = remoteLock(false);
        other.delete(ClusterLocks.key("res"), PersistenceStore.ANY); // reset on the resources page there
        applied();
        assertEquals(org.jenkins.plugins.lockableresources.remote.RemoteLockState.FAILED, record.getState());
        assertNull(lrm.fromName("res").getRemoteLockedBy());

        var second = remoteLock(false);
        assertEquals(org.jenkins.plugins.lockableresources.remote.RemoteLockState.ACQUIRED, second.getState());
        other.write(ClusterLocks.key("res"), reservationOf("alice", "other"), PersistenceStore.ANY); // stolen there
        applied();
        assertEquals(org.jenkins.plugins.lockableresources.remote.RemoteLockState.FAILED, second.getState());
        assertNull(lrm.fromName("res").getRemoteLockedBy());
        assertEquals("alice", lrm.fromName("res").getReservedBy());
        assertNotNull(holder("res"), "the reservation made there stays");
        assertFalse(holder("res").isRemote());
    }

    @Test
    void remoteLocksOfAControllerGoneAreReleased() throws Exception {
        other.write(
                ClusterLocks.key("res"),
                ClusterLocks.Holder.remote("dead~1234", "dead").toBytes(),
                PersistenceStore.ABSENT);
        other.write(
                ClusterLocks.key("res2"),
                ClusterLocks.Holder.remote("other~5678", "other").toBytes(),
                PersistenceStore.ABSENT);
        applied();
        assertEquals(
                "remote lock dead~1234 on " + io.jenkins.plugins.cluster.spi.ClusterMember.NAME,
                lrm.fromName("res").getHeldElsewhereBy());
        assertEquals(1, LockOrphans.sweep(store), "res; the holder of res2 is alive");
        locks.flush();
        assertNull(store.read(ClusterLocks.key("res")));
        assertNull(lrm.fromName("res").getHeldElsewhereBy());
        assertNotNull(store.read(ClusterLocks.key("res2")));
    }

    @Test
    void aCopyOfABuildTakenOverElsewhereLeavesTheNewOwnersLock() throws Exception {
        WorkflowRun b = lockInside("p");
        // taken over by another controller, which rewrote the lock as its own (LockAdoption there)
        other.write(ClusterLocks.key("res"), lockOf(ClusterLocks.buildId(b), "other"), PersistenceStore.ANY);
        applied();
        // the copy here ends (stopped, or its body ends): it releases the resource here only
        SemaphoreStep.success("inside/1", null);
        j.waitForCompletion(b);
        assertNull(lrm.fromName("res").getBuild());
        ClusterLocks.Holder h = holder("res");
        assertNotNull(h, "still held by the new owner");
        assertEquals("other", h.origin());
        locks.flush();
        assertEquals(
                ClusterLocks.buildId(b) + " on controller-other",
                lrm.fromName("res").getHeldElsewhereBy());
    }
}
