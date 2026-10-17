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
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import io.jenkins.plugins.cluster.ClusterFixture;
import io.jenkins.plugins.cluster.spi.InMemoryClusterStore;
import io.jenkins.plugins.cluster.spi.Keys;
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
        StoredPermalinks.forgetForTests();
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
        other.write(key, elsewhere.getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
        // and here, by a copy which has not applied that yet: refused, and the copy refreshed
        b.setDescription("from here");
        assertThat(record(p, 1), containsString("from elsewhere"));
        ClusterFixture.await(
                "the copy here refreshed",
                () -> "from elsewhere".equals(p.getBuildByNumber(1).getDescription()));
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
