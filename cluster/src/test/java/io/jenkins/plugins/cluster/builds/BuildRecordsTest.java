package io.jenkins.plugins.cluster.builds;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import io.jenkins.plugins.cluster.ClusterFixture;
import io.jenkins.plugins.cluster.PerItemCache;
import io.jenkins.plugins.cluster.spi.InMemoryClusterStore;
import io.jenkins.plugins.cluster.spi.Keys;
import io.jenkins.plugins.cluster.spi.Resync;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import jenkins.persistence.PersistenceStore;
import org.apache.tools.tar.TarConstants;
import org.apache.tools.tar.TarEntry;
import org.apache.tools.tar.TarInputStream;
import org.apache.tools.tar.TarOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockFolder;
import org.jvnet.hudson.test.TestBuilder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Completed builds are published to the stores by the job's identity, and a controller without their directories lists
 * and opens them.
 */
@WithJenkins
class BuildRecordsTest {

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

    /** Writes to the log, to a data file, and to {@code archive/}. */
    private static FreeStyleProject project(JenkinsRule r, String name) throws IOException {
        FreeStyleProject p = r.createFreeStyleProject(name);
        p.getBuildersList().add(new Writes());
        return p;
    }

    /** Named, so that the project loads again after a reload. */
    public static final class Writes extends TestBuilder {
        @Override
        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
                throws IOException {
            listener.getLogger().println("hello from #" + build.getNumber());
            File data = new File(build.getRootDir(), "extra/data.txt");
            Files.createDirectories(data.getParentFile().toPath());
            Files.writeString(data.toPath(), "data of #" + build.getNumber());
            File archived = new File(build.getRootDir(), "archive/a.txt");
            Files.createDirectories(archived.getParentFile().toPath());
            Files.writeString(archived.toPath(), "artifact");
            return true;
        }
    }

    private static String record(FreeStyleProject p, int n) {
        PersistenceStore.Entry e = store().read(Keys.buildRecord(Keys.item(p), n));
        return e == null ? null : new String(e.content(), StandardCharsets.UTF_8);
    }

    /** As a controller which never had the job's builds on disk: its build directories gone, its index read again. */
    private static FreeStyleProject withoutLocalBuilds(JenkinsRule r, FreeStyleProject p) throws Exception {
        Util.deleteRecursive(p.getBuildDir());
        PerItemCache.forgetAllForTests();
        r.jenkins.reload();
        return r.jenkins.getItemByFullName(p.getFullName(), FreeStyleProject.class);
    }

    @Test
    void aCompletedBuildIsPublishedByItsJobsIdentity() throws Exception {
        FreeStyleProject p = project(r, "published");
        r.buildAndAssertSuccess(p);
        String uuid = p.getItemUuid();
        assertThat(record(p, 1), containsString("<result>SUCCESS</result>"));
        assertEquals("_items/" + uuid + "/builds/1/build.xml", Keys.buildRecord(Keys.item(p), 1));
        assertThat(ClusterFixture.blobs().list("items/" + uuid), hasItem("items/" + uuid + "/builds/1/files.tgz"));
        assertTrue(
                new File(p.getBuildByNumber(1).getRootDir(), "build.xml").isFile(),
                "build.xml is still written locally");
        assertNull(store().read(Keys.placeholder(Keys.item(p), 1)), "no longer running anywhere");
        assertEquals(
                "2",
                new String(store().read(Keys.nextBuildNumber(Keys.item(p))).content(), StandardCharsets.UTF_8).trim());
    }

    @Test
    void aControllerWithoutTheBuildsListsAndOpensThem() throws Exception {
        FreeStyleProject p = project(r, "fresh");
        r.buildAndAssertSuccess(p);
        r.buildAndAssertSuccess(p);
        p.getBuildByNumber(1).setDescription("edited after completion");

        p = withoutLocalBuilds(r, p);

        FreeStyleBuild b1 = p.getBuildByNumber(1);
        assertFalse(new File(p.getBuildDir(), "2").exists(), "build 2 is not opened");
        assertEquals(Result.SUCCESS, b1.getResult());
        assertEquals("edited after completion", b1.getDescription());
        assertThat(b1.getLog(), containsString("hello from #1"));
        assertEquals("data of #1", Files.readString(new File(b1.getRootDir(), "extra/data.txt").toPath()));
        assertFalse(new File(b1.getRootDir(), "archive").exists(), "artifacts are not part of the snapshot");
        assertEquals(2, p.getLastSuccessfulBuild().getNumber());
        assertEquals(
                List.of(2, 1),
                p.getBuilds().stream().map(FreeStyleBuild::getNumber).toList());
        r.assertBuildStatusSuccess(p.scheduleBuild2(0));
        assertEquals(3, p.getLastBuild().getNumber());
    }

    /** Whether a store key is a build's record or snapshot (what fetching a build reads). */
    private static boolean buildData(FreeStyleProject p, String key) {
        return key.startsWith(Keys.builds(Keys.item(p)) + "/") && key.endsWith("/" + BuildRecords.RECORD)
                || key.startsWith(Keys.blobs(p) + "/");
    }

    @Test
    void theQueueNeverWaitsForABuildMadeElsewhereToBeFetched() throws Exception {
        FreeStyleProject made = project(r, "queued");
        r.buildAndAssertSuccess(made);
        r.buildAndAssertSuccess(made);
        FreeStyleProject p = withoutLocalBuilds(r, made);
        List<String> underTheQueueLock = new java.util.concurrent.CopyOnWriteArrayList<>();
        Thread[] queueLockHolder = new Thread[1];
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        InMemoryClusterStore.hook = (operation, key) -> {
            if (!buildData(p, key)) {
                return;
            }
            if (Thread.currentThread() == queueLockHolder[0]) {
                underTheQueueLock.add(operation + " " + key);
            }
            try {
                go.await(); // a slow store: the build is fetched only once the queue is done
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
        };
        try {
            // as Queue.maintain asks a job for its last build
            java.util.concurrent.CompletableFuture<Void> maintain =
                    java.util.concurrent.CompletableFuture.runAsync(() -> hudson.model.Queue.withLock(() -> {
                        queueLockHolder[0] = Thread.currentThread();
                        try {
                            p.getLastBuild();
                        } finally {
                            queueLockHolder[0] = null;
                        }
                    }));
            maintain.get(30, java.util.concurrent.TimeUnit.SECONDS); // not waiting for the store
            assertThat("no store read under the queue's lock", underTheQueueLock, empty());
            go.countDown();
            ClusterFixture.await("#2 fetched, and loaded on its next access", () -> {
                FreeStyleBuild last = p.getLastBuild();
                return last != null && last.getNumber() == 2;
            });
        } finally {
            go.countDown();
            InMemoryClusterStore.hook = null;
        }
        FreeStyleBuild b2 = p.getBuildByNumber(2);
        assertThat(b2.getLog(), containsString("hello from #2"));
        assertEquals("data of #2", Files.readString(new File(b2.getRootDir(), "extra/data.txt").toPath()));
        ClusterFixture.await(
                "nothing staged is left next to the builds",
                () -> Stream.of(p.getBuildDir().list()).noneMatch(n -> n.startsWith(".")));
    }

    @Test
    void aBuildMadeElsewhereIsFetchedWithoutHoldingItsJobsBuildIndex() throws Exception {
        FreeStyleProject made = project(r, "indexed");
        r.buildAndAssertSuccess(made);
        FreeStyleProject p = withoutLocalBuilds(r, made);
        String snapshot = Keys.snapshotBlob(Keys.blobs(p), 1);
        java.util.concurrent.CountDownLatch fetching = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        InMemoryClusterStore.hook = (operation, key) -> {
            if (operation.equals("blob") && key.equals(snapshot)) {
                fetching.countDown();
                try {
                    go.await();
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
            }
        };
        try {
            java.util.concurrent.CompletableFuture<FreeStyleBuild> load =
                    java.util.concurrent.CompletableFuture.supplyAsync(() -> p.getBuildByNumber(1));
            assertTrue(fetching.await(30, java.util.concurrent.TimeUnit.SECONDS), "fetching #1");
            // while it is fetched (its snapshot downloads), the job's build index is free: e.g. for the queue
            java.util.concurrent.CompletableFuture<Boolean> index = java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> p._getRuns().knows(1));
            assertTrue(index.get(30, java.util.concurrent.TimeUnit.SECONDS));
            go.countDown();
            FreeStyleBuild b1 = load.get(30, java.util.concurrent.TimeUnit.SECONDS);
            assertNotNull(b1, "loaded once fetched");
            assertThat(b1.getLog(), containsString("hello from #1"));
        } finally {
            go.countDown();
            InMemoryClusterStore.hook = null;
        }
    }

    /** Holds the download of a build's snapshot until released: a slow blob store. */
    private static final class SlowSnapshot implements AutoCloseable {
        final java.util.concurrent.CountDownLatch fetching = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);

        SlowSnapshot(FreeStyleProject p, int number) {
            String snapshot = Keys.snapshotBlob(Keys.blobs(p), number);
            InMemoryClusterStore.hook = (operation, key) -> {
                if (operation.equals("blob") && key.equals(snapshot)) {
                    fetching.countDown();
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        throw new IOException(e);
                    }
                }
            };
        }

        void awaitFetching() throws InterruptedException {
            assertTrue(fetching.await(30, java.util.concurrent.TimeUnit.SECONDS), "fetching");
        }

        @Override
        public void close() {
            go.countDown();
            InMemoryClusterStore.hook = null;
        }
    }

    /** A build asked for under the queue's lock, as Queue.maintain asks a job for its last build. */
    private static FreeStyleBuild underTheQueueLock(FreeStyleProject p, int number) throws Exception {
        FreeStyleBuild[] b = new FreeStyleBuild[1];
        java.util.concurrent.CompletableFuture.runAsync(() -> hudson.model.Queue.withLock(() -> {
                    b[0] = p.getBuildByNumber(number);
                }))
                .get(30, java.util.concurrent.TimeUnit.SECONDS);
        return b[0];
    }

    /** A load waiting for the fetch of a build, the build index lock released meanwhile. */
    private static Thread waitingLoad(FreeStyleProject p, int number, FreeStyleBuild[] loaded) throws Exception {
        Thread t = new Thread(() -> loaded[0] = p.getBuildByNumber(number), "load of #" + number);
        t.start();
        ClusterFixture.await(
                t.getName() + " waiting for the fetch",
                () -> t.getState() == Thread.State.TIMED_WAITING
                        && Stream.of(t.getStackTrace())
                                .anyMatch(e -> e.getClassName().equals(BuildFetches.class.getName())
                                        && e.getMethodName().equals("await")));
        return t;
    }

    @Test
    void aLoadWaitingForAFetchWhenTheQueueGivesUpOnTheBuildLoadsItOnce() throws Exception {
        FreeStyleProject made = project(r, "given-up");
        r.buildAndAssertSuccess(made);
        FreeStyleProject p = withoutLocalBuilds(r, made);
        FreeStyleBuild[] page = new FreeStyleBuild[1];
        try (SlowSnapshot slow = new SlowSnapshot(p, 1)) {
            Thread load = waitingLoad(p, 1, page);
            slow.awaitFetching();
            assertNull(underTheQueueLock(p, 1), "the queue does not wait, and gives up on the build");
            slow.go.countDown();
            load.join(30_000);
            assertFalse(load.isAlive());
        }
        assertNotNull(page[0], "loaded once fetched");
        assertSame(page[0], p.getBuildByNumber(1), "one instance of the build, not one per load");
    }

    @Test
    void twoLoadsWaitingForTheSameFetchGetTheSameBuild() throws Exception {
        FreeStyleProject made = project(r, "two-loads");
        r.buildAndAssertSuccess(made);
        FreeStyleProject p = withoutLocalBuilds(r, made);
        FreeStyleBuild[] first = new FreeStyleBuild[1];
        FreeStyleBuild[] second = new FreeStyleBuild[1];
        try (SlowSnapshot slow = new SlowSnapshot(p, 1)) {
            Thread one = waitingLoad(p, 1, first);
            slow.awaitFetching();
            Thread two = waitingLoad(p, 1, second);
            slow.go.countDown();
            one.join(30_000);
            two.join(30_000);
            assertFalse(one.isAlive() || two.isAlive());
        }
        assertNotNull(first[0], "loaded once fetched");
        assertSame(first[0], second[0], "one instance of the build");
        assertSame(first[0], p.getBuildByNumber(1));
    }

    @Test
    void aBuildAskedForWhileAFetchTheQueueGaveUpOnRunsWaitsForIt() throws Exception {
        FreeStyleProject made = project(r, "page");
        r.buildAndAssertSuccess(made);
        FreeStyleProject p = withoutLocalBuilds(r, made);
        FreeStyleBuild[] page = new FreeStyleBuild[1];
        try (SlowSnapshot slow = new SlowSnapshot(p, 1)) {
            assertNull(underTheQueueLock(p, 1), "the queue does not wait, and gives up on the build");
            slow.awaitFetching();
            String key = Keys.buildRecord(Keys.item(p), 1);
            ClusterFixture.await(
                    "the build loadable again, though not fetched yet", () -> !BuildFetches.isDeferred(key));
            Thread load = waitingLoad(p, 1, page); // e.g. its page: waits rather than finding nothing
            slow.go.countDown();
            load.join(30_000);
            assertFalse(load.isAlive());
        }
        assertNotNull(page[0], "loaded once fetched");
        assertThat(page[0].getLog(), containsString("hello from #1"));
        assertSame(page[0], p.getBuildByNumber(1));
    }

    @Test
    void aCopyLeftRunningByAPreviousRunIsReplacedByTheRecordOfTheBuildCompletedElsewhere() throws Exception {
        FreeStyleProject made = project(r, "reclaimed");
        r.buildAndAssertSuccess(made);
        String stored = record(made, 1);
        FreeStyleProject p = withoutLocalBuilds(r, made);
        // as this controller left it when it stopped, before another controller adopted and completed the build
        File local = new File(p.getBuildDir(), "1/" + BuildRecords.RECORD);
        Files.createDirectories(local.getParentFile().toPath());
        Files.writeString(local.toPath(), stored.replace("<result>SUCCESS</result>", ""));
        List<String> told = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.logging.Logger logger = java.util.logging.Logger.getLogger(RunningBuilds.class.getName());
        java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                told.add(record.getMessage());
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.addHandler(handler);
        try {
            underTheQueueLock(p, 1); // not resumed from the copy here; fetched in the background
            ClusterFixture.await("#1 loaded from its record", () -> {
                FreeStyleBuild b = p.getBuildByNumber(1);
                return b != null && b.getResult() == Result.SUCCESS;
            });
        } finally {
            logger.removeHandler(handler);
        }
        assertThat(told, hasItem(containsString("completed elsewhere while this controller restarted")));
        assertThat(told, not(hasItem(containsString("neither running nor recorded"))));
    }

    @Test
    void aStoredRecordWhoseSnapshotIsMissingIsNotLoadableAndTheJobStillAnswers() throws Exception {
        FreeStyleProject p = project(r, "restored");
        r.buildAndAssertSuccess(p);
        r.buildAndAssertSuccess(p);
        // e.g. the store restored to a point in time, the blob store not: #2's record is there, its snapshot not
        ClusterFixture.blobs().delete(Keys.snapshotBlob(Keys.blobs(p), 2));
        p = withoutLocalBuilds(r, p);
        assertNull(p.getBuildByNumber(2), "not loadable without its files");
        assertEquals(1, p.getBuildByNumber(1).getNumber());
        JenkinsRule.WebClient wc = r.createWebClient();
        assertEquals(200, wc.getPage(p).getWebResponse().getStatusCode());
        assertEquals(
                200,
                wc.goTo(p.getUrl() + "api/json?depth=1", "application/json")
                        .getWebResponse()
                        .getStatusCode());
    }

    @Test
    void aRenameOrMoveKeepsTheBuildsWhereTheyAre() throws Exception {
        FreeStyleProject p = project(r, "before");
        r.buildAndAssertSuccess(p);
        String prefix = Keys.item(p);
        p.renameTo("after");
        MockFolder f = r.createFolder("f");
        p = r.jenkins.getItemByFullName("after", FreeStyleProject.class);
        hudson.model.Items.move(p, f);
        p = r.jenkins.getItemByFullName("f/after", FreeStyleProject.class);
        assertEquals(prefix, Keys.item(p), "the identity stays");
        assertNotNull(store().read(Keys.buildRecord(prefix, 1)));
        r.buildAndAssertSuccess(p);
        assertNotNull(store().read(Keys.buildRecord(prefix, 2)));
        assertEquals(
                List.of(2, 1),
                p.getBuilds().stream().map(FreeStyleBuild::getNumber).toList());
    }

    @Test
    void linksAreLeftOutOfSnapshotsAndNeverMaterialized(@TempDir Path outside) throws Exception {
        Path secret = Files.writeString(outside.resolve("secret.txt"), "outside the build");
        FreeStyleProject p = r.createFreeStyleProject("links");
        p.getBuildersList().add(new Links(secret.toString()));
        r.buildAndAssertSuccess(p);
        String snapshot = Keys.snapshotBlob(Keys.blobs(p), 1);
        List<String> names = new ArrayList<>();
        try (TarInputStream tar =
                new TarInputStream(new GZIPInputStream(ClusterFixture.blobs().get(snapshot)))) {
            for (TarEntry e = tar.getNextEntry(); e != null; e = tar.getNextEntry()) {
                names.add(e.getName());
                assertFalse(e.isSymbolicLink(), e.getName());
                if (!e.isDirectory()) {
                    assertThat(
                            new String(tar.readAllBytes(), StandardCharsets.UTF_8),
                            not(containsString("outside the build")));
                }
            }
        }
        assertThat(names, hasItem("own.txt"));

        // a snapshot with a link in it, as no publisher here makes
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TarOutputStream tar = new TarOutputStream(new GZIPOutputStream(bytes))) {
            TarEntry link = new TarEntry("log", TarConstants.LF_SYMLINK);
            link.setLinkName(outside.resolve("elsewhere").toString());
            tar.putNextEntry(link);
            tar.closeEntry();
        }
        ClusterFixture.blobs().put(snapshot, new ByteArrayInputStream(bytes.toByteArray()), bytes.size());
        p = withoutLocalBuilds(r, p);
        File dir = p.getBuildByNumber(1).getRootDir();
        assertFalse(Files.exists(dir.toPath().resolve("log"), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void aDeletedBuildIsRemovedFromTheStores() throws Exception {
        FreeStyleProject p = project(r, "deleted");
        r.buildAndAssertSuccess(p);
        r.buildAndAssertSuccess(p);
        p.getBuildByNumber(1).delete();
        assertNull(record(p, 1));
        assertNotNull(record(p, 2));
        assertThat(ClusterFixture.blobs().list(BuildRecords.buildBlobs(p, 1)), empty());
        p = withoutLocalBuilds(r, p);
        assertEquals(
                List.of(2),
                p.getBuilds().stream().map(FreeStyleBuild::getNumber).toList());
    }

    @Test
    void aRecordWrittenElsewhereIsListedAndARepublishHereNeverWritesOverAnEditElsewhere() throws Exception {
        FreeStyleProject p = project(r, "edited");
        FreeStyleBuild b = r.buildAndAssertSuccess(p);
        String key = Keys.buildRecord(Keys.item(p), 1);
        InMemoryClusterStore other = store().as("other");
        // edited on another controller
        String elsewhere = record(p, 1)
                .replace(
                        "<keepLog>false</keepLog>",
                        "<description>from elsewhere</description><keepLog>false</keepLog>");
        // and here, by a copy which has not applied that yet (applying it waits for the job's builds): refused, and the
        // copy refreshed
        synchronized (BuildRecords.runsOf(p)) {
            other.write(key, elsewhere.getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
            b.setDescription("from here");
        }
        assertThat(record(p, 1), containsString("from elsewhere"));
        ClusterFixture.await(
                "the copy here refreshed",
                () -> "from elsewhere".equals(p.getBuildByNumber(1).getDescription()));
    }

    @Test
    void aSaveOfACopyRefreshedSinceItWasLoadedNeverWritesOverAnEditElsewhere() throws Exception {
        FreeStyleProject p = project(r, "stale");
        FreeStyleBuild b = r.buildAndAssertSuccess(p);
        String key = Keys.buildRecord(Keys.item(p), 1);
        String elsewhere = record(p, 1)
                .replace(
                        "<keepLog>false</keepLog>",
                        "<description>from elsewhere</description><keepLog>false</keepLog>");
        store().as("other").write(key, elsewhere.getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
        ClusterFixture.await(
                "the copy here refreshed",
                () -> "from elsewhere".equals(p.getBuildByNumber(1).getDescription()));
        b.setDescription("from here"); // b is a build object from before
        assertThat(record(p, 1), containsString("from elsewhere"));
        ClusterFixture.await(
                "the copy here refreshed",
                () -> "from elsewhere".equals(p.getBuildByNumber(1).getDescription()));
    }

    @Test
    void aBuildRecordedElsewhereRaisesTheNextBuildNumberShownHere() throws Exception {
        FreeStyleProject p = project(r, "numbered");
        r.buildAndAssertSuccess(p);
        // build #2 ran and completed on another controller
        store().as("other")
                .write(
                        Keys.buildRecord(Keys.item(p), 2),
                        record(p, 1).getBytes(StandardCharsets.UTF_8),
                        PersistenceStore.ABSENT);
        ClusterFixture.await("the next build number follows", () -> p.getNextBuildNumber() == 3);
    }

    @Test
    void aJobDeletedHereLeavesNothingStored() throws Exception {
        FreeStyleProject p = project(r, "gone");
        r.buildAndAssertSuccess(p);
        String uuid = p.getItemUuid();
        p.delete();
        ClusterFixture.await(
                "its data removed",
                () -> store().children(Keys.itemByUuid(uuid)).isEmpty()
                        && ClusterFixture.blobs().list("items/" + uuid).isEmpty()
                        && store().children(BuildRecords.DELETING).isEmpty());
        FreeStyleProject again = project(r, "gone");
        assertNotEquals(uuid, again.getItemUuid());
        r.buildAndAssertSuccess(again);
        assertEquals(1, again.getLastBuild().getNumber(), "a new job, numbered afresh");
    }

    @Test
    void aStoredSequenceIsFollowedAndNeverLowered() throws Exception {
        FreeStyleProject p = project(r, "numbers");
        store().as("other")
                .write(
                        Keys.nextBuildNumber(Keys.item(p)),
                        "7\n".getBytes(StandardCharsets.UTF_8),
                        PersistenceStore.ABSENT);
        assertEquals(7, r.buildAndAssertSuccess(p).getNumber());
        StoredBuildNumberAssigner.raiseTo(p, 3);
        assertEquals(8, p.getNextBuildNumber(), "the local number follows");
        assertEquals(
                "8",
                new String(store().read(Keys.nextBuildNumber(Keys.item(p))).content(), StandardCharsets.UTF_8).trim());
        p.updateNextBuildNumber(20);
        assertEquals(20, r.buildAndAssertSuccess(p).getNumber(), "a number raised here raises the sequence");
    }

    @Test
    void aBuildChangedElsewhereKeepsTheArtifactsArchivedHere() throws Exception {
        FreeStyleProject p = project(r, "archived");
        FreeStyleBuild b = r.buildAndAssertSuccess(p);
        assertTrue(new File(b.getRootDir(), "archive/a.txt").isFile());
        // another controller set its description, and published a snapshot without extra/data.txt (removed there)
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TarOutputStream tar = new TarOutputStream(new GZIPOutputStream(bytes))) {
            byte[] log = "hello from #1, as elsewhere\n".getBytes(StandardCharsets.UTF_8);
            TarEntry e = new TarEntry("log");
            e.setSize(log.length);
            tar.putNextEntry(e);
            tar.write(log);
            tar.closeEntry();
        }
        ClusterFixture.blobs()
                .put(Keys.snapshotBlob(Keys.blobs(p), 1), new ByteArrayInputStream(bytes.toByteArray()), bytes.size());
        String elsewhere = record(p, 1)
                .replace(
                        "<keepLog>false</keepLog>",
                        "<description>from elsewhere</description><keepLog>false</keepLog>");
        store().as("other")
                .write(
                        Keys.buildRecord(Keys.item(p), 1),
                        elsewhere.getBytes(StandardCharsets.UTF_8),
                        PersistenceStore.ANY);
        ClusterFixture.await(
                "the copy here refreshed",
                () -> "from elsewhere".equals(p.getBuildByNumber(1).getDescription()));
        File dir = p.getBuildByNumber(1).getRootDir();
        assertEquals(
                "artifact",
                Files.readString(new File(dir, "archive/a.txt").toPath()),
                "artifacts are in no snapshot: their only copy is here");
        assertThat(p.getBuildByNumber(1).getLog(), containsString("as elsewhere"));
        assertFalse(new File(dir, "extra/data.txt").exists(), "a file of the snapshot removed elsewhere goes here too");
    }

    @Test
    void buildsMadeElsewhereAreListedAgainWhenTheyCouldNotBeListed() throws Exception {
        FreeStyleProject p = project(r, "unlisted");
        r.buildAndAssertSuccess(p);
        r.buildAndAssertSuccess(p);
        String builds = Keys.builds(Keys.item(p));
        InMemoryClusterStore.hook = (operation, key) -> {
            if (operation.equals("children") && key.equals(builds)) {
                throw new IOException("the store cannot be reached");
            }
        };
        FreeStyleProject fresh;
        try {
            fresh = withoutLocalBuilds(r, p);
            assertEquals(
                    List.of(),
                    fresh.getBuilds().stream().map(FreeStyleBuild::getNumber).toList());
        } finally {
            InMemoryClusterStore.hook = null;
        }
        BuildRecords.listAgain(); // as the periodic retry does
        assertEquals(
                List.of(2, 1),
                fresh.getBuilds().stream().map(FreeStyleBuild::getNumber).toList());
        assertThat(fresh.getBuildByNumber(2).getLog(), containsString("hello from #2"));
    }

    @Test
    void buildsChangedElsewhereWhoseEventsWereLostAreCaughtUpWith() throws Exception {
        FreeStyleProject p = project(r, "caught-up");
        r.buildAndAssertSuccess(p);
        r.buildAndAssertSuccess(p);
        assertNotNull(p.getBuildByNumber(1));
        String prefix = Keys.item(p);
        InMemoryClusterStore other = store().as("other");
        String edited = record(p, 1)
                .replace(
                        "<keepLog>false</keepLog>",
                        "<description>from elsewhere</description><keepLog>false</keepLog>");
        // completed (#3), edited (#1) and deleted (#2) elsewhere, none of their change events reaching here
        store().loseEvents(true);
        try {
            other.write(
                    Keys.buildRecord(prefix, 3),
                    record(p, 2).getBytes(StandardCharsets.UTF_8),
                    PersistenceStore.ABSENT);
            other.write(Keys.buildRecord(prefix, 1), edited.getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
            other.delete(Keys.buildRecord(prefix, 2), PersistenceStore.ANY);
        } finally {
            store().loseEvents(false);
        }
        assertEquals(3, p.getNextBuildNumber(), "nothing of it known here yet");
        assertNotNull(p.getBuildByNumber(2));
        // as the store's listener asks when it cannot rule out lost events
        Resync.request("a test lost change events");
        ClusterFixture.await(
                "the build completed elsewhere is listed",
                () -> p.getNextBuildNumber() == 4 && new File(p.getBuildDir(), "3").isDirectory());
        ClusterFixture.await("the build deleted elsewhere is forgotten", () -> p.getBuildByNumber(2) == null);
        ClusterFixture.await(
                "the edit made elsewhere is applied",
                () -> "from elsewhere".equals(p.getBuildByNumber(1).getDescription()));
    }

    @Test
    void aRetriedPublicationNeverWritesOverAnEditMadeElsewhereSince() throws Exception {
        FreeStyleProject p = project(r, "retried");
        String lease = Keys.buildLease(Keys.item(p), 1);
        // published, but the placeholder's removal fails: retried later
        InMemoryClusterStore.hook = (operation, key) -> {
            if (operation.equals("release") && key.equals(lease)) {
                throw new IOException("the store cannot be reached");
            }
        };
        FreeStyleBuild b;
        try {
            b = r.buildAndAssertSuccess(p);
            ClusterFixture.await("published", () -> record(p, 1) != null);
            assertTrue(BuildRecords.hasUnpublished(p), "its placeholder's removal is retried");
        } finally {
            InMemoryClusterStore.hook = null;
        }
        // meanwhile another controller set its description
        String elsewhere = record(p, 1)
                .replace(
                        "<keepLog>false</keepLog>",
                        "<description>from elsewhere</description><keepLog>false</keepLog>");
        // the retry, before this controller applied that change (applying it waits for the job's builds)
        synchronized (BuildRecords.runsOf(p)) {
            store().as("other")
                    .write(
                            Keys.buildRecord(Keys.item(p), 1),
                            elsewhere.getBytes(StandardCharsets.UTF_8),
                            PersistenceStore.ANY);
            BuildRecords.complete(b);
        }
        assertThat(record(p, 1), containsString("from elsewhere"));
        assertNull(store().read(Keys.placeholder(Keys.item(p), 1)), "its placeholder removed");
        assertFalse(BuildRecords.hasUnpublished(p), "done");
        ClusterFixture.await(
                "the copy here refreshed",
                () -> "from elsewhere".equals(p.getBuildByNumber(1).getDescription()));
    }

    @Test
    void aRetriedPublicationAfterAnEditElsewhereWasAppliedHereRemovesThePlaceholder() throws Exception {
        FreeStyleProject p = project(r, "refreshed");
        String lease = Keys.buildLease(Keys.item(p), 1);
        InMemoryClusterStore.hook = (operation, key) -> {
            if (operation.equals("release") && key.equals(lease)) {
                throw new IOException("the store cannot be reached");
            }
        };
        FreeStyleBuild b;
        try {
            b = r.buildAndAssertSuccess(p);
            ClusterFixture.await("published", () -> record(p, 1) != null);
        } finally {
            InMemoryClusterStore.hook = null;
        }
        String elsewhere = record(p, 1)
                .replace(
                        "<keepLog>false</keepLog>",
                        "<description>from elsewhere</description><keepLog>false</keepLog>");
        store().as("other")
                .write(
                        Keys.buildRecord(Keys.item(p), 1),
                        elsewhere.getBytes(StandardCharsets.UTF_8),
                        PersistenceStore.ANY);
        File dir = b.getRootDir();
        ClusterFixture.await(
                "the copy here to be refreshed (materialized again when next loaded)",
                () -> !new File(dir, "build.xml").exists());
        BuildRecords.complete(b); // the retry, with the build object from before
        assertThat(record(p, 1), containsString("from elsewhere"));
        assertFalse(BuildRecords.hasUnpublished(p), "done");
        assertTrue(store().as("other").leases().acquire(lease) > 0, "its lease released");
        assertEquals("from elsewhere", p.getBuildByNumber(1).getDescription());
    }

    @Test
    void whatIsStoredAboutAnItemThatExistsNowhereIsDeletedAfterAWhile() throws Exception {
        FreeStyleProject p = project(r, "exists");
        r.buildAndAssertSuccess(p);
        InMemoryClusterStore other = store().as("other");
        // left by a deletion that failed before marking it
        String orphan = UUID.randomUUID().toString();
        other.write(
                Keys.nextBuildNumber(Keys.itemByUuid(orphan)),
                "4\n".getBytes(StandardCharsets.UTF_8),
                PersistenceStore.ABSENT);
        ClusterFixture.blobs().put("items/" + orphan + "/builds/3/files.tgz", new ByteArrayInputStream(new byte[1]), 1);
        // an item which exists, stored, but is not loaded here (it cannot be: a type this controller does not have)
        String unloaded = UUID.randomUUID().toString();
        other.write(
                "jobs/unloadable/config.xml",
                ("<?xml version='1.1' encoding='UTF-8'?>\n<no.such.Project><itemUuid>" + unloaded
                                + "</itemUuid></no.such.Project>")
                        .getBytes(StandardCharsets.UTF_8),
                PersistenceStore.ABSENT);
        other.write(
                Keys.nextBuildNumber(Keys.itemByUuid(unloaded)),
                "2\n".getBytes(StandardCharsets.UTF_8),
                PersistenceStore.ABSENT);
        ClusterFixture.awaitApplied();
        long now = System.currentTimeMillis();
        BuildRecords.sweepUnknown(store(), now);
        assertNotNull(store().read(Keys.nextBuildNumber(Keys.itemByUuid(orphan))), "not at first sight");
        BuildRecords.sweepUnknown(store(), now + BuildRecords.UNKNOWN_GRACE_MILLIS / 2);
        assertNotNull(store().read(Keys.nextBuildNumber(Keys.itemByUuid(orphan))), "not within the grace period");
        BuildRecords.sweepUnknown(store(), now + BuildRecords.UNKNOWN_GRACE_MILLIS);
        assertNull(store().read(Keys.nextBuildNumber(Keys.itemByUuid(orphan))), "deleted once it has passed");
        assertThat(ClusterFixture.blobs().list("items/" + orphan), empty());
        assertNotNull(
                store().read(Keys.nextBuildNumber(Keys.itemByUuid(unloaded))),
                "an item stored, though not loaded here");
        assertNotNull(record(p, 1), "an item loaded here");
    }

    /** Writes a file of its own and a link to one outside. */
    public static final class Links extends TestBuilder {
        private final String secret;

        Links(String secret) {
            this.secret = secret;
        }

        @Override
        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
                throws IOException {
            Path root = build.getRootDir().toPath();
            Files.writeString(root.resolve("own.txt"), "the build's own");
            Files.createSymbolicLink(root.resolve("link.txt"), Path.of(secret));
            return true;
        }
    }

    private static void assertNotEquals(Object a, Object b) {
        org.junit.jupiter.api.Assertions.assertNotEquals(a, b);
    }

    @SuppressWarnings("unused")
    private static Set<Integer> numbers(FreeStyleProject p) throws IOException {
        return BuildRecords.listStoredNumbers(p);
    }
}
