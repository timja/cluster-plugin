package io.jenkins.plugins.cluster.builds;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.model.User;
import io.jenkins.plugins.cluster.ClusterFixture;
import io.jenkins.plugins.cluster.PipelineAdopter;
import io.jenkins.plugins.cluster.spi.InMemoryClusterStore;
import io.jenkins.plugins.cluster.spi.Keys;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import jenkins.model.ExternalBuild;
import jenkins.model.ExternalBuilds;
import jenkins.model.experimentalflags.UserExperimentalFlagsProperty;
import jenkins.persistence.PersistenceStore;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestBuilder;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Builds running here are recorded, leased and fenced; builds running elsewhere are shown, stopped and, once their
 * controller is gone, closed.
 */
@WithJenkins
class RunningBuildsTest {

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

    /** A project whose builds wait until released. */
    private static FreeStyleProject waiting(JenkinsRule r, String name, Semaphore release) throws Exception {
        FreeStyleProject p = r.createFreeStyleProject(name);
        p.getBuildersList().add(new TestBuilder() {
            @Override
            public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
                    throws InterruptedException {
                listener.getLogger().println("waiting");
                release.acquire();
                return true;
            }
        });
        return p;
    }

    /** A placeholder as another controller writes it for a build it runs. */
    private static void runningElsewhere(FreeStyleProject p, int number, String origin) throws Exception {
        String prefix = Keys.item(p);
        InMemoryClusterStore other = store().as(origin);
        long epoch = other.leases().acquire(Keys.buildLease(prefix, number));
        RunningBuilds.Placeholder ph = new RunningBuilds.Placeholder(
                prefix,
                number,
                origin,
                "controller-" + origin,
                "#" + number,
                System.currentTimeMillis(),
                epoch,
                Map.of(),
                PersistenceStore.ABSENT);
        other.leases()
                .writeIfHeld(
                        Keys.buildLease(prefix, number), epoch, ph.getKey(), ph.toBytes(), PersistenceStore.ABSENT);
    }

    @Test
    void aBuildRunningHereIsLeasedAndShownAsRunning() throws Exception {
        Semaphore release = new Semaphore(0);
        FreeStyleProject p = waiting(r, "here", release);
        FreeStyleBuild b = p.scheduleBuild2(0).waitForStart();
        String prefix = Keys.item(p);
        ClusterFixture.await("its placeholder", () -> store().read(Keys.placeholder(prefix, 1)) != null);
        RunningBuilds.Placeholder owned = RunningBuilds.ownedPlaceholder(b);
        assertNotNull(owned);
        assertEquals(store().getOrigin(), owned.getOrigin());
        assertTrue(store().leases().holds(owned.getLeaseKey(), owned.getEpoch()));
        assertEquals(0, store().as("other").leases().acquire(owned.getLeaseKey()), "nobody else may run it");
        assertTrue(ExternalBuilds.get().running(p).isEmpty(), "it runs here: a real build, not one elsewhere");
        RunningBuilds.update(b, Map.of("k", "v"));
        assertThat(new String(store().read(owned.getKey()).content(), StandardCharsets.UTF_8), containsString("x.k=v"));
        release.release();
        r.assertBuildStatusSuccess(r.waitForCompletion(b));
        ClusterFixture.await("its placeholder removed", () -> store().read(Keys.placeholder(prefix, 1)) == null);
        assertTrue(store().as("other").leases().acquire(owned.getLeaseKey()) > 0, "its lease released");
        assertThrows(
                IllegalStateException.class,
                () -> RunningBuilds.update(b, Map.of("k", "late")),
                "not owned once completed");
    }

    @Test
    void aBuildRunningElsewhereIsShownAndCanBeStopped() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("elsewhere");
        runningElsewhere(p, 1, "other");
        ClusterFixture.await("listed", () -> !ExternalBuilds.get().running(p).isEmpty());
        ExternalBuild b = ExternalBuilds.get().get(p, 1);
        assertNotNull(b);
        assertEquals("controller-other", b.getLocation());
        assertTrue(p.isBuilding());
        assertTrue(p.getIconColor().isAnimated());
        JenkinsRule.WebClient wc = r.createWebClient();
        HtmlPage page = wc.getPage(p, "1/");
        assertThat(page.asNormalizedText(), containsString("Running on controller controller-other"));
        assertThat(
                wc.goTo(p.getUrl() + "api/json?depth=1", "application/json")
                        .getWebResponse()
                        .getContentAsString(),
                containsString("\"buildsElsewhere\""));
        b.requestStop("alice");
        assertThat(
                new String(store().read(Keys.stopRequest(Keys.item(p), 1)).content(), StandardCharsets.UTF_8),
                containsString("alice"));
    }

    @Test
    void aBuildRunningHereAskedToStopElsewhereIsStopped() throws Exception {
        Semaphore release = new Semaphore(0);
        FreeStyleProject p = waiting(r, "stopped", release);
        FreeStyleBuild b = p.scheduleBuild2(0).waitForStart();
        String prefix = Keys.item(p);
        ClusterFixture.await("owned", () -> RunningBuilds.ownedPlaceholder(b) != null);
        String stop = Keys.stopRequest(prefix, 1);
        store().as("other")
                .write(stop, "controller=b\nby=bob\n".getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
        r.assertBuildStatus(Result.ABORTED, r.waitForCompletion(b));
        assertThat(b.getLog(), containsString("bob"));
    }

    @Test
    void aBuildTakenOverByAnotherControllerIsStoppedAndNotPublished() throws Exception {
        Semaphore release = new Semaphore(0);
        FreeStyleProject p = waiting(r, "lost", release);
        FreeStyleBuild b = p.scheduleBuild2(0).waitForStart();
        ClusterFixture.await("owned", () -> RunningBuilds.ownedPlaceholder(b) != null);
        RunningBuilds.Placeholder owned = RunningBuilds.ownedPlaceholder(b);
        // this controller looked gone for a while, and another took the build's lease
        store().gone(store().getOrigin(), true);
        assertTrue(store().as("other").leases().acquire(owned.getLeaseKey()) > owned.getEpoch());
        store().gone(store().getOrigin(), false);
        RunningBuilds.checkOwnership();
        // the copy here stops, but the build carries on elsewhere: it is not aborted
        r.assertBuildStatus(Result.NOT_BUILT, r.waitForCompletion(b));
        assertNull(store().read(Keys.buildRecord(Keys.item(p), 1)), "the new owner publishes it, not this controller");
        assertNotNull(store().read(owned.getKey()), "nor is its placeholder removed from here");
    }

    @Test
    void aBuildTakenOverByAnotherControllerIsShownRunningThereOnceItsCopyHereStopped() throws Exception {
        Semaphore release = new Semaphore(0);
        FreeStyleProject p = waiting(r, "moved", release);
        FreeStyleBuild b = p.scheduleBuild2(0).waitForStart();
        ClusterFixture.await("owned", () -> RunningBuilds.ownedPlaceholder(b) != null);
        RunningBuilds.Placeholder owned = RunningBuilds.ownedPlaceholder(b);
        // taken over by another controller, which names itself in the placeholder (as an adoption does)
        store().gone(store().getOrigin(), true);
        InMemoryClusterStore other = store().as("other");
        long epoch = other.leases().acquire(owned.getLeaseKey());
        assertTrue(epoch > owned.getEpoch());
        store().gone(store().getOrigin(), false);
        long version = store().read(owned.getKey()).version();
        RunningBuilds.Placeholder theirs = new RunningBuilds.Placeholder(
                owned.getPrefix(),
                1,
                "other",
                "controller-other",
                "#1",
                System.currentTimeMillis(),
                epoch,
                Map.of(),
                version);
        other.leases().writeIfHeld(owned.getLeaseKey(), epoch, theirs.getKey(), theirs.toBytes(), version);
        ClusterFixture.awaitApplied();
        RunningBuilds.checkOwnership();
        r.waitForCompletion(b);
        assertEquals(Result.NOT_BUILT, b.getResult(), "never shown as aborted");
        // the stopped copy is dropped as soon as it is finalized, rather than shown here for a while
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (p.getBuildByNumber(1) != null) {
            assertTrue(System.nanoTime() < deadline, "the stopped copy is still shown: " + p.getBuildByNumber(1));
            Thread.sleep(20);
        }
        // shown as running on the controller which took it over (read in the background, not while rendering)
        ClusterFixture.await(
                "shown as running elsewhere", () -> ExternalBuilds.get().get(p, 1) != null);
        ExternalBuild elsewhere = ExternalBuilds.get().get(p, 1);
        assertEquals("controller-other", elsewhere.getLocation());
        assertTrue(p.isBuilding());
    }

    @Test
    void aBuildOfAGoneControllerIsClosedWhenNothingCanResumeIt() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("orphan");
        runningElsewhere(p, 1, "dead");
        store().gone("dead", true);
        assertEquals(1, RunningBuilds.removeStale());
        String prefix = Keys.item(p);
        assertNull(store().read(Keys.placeholder(prefix, 1)));
        assertThat(
                new String(store().read(Keys.buildRecord(prefix, 1)).content(), StandardCharsets.UTF_8),
                containsString("<result>ABORTED</result>"));
        FreeStyleBuild b = p.getBuildByNumber(1);
        assertNotNull(b);
        assertThat(b.getLog(), containsString("could not be resumed elsewhere"));
        assertEquals(2, p.getNextBuildNumber(), "past the build numbered elsewhere");
        assertFalse(ExternalBuilds.get().isBuilding(p));
    }

    /** Resumes what it is offered, as a Pipeline adopter would from a save point. */
    @TestExtension("aBuildOfAGoneControllerIsAdoptedByAnAdopter")
    public static final class Adopter implements BuildAdopter {
        static volatile long resumedWith;

        @Override
        public Adoption prepare(hudson.model.Job<?, ?> job, RunningBuilds.Placeholder placeholder) {
            return new Adoption() {
                @Override
                public void resume(long epoch) {
                    resumedWith = epoch;
                }

                @Override
                public void abandon() {}
            };
        }
    }

    @Test
    void aBuildOfAGoneControllerIsAdoptedByAnAdopter() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("adopted");
        runningElsewhere(p, 1, "dead");
        store().gone("dead", true);
        assertEquals(1, RunningBuilds.removeStale());
        String prefix = Keys.item(p);
        assertTrue(Adopter.resumedWith > 0);
        assertThat(
                new String(store().read(Keys.placeholder(prefix, 1)).content(), StandardCharsets.UTF_8),
                containsString("origin=" + store().getOrigin()));
        assertTrue(
                store().leases().holds(Keys.buildLease(prefix, 1), Adopter.resumedWith),
                "this controller holds its lease now");
        assertNull(store().read(Keys.buildRecord(prefix, 1)), "resumed, not closed");
    }

    @Test
    void aJobWhoseBuildRunsElsewhereIsBuildingEvenBeforeAnythingOfItIsCached() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("cold");
        runningElsewhere(p, 1, "other");
        ClusterFixture.awaitApplied();
        RunningBuilds.invalidate(Keys.item(p)); // nothing cached here, as after a restart
        // a rename, the queue or the CLI asks once: the answer is from the store, not "nothing cached, so not building"
        assertTrue(p.isBuilding(), "a rename is refused while it builds elsewhere");
        RunningBuilds.invalidate(Keys.item(p));
        assertNotNull(ExternalBuilds.get().get(p, 1), "its build by number");
        RunningBuilds.invalidate(Keys.item(p));
        assertEquals(1, RunningBuilds.newerElsewhere(p), "its newest build");
    }

    @Test
    void theLastBuildPermalinkOfAJobWhoseNewestBuildRunsElsewhereLeadsToThatBuild() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("newest");
        runningElsewhere(p, 1, "other");
        ClusterFixture.awaitApplied();
        RunningBuilds.invalidate(Keys.item(p)); // nothing cached here yet
        JenkinsRule.WebClient wc = r.createWebClient();
        HtmlPage page = wc.getPage(p, "lastBuild/");
        assertThat(page.getUrl().toString(), containsString("/job/newest/1/"));
        assertThat(page.asNormalizedText(), containsString("Running on controller controller-other"));
    }

    @Test
    void aBuildStartedElsewhereWhileTheJobsRunningBuildsAreLoadedIsNotMissed() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("loading");
        String prefix = Keys.item(p);
        runningElsewhere(p, 1, "other");
        ClusterFixture.awaitApplied();
        RunningBuilds.invalidate(prefix); // nothing cached
        AtomicBoolean once = new AtomicBoolean();
        // #2 starts elsewhere once #1 is listed, and its change is applied here before the listing is cached
        InMemoryClusterStore.hook = (operation, key) -> {
            if (operation.equals("children") && key.equals(Keys.running(prefix)) && once.compareAndSet(false, true)) {
                try {
                    runningElsewhere(p, 2, "other");
                    ClusterFixture.awaitApplied();
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }
        };
        try {
            assertEquals(
                    List.of(2, 1),
                    RunningBuilds.elsewhere(p).stream()
                            .map(RunningBuilds.Placeholder::getNumber)
                            .toList());
        } finally {
            InMemoryClusterStore.hook = null;
        }
        assertEquals(
                List.of(2, 1),
                RunningBuilds.elsewhere(p).stream()
                        .map(RunningBuilds.Placeholder::getNumber)
                        .toList(),
                "and cached so");
    }

    @Test
    void anExclusiveLeaseWhoseReleaseKeepsFailingIsReleasedOnceTheStoreAnswers() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("exclusive");
        String key = Keys.running(Keys.item(p)) + "/exclusive";
        int attempts = ExclusiveLeases.RELEASE_ATTEMPTS;
        ExclusiveLeases.RELEASE_ATTEMPTS = 0; // given up on at once, as after minutes of failures
        InMemoryClusterStore.hook = (operation, k) -> {
            if (operation.equals("release") && k.equals(key)) {
                throw new IOException("the store cannot be reached");
            }
        };
        try {
            assertTrue(ExclusiveLeases.acquire(p, ExclusiveLeases.claimHolder(1)));
            ExclusiveLeases.release(p, ExclusiveLeases.claimHolder(1));
            assertEquals(1, ExclusiveLeases.unreleasedCount());
            assertEquals(0, store().as("other").leases().acquire(key), "still held");
            ExclusiveLeases.releasePending();
            assertEquals(1, ExclusiveLeases.unreleasedCount(), "kept while it fails");
        } finally {
            InMemoryClusterStore.hook = null;
            ExclusiveLeases.RELEASE_ATTEMPTS = attempts;
        }
        ExclusiveLeases.releasePending(); // as the periodic cleanup does
        assertEquals(0, ExclusiveLeases.unreleasedCount());
        assertTrue(store().as("other").leases().acquire(key) > 0, "released");
    }

    @Test
    void anExclusiveLeaseHeldHereIsCheckedWithTheStoreOnceThisControllerMayHaveLookedGone() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("lapsed");
        String key = Keys.running(Keys.item(p)) + "/exclusive";
        String me = store().getOrigin();
        InMemoryClusterStore other = store().as("other");
        // still held once it looked gone, if nobody took it meanwhile
        assertTrue(ExclusiveLeases.acquire(p, ExclusiveLeases.claimHolder(1)));
        store().gone(me, true);
        store().gone(me, false);
        assertTrue(ExclusiveLeases.acquire(p, ExclusiveLeases.claimHolder(2)), "still this controller's");
        // taken over by another controller while this one looked gone
        store().gone(me, true);
        long epoch = other.leases().acquire(key);
        assertTrue(epoch > 0, "taken over");
        store().gone(me, false);
        try {
            assertFalse(
                    ExclusiveLeases.acquire(p, ExclusiveLeases.claimHolder(3)),
                    "the hold here is not trusted: another controller holds the lease");
            assertEquals(0, store().leases().acquire(key), "still the other controller's");
        } finally {
            ExclusiveLeases.release(p, ExclusiveLeases.claimHolder(1));
            ExclusiveLeases.release(p, ExclusiveLeases.claimHolder(2));
            other.leases().release(key, epoch);
        }
        assertTrue(ExclusiveLeases.acquire(p, ExclusiveLeases.claimHolder(4)), "taken here once it is free");
        ExclusiveLeases.release(p, ExclusiveLeases.claimHolder(4));
    }

    @Test
    void theControllerNameOfABuildElsewhereIsShownAsText() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("named");
        String prefix = Keys.item(p);
        InMemoryClusterStore other = store().as("other");
        long epoch = other.leases().acquire(Keys.buildLease(prefix, 1));
        String name = "<img src=x id=injected>";
        RunningBuilds.Placeholder ph = new RunningBuilds.Placeholder(
                prefix, 1, "other", name, "#1", System.currentTimeMillis(), epoch, Map.of(), PersistenceStore.ABSENT);
        other.leases()
                .writeIfHeld(Keys.buildLease(prefix, 1), epoch, ph.getKey(), ph.toBytes(), PersistenceStore.ABSENT);
        ClusterFixture.await("listed", () -> !ExternalBuilds.get().running(p).isEmpty());
        // with the new job page, whose build history shows it with a tooltip
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        User.getById("alice", true).addProperty(new UserExperimentalFlagsProperty(Map.of("new-job-page.flag", "true")));
        JenkinsRule.WebClient wc = r.createWebClient().login("alice");
        HtmlPage page = wc.getPage(p);
        wc.waitForBackgroundJavaScript(5000);
        List<String> tooltips = new java.util.ArrayList<>();
        for (org.htmlunit.html.DomNode n : page.querySelectorAll("[tooltip], [data-html-tooltip]")) {
            org.htmlunit.html.DomElement e = (org.htmlunit.html.DomElement) n;
            if (e.hasAttribute("data-html-tooltip")) {
                tooltips.add("html:" + e.getAttribute("data-html-tooltip"));
            }
            if (e.hasAttribute("tooltip")) {
                tooltips.add("text:" + e.getAttribute("tooltip"));
            }
        }
        assertThat(tooltips.toString(), tooltips.contains("text:Running on " + name));
        assertThat(tooltips.toString(), tooltips.stream().noneMatch(t -> t.startsWith("html:Running on")));
        assertNull(page.getElementById("injected"));
    }

    @Test
    void aBuildIsNotHandedOverOnceItCompleted() throws Exception {
        Semaphore release = new Semaphore(0);
        FreeStyleProject p = waiting(r, "completing", release);
        FreeStyleBuild b = p.scheduleBuild2(0).waitForStart();
        ClusterFixture.await("owned", () -> RunningBuilds.ownedPlaceholder(b) != null);
        RunningBuilds.update(b, Map.of(RunningBuilds.RESULT, "SUCCESS")); // as its completion records
        assertThrows(java.io.IOException.class, () -> RunningBuilds.handOver(b));
        assertNotNull(RunningBuilds.ownedPlaceholder(b), "still this controller's");
        assertFalse(RunningBuilds.wasLost(b));
        release.release();
        r.assertBuildStatusSuccess(r.waitForCompletion(b));
    }

    @Test
    void theArtifactsOfACopyTakenOverByAnotherControllerAreKept() throws Exception {
        Semaphore release = new Semaphore(0);
        FreeStyleProject p = r.createFreeStyleProject("artifacts");
        p.getBuildersList().add(new TestBuilder() {
            @Override
            public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
                    throws InterruptedException, java.io.IOException {
                java.nio.file.Path a = build.getRootDir().toPath().resolve("archive/a.txt");
                java.nio.file.Files.createDirectories(a.getParent());
                java.nio.file.Files.writeString(a, "archived here");
                release.acquire();
                return true;
            }
        });
        FreeStyleBuild b = p.scheduleBuild2(0).waitForStart();
        ClusterFixture.await("owned", () -> RunningBuilds.ownedPlaceholder(b) != null);
        RunningBuilds.Placeholder owned = RunningBuilds.ownedPlaceholder(b);
        store().gone(store().getOrigin(), true);
        assertTrue(store().as("other").leases().acquire(owned.getLeaseKey()) > owned.getEpoch());
        store().gone(store().getOrigin(), false);
        RunningBuilds.checkOwnership();
        r.waitForCompletion(b);
        ClusterFixture.await("the copy here discarded", () -> !new java.io.File(p.getBuildDir(), "1").exists());
        java.io.File[] aside = p.getBuildDir().listFiles((d, n) -> n.startsWith(".1-discarded-"));
        assertNotNull(aside);
        assertEquals(1, aside.length, "moved aside, not deleted");
        assertEquals(
                "archived here",
                java.nio.file.Files.readString(aside[0].toPath().resolve("archive/a.txt")));
    }

    @Test
    void flowNodeKeysNeverBecomePathsOutsideTheStagingDirectory() throws Exception {
        WorkflowJob p = r.createProject(WorkflowJob.class, "staged");
        String base = BuildRecords.buildBlobs(p, 1) + "/running";
        put(base + "/build.xml.5.1", "<record/>");
        put(base + "/program.dat.5.1", "program");
        put(base + "/workflow/2.xml", "a node");
        put(base + "/workflow/../../../escaped.xml", "written by whatever can write to the blob store");
        put(base + "/workflow.5.1/..", "not a name");
        RunningBuilds.Placeholder ph = new RunningBuilds.Placeholder(
                Keys.item(p),
                1,
                "dead",
                "controller-dead",
                "#1",
                System.currentTimeMillis(),
                1,
                Map.of("savePoint", "5.1"),
                PersistenceStore.ABSENT);
        BuildAdopter.Adoption adoption = new PipelineAdopter().prepare(p, ph);
        assertNotNull(adoption);
        try (java.util.stream.Stream<java.nio.file.Path> files =
                java.nio.file.Files.walk(r.jenkins.getRootDir().toPath())) {
            for (java.nio.file.Path f : files.toList()) {
                if (f.getFileName().toString().equals("escaped.xml")) {
                    assertThat(f.toString(), containsString("-adoption-"));
                }
            }
        }
        adoption.abandon();
    }

    private static void put(String key, String content) throws java.io.IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        ClusterFixture.blobs().put(key, new java.io.ByteArrayInputStream(bytes), bytes.length);
    }

    @Test
    void aBuildStartedElsewhereIsSeenByAJobWhoseBuildsAreCachedAlready() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("warm");
        assertFalse(p.isBuilding()); // cached: none
        runningElsewhere(p, 1, "other");
        ClusterFixture.await(
                "listed from the cache kept up to date",
                () -> ExternalBuilds.get().running(p).size() == 1);
        assertTrue(p.isBuilding());
    }
}
