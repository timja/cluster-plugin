package io.jenkins.plugins.cluster.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Cause;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Queue;
import hudson.model.queue.CauseOfBlockage;
import hudson.model.queue.QueueTaskDispatcher;
import hudson.model.queue.QueueTaskFuture;
import io.jenkins.plugins.cluster.ClusterFixture;
import io.jenkins.plugins.cluster.spi.ClusterQueue;
import io.jenkins.plugins.cluster.spi.InMemoryClusterStore;
import io.jenkins.plugins.cluster.spi.Keys;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * An item this controller claimed (so holds its job's exclusive lease for) and which another
 * controller then took (the claim lapsed) must not leave a hold on the exclusive lease here: later items of the job
 * would be claimed here without asking the store, beside the build the other controller runs.
 */
@WithJenkins
class TakenElsewhereExclusiveHoldTest {

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

    /** Holds the first item of a job back once this controller has claimed it, so that it never starts here. */
    @TestExtension
    public static final class HoldClaimed extends QueueTaskDispatcher {
        static volatile String job;
        static volatile long heldId = -1;

        @Override
        public CauseOfBlockage canTake(hudson.model.Node node, Queue.BuildableItem item) {
            return canRun(item); // asked as the item is about to start, also after it was claimed
        }

        @Override
        public CauseOfBlockage canRun(Queue.Item item) {
            String held = job;
            if (held == null || !held.equals(item.task.getName())) {
                return null;
            }
            synchronized (HoldClaimed.class) {
                if (heldId < 0) {
                    heldId = item.getId();
                }
            }
            if (item.getId() != heldId) {
                return null;
            }
            try {
                ClusterQueue.Record rec = store().queue().get(item.getId());
                if (rec != null && rec.state() == ClusterQueue.State.CLAIMED) {
                    return new SharedQueue.Blocked("held by the test once claimed");
                }
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            return null;
        }
    }

    @Test
    void anItemClaimedHereAndTakenElsewhereLeavesNoHoldOnTheJobsExclusiveLease() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("exclusive");
        String lease = Keys.running(Keys.item(p)) + "/exclusive";
        String me = store().getOrigin();
        InMemoryClusterStore other = store().as("other");

        // 1. an item of the job is claimed here (the exclusive lease taken for it) but does not start. The dispatcher
        // holding it can only do so from the queue's next look at it: should the item start in the very look in which
        // its claim completed, it is tried again
        long id = -1;
        QueueTaskFuture<FreeStyleBuild> first = null;
        for (int attempt = 0; attempt < 5 && id < 0; attempt++) {
            HoldClaimed.heldId = -1;
            HoldClaimed.job = p.getName();
            QueueTaskFuture<FreeStyleBuild> f = p.scheduleBuild2(0, new Cause.UserIdCause());
            Queue.Item item = r.jenkins.getQueue().getItem(p);
            assertNotNull(item);
            long candidate = item.getId();
            ClusterFixture.await("held here once claimed, or started", () -> {
                ClusterQueue.Record rec = store().queue().get(candidate);
                Queue.Item now = r.jenkins.getQueue().getItem(candidate);
                return rec != null && rec.state() == ClusterQueue.State.STARTED
                        || now != null
                                && !(now instanceof Queue.LeftItem)
                                && now.getCauseOfBlockage() != null
                                && now.getCauseOfBlockage()
                                        .getShortDescription()
                                        .contains("held by the test");
            });
            if (store().queue().get(candidate).state() == ClusterQueue.State.CLAIMED) {
                id = candidate;
                first = f;
            } else {
                HoldClaimed.job = null;
                r.assertBuildStatusSuccess(f.get(60, TimeUnit.SECONDS));
            }
        }
        assertTrue(id > 0, "held here once claimed");
        long held = id;
        ClusterQueue.Record claimed = store().queue().get(held);
        assertTrue(me.equals(claimed.claimedBy()), "claimed here: " + claimed);
        assertEquals(0, other.leases().acquire(lease), "the exclusive lease is held here for the claim");

        // 2. this controller looks gone: the item is put back and taken by another controller, which also takes the
        // job's exclusive lease; this controller comes back
        store().gone(me, true);
        store().reclaimAndPrune();
        assertTrue(other.queue().claim(held, null));
        long otherEpoch = other.leases().acquire(lease);
        assertTrue(otherEpoch > 0, "the lease lapsed with this controller");
        store().gone(me, false);
        ClusterFixture.await(
                "handed off here",
                () -> r.jenkins.getQueue().getItem(held) instanceof Queue.LeftItem li && li.isTakenElsewhere());
        assertFalse(first.isDone());

        // 3. a new item of the job here must wait for the build the other controller runs (its lease): it must not be
        // claimed here on the strength of the hold left by the item taken elsewhere
        HoldClaimed.job = null;
        QueueTaskFuture<FreeStyleBuild> second = p.scheduleBuild2(0, new Cause.UserIdCause());
        Queue.Item item2 = r.jenkins.getQueue().getItem(p);
        assertNotNull(item2);
        long id2 = item2.getId();
        ClusterFixture.await("published", () -> store().queue().get(id2) != null);
        ClusterFixture.await("the claim here to be refused", () -> {
            r.jenkins.getQueue().scheduleMaintenance();
            Queue.Item waiting = r.jenkins.getQueue().getItem(id2);
            return waiting != null
                    && waiting.getCauseOfBlockage() != null
                    && waiting.getCauseOfBlockage().getShortDescription().contains("already in progress");
        });
        ClusterQueue.Record rec2 = store().queue().get(id2);
        assertNotNull(rec2);
        assertTrue(
                other.leases().holds(lease, otherEpoch), "the other controller still holds the job's exclusive lease");
        assertEquals(
                ClusterQueue.State.WAITING,
                rec2.state(),
                "claimed here (" + rec2.claimedBy() + ") although another controller holds the job's exclusive lease: "
                        + (second.getStartCondition().isDone() ? "and its build started here" : "not started yet"));
        Queue.Item here = r.jenkins.getQueue().getItem(id2);
        assertTrue(here != null && !(here instanceof Queue.LeftItem), "still waiting here");
        // once the other controller lets go, it starts here
        other.leases().release(lease, otherEpoch);
        r.jenkins.getQueue().scheduleMaintenance();
        r.assertBuildStatusSuccess(second.get(60, TimeUnit.SECONDS));
    }
}
