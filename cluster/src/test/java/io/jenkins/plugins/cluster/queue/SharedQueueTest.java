package io.jenkins.plugins.cluster.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Queue;
import hudson.model.queue.QueueTaskFuture;
import io.jenkins.plugins.cluster.ClusterFixture;
import io.jenkins.plugins.cluster.builds.BuildElsewhere;
import io.jenkins.plugins.cluster.builds.RunningBuilds;
import io.jenkins.plugins.cluster.spi.ClusterQueue;
import io.jenkins.plugins.cluster.spi.InMemoryClusterStore;
import io.jenkins.plugins.cluster.spi.Keys;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestBuilder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Items are shared through the cluster queue by the job's identity, and an item another controller takes is handed off
 * here: its future follows the build there.
 */
@WithJenkins
class SharedQueueTest {

    @BeforeAll
    static void cluster() {
        ClusterFixture.activate();
    }

    @AfterAll
    static void noCluster() {
        ClusterFixture.deactivate();
    }

    private JenkinsRule r;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
    }

    private static InMemoryClusterStore store() {
        return ClusterFixture.store();
    }

    @Test
    void aJobWithoutAPinnedIdentityIsNotSharedUnderItsNameDerivedOne() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("unpinned");
        java.lang.reflect.Field uuid = hudson.model.AbstractItem.class.getDeclaredField("itemUuid");
        uuid.setAccessible(true);
        uuid.set(p, null); // as an item loaded from a configuration without one
        assertFalse(SharedQueue.shared(p), "its queue items stay local");
    }

    @Test
    void anItemScheduledHereIsPublishedByItsJobsIdentity() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("published");
        p.scheduleBuild2(600, new Cause.UserIdCause());
        Queue.Item item = r.jenkins.getQueue().getItem(p);
        assertNotNull(item);
        ClusterFixture.await(
                "published", () -> store().queue().pending().stream().anyMatch(rec -> rec.id() == item.getId()));
        ClusterQueue.Record rec = store().queue().get(item.getId());
        assertEquals(p.getItemUuid(), rec.itemUuid());
        assertEquals("published", rec.task());
        assertTrue(rec.dedupKey().startsWith(p.getItemUuid()), "folded by identity, not by name");
    }

    @Test
    void anItemIsRecordedStartedOnlyOnceItsBuildIsRecordedRunning() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("ordered");
        String placeholder = Keys.placeholder(Keys.item(p), 1);
        List<Boolean> placeholderThere = new CopyOnWriteArrayList<>();
        try (AutoCloseable ignored = store().queue().subscribe(rec -> {
            if (rec.state() == ClusterQueue.State.STARTED && p.getItemUuid().equals(rec.itemUuid())) {
                placeholderThere.add(store().read(placeholder) != null);
            }
        })) {
            r.buildAndAssertSuccess(p);
        }
        assertEquals(List.of(true), placeholderThere);
    }

    @Test
    void anItemTakenElsewhereFollowsItsBuildThere() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("taken");
        QueueTaskFuture<FreeStyleBuild> future = p.scheduleBuild2(600, new Cause.UserIdCause());
        long id = r.jenkins.getQueue().getItem(p).getId();
        ClusterFixture.await(
                "published", () -> store().queue().pending().stream().anyMatch(rec -> rec.id() == id));
        InMemoryClusterStore other = store().as("other");
        assertTrue(other.queue().claim(id, null));
        ClusterFixture.await("handed off", () -> r.jenkins.getQueue().getItem(id) instanceof Queue.LeftItem);
        Queue.LeftItem left = (Queue.LeftItem) r.jenkins.getQueue().getItem(id);
        assertTrue(left.isTakenElsewhere());
        assertFalse(left.isCancelled());
        assertFalse(future.isDone(), "pending: the build runs elsewhere");
        assertTrue(other.queue().started(id, 1));
        Future<FreeStyleBuild> start = future.getStartCondition();
        Object started = ((Future<?>) start).get(10, TimeUnit.SECONDS);
        assertInstanceOf(BuildElsewhere.class, started, "a view of the build elsewhere");
        assertEquals(1, ((BuildElsewhere) started).getNumber());
        // the build completes there and its record arrives here
        FreeStyleBuild here = r.buildAndAssertSuccess(p); // #1 as this controller would have made it
        assertEquals(1, here.getNumber());
        SharedQueue.HandOffs.recorded(p, 1);
        assertSame(here, future.get(10, TimeUnit.SECONDS));
    }

    @Test
    void anItemCancelledElsewhereIsCancelledHere() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("cancelled");
        QueueTaskFuture<FreeStyleBuild> future = p.scheduleBuild2(600, new Cause.UserIdCause());
        long id = r.jenkins.getQueue().getItem(p).getId();
        ClusterFixture.await(
                "published", () -> store().queue().pending().stream().anyMatch(rec -> rec.id() == id));
        assertTrue(store().as("other").queue().cancel(id));
        ClusterFixture.await(
                "cancelled here",
                () -> r.jenkins.getQueue().getItem(id) instanceof Queue.LeftItem li && li.isCancelled());
        assertTrue(future.isCancelled());
    }

    @Test
    void anItemPublishedElsewhereIsMirroredAndFoldsRequestsMadeHere() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("mirrored");
        InMemoryClusterStore other = store().as("other");
        long id = other.queue().nextId();
        List<hudson.model.Action> actions = List.of(new CauseAction(new Cause.RemoteCause("elsewhere", "note")));
        other.queue()
                .publish(new ClusterQueue.Record(
                        id,
                        p.getItemUuid(),
                        p.getFullName(),
                        SharedQueue.dedupKey(p, actions),
                        SharedQueue.toXml(actions),
                        System.currentTimeMillis() + 600_000,
                        ClusterQueue.State.WAITING,
                        "other",
                        null,
                        null,
                        0));
        ClusterFixture.await("mirrored", () -> r.jenkins.getQueue().getItem(id) instanceof Queue.WaitingItem);
        p.scheduleBuild2(600, new Cause.UserIdCause());
        ClusterFixture.await("folded", () -> store().queue().get(id).actions().contains("UserIdCause"));
        assertEquals(1, r.jenkins.getQueue().getItems().length, "one item for both requests");
    }

    @Test
    void aJobBuildingOnAnotherControllerOneAtATimeWaitsForIt() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("exclusive");
        String lease =
                io.jenkins.plugins.cluster.spi.Keys.running(io.jenkins.plugins.cluster.spi.Keys.item(p)) + "/exclusive";
        InMemoryClusterStore other = store().as("other");
        long epoch = other.leases().acquire(lease);
        QueueTaskFuture<FreeStyleBuild> future = p.scheduleBuild2(0, new Cause.UserIdCause());
        ClusterFixture.await(
                "held back",
                () -> r.jenkins.getQueue().getItem(p) instanceof Queue.Item item
                        && item.getCauseOfBlockage() != null
                        && item.getCauseOfBlockage()
                                .getShortDescription()
                                .contains("already in progress on another controller"));
        other.leases().release(lease, epoch);
        r.jenkins.getQueue().scheduleMaintenance();
        r.assertBuildStatusSuccess(future);
        ClusterFixture.await(
                "released again once the build is done", () -> other.leases().acquire(lease) > 0);
    }

    @Test
    void aFutureFollowingABuildTakenOverElsewhereIsNotCompletedByItsStoppedCopy() throws Exception {
        Semaphore release = new Semaphore(0);
        FreeStyleProject p = r.createFreeStyleProject("moved");
        p.getBuildersList().add(new TestBuilder() {
            @Override
            public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
                    throws InterruptedException {
                release.acquire();
                return true;
            }
        });
        FreeStyleBuild b = p.scheduleBuild2(0).waitForStart();
        ClusterFixture.await("owned", () -> RunningBuilds.ownedPlaceholder(b) != null);
        // a future following this item's build (handed off, then put back and started here after all)
        AtomicReference<Queue.Executable> completed = new AtomicReference<>();
        SharedQueue.HandOffs.add(b.getQueueId(), new Queue.HandOff() {
            @Override
            public void started(Queue.Executable view) {}

            @Override
            public void completed(Queue.Executable run) {
                completed.set(run);
            }

            @Override
            public void cancelled() {}
        });
        SharedQueue.HandOffs.startedHere(b.getQueueId(), b);
        RunningBuilds.lost(b); // another controller took the build over: the copy here stops
        r.waitForCompletion(b);
        // every listener has seen it finalized once its executor is free
        ClusterFixture.await("its executor to be free", () -> b.getExecutor() == null);
        assertEquals(null, completed.get(), "the future follows the build elsewhere, not the copy stopped here");
    }

    /** A hand-off recording what it is told. */
    private static final class Told implements Queue.HandOff {
        final AtomicReference<Queue.Executable> started = new AtomicReference<>();

        @Override
        public void started(Queue.Executable view) {
            started.set(view);
        }

        @Override
        public void completed(Queue.Executable run) {}

        @Override
        public void cancelled() {}
    }

    @Test
    void everyHandOffOfAnItemIsToldOfItsBuildElsewhere() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("handed-off-twice");
        long id = store().queue().nextId();
        // handed off when first claimed elsewhere; re-created here when that claimant died, and handed off again
        Told first = new Told();
        Told again = new Told();
        SharedQueue.HandOffs.add(id, first);
        SharedQueue.HandOffs.add(id, again);
        SharedQueue.HandOffs.startedElsewhere(new ClusterQueue.Record(
                id,
                p.getItemUuid(),
                p.getFullName(),
                p.getItemUuid(),
                "",
                0,
                ClusterQueue.State.STARTED,
                "other",
                null,
                "other",
                7));
        assertNotNull(first.started.get(), "the first left item gets the build");
        assertNotNull(again.started.get(), "and so does the one it was handed off as again");
        SharedQueue.HandOffs.cancelled(id);
    }

    @Test
    void theMirrorCatchesUpWithChangesWhoseEventsWereLost() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("caught-up");
        List<hudson.model.Action> actions = List.of(new CauseAction(new Cause.UserIdCause()));
        InMemoryClusterStore other = store().as("other");
        long waiting = other.queue().nextId();
        store().loseEvents(true);
        try {
            other.queue()
                    .publish(new ClusterQueue.Record(
                            waiting,
                            p.getItemUuid(),
                            p.getFullName(),
                            SharedQueue.dedupKey(p, actions),
                            SharedQueue.toXml(actions),
                            System.currentTimeMillis() + 600_000,
                            ClusterQueue.State.WAITING,
                            "other",
                            null,
                            null,
                            0));
        } finally {
            store().loseEvents(false);
        }
        assertEquals(null, r.jenkins.getQueue().getItem(waiting), "its event was lost");
        io.jenkins.plugins.cluster.spi.Resync.runAll();
        ClusterFixture.await(
                "mirrored after all", () -> r.jenkins.getQueue().getItem(waiting) instanceof Queue.WaitingItem);
        // and an item taken elsewhere whose event is lost leaves the queue here once caught up
        store().loseEvents(true);
        try {
            assertTrue(other.queue().claim(waiting, null));
        } finally {
            store().loseEvents(false);
        }
        io.jenkins.plugins.cluster.spi.Resync.runAll();
        ClusterFixture.await(
                "handed off",
                () -> r.jenkins.getQueue().getItem(waiting) instanceof Queue.LeftItem li && li.isTakenElsewhere());
    }
}
