package io.jenkins.plugins.cluster.builds;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import hudson.ExtensionList;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import io.jenkins.plugins.cluster.ClusterFixture;
import io.jenkins.plugins.cluster.spi.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import jenkins.model.PeepholePermalink;
import jenkins.persistence.PersistenceStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** The permalinks of shared builds are kept in the store, by the job's identity. */
@WithJenkins
class StoredPermalinksTest {

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

    private static String stored(FreeStyleProject p) {
        PersistenceStore.Entry e = ClusterFixture.store().read(Keys.permalinks(Keys.item(p)));
        return e == null ? "" : new String(e.content(), StandardCharsets.UTF_8);
    }

    private static PeepholePermalink.Cache cache() {
        return ExtensionList.lookupFirst(PeepholePermalink.Cache.class);
    }

    @Test
    void permalinksAreSharedAndResolvedWithoutGoingThroughEveryBuild() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("p");
        for (int i = 0; i < 5; i++) {
            r.buildAndAssertSuccess(p);
        }
        assertNull(p.getLastFailedBuild());
        assertEquals(5, p.getLastSuccessfulBuild().getNumber());
        assertThat(stored(p), containsString("lastFailedBuild -1"));
        assertThat(stored(p), containsString("lastSuccessfulBuild 5"));

        // as on a controller which did not run them: nothing loaded, nothing cached here
        p._getRuns().purgeCache();
        StoredPermalinks.forgetForTests();
        assertNull(p.getLastFailedBuild(), "known to be none, from the store");
        assertEquals(5, p.getLastSuccessfulBuild().getNumber());
        assertThat(
                "builds loaded to resolve them", p._getRuns().getLoadedBuilds().size(), lessThanOrEqualTo(1));

        // a controller which has not seen #5 yet cannot move a target back
        cache().put(p, "lastSuccessfulBuild", new PeepholePermalink.Cache.Some(4));
        assertThat(stored(p), containsString("lastSuccessfulBuild 5"));

        // deleting #5 does (its record is gone)
        p.getBuildByNumber(5).delete();
        assertEquals(4, p.getLastSuccessfulBuild().getNumber());
        assertThat(stored(p), containsString("lastSuccessfulBuild 4"));

        // a change made elsewhere is read again
        String key = Keys.permalinks(Keys.item(p));
        ClusterFixture.store()
                .as("other")
                .write(
                        key,
                        "lastSuccessfulBuild 3\n".getBytes(StandardCharsets.UTF_8),
                        ClusterFixture.store().read(key).version());
        ClusterFixture.awaitApplied();
        assertEquals(new PeepholePermalink.Cache.Some(3), cache().get(p, "lastSuccessfulBuild"));
        assertEquals(
                PeepholePermalink.Cache.UNKNOWN,
                cache().get(p, "lastFailedBuild"),
                "the other controller's cache knew nothing else");
    }

    @Test
    void aPermalinkResolvedOnceItsJobIsDeletedIsNotStoredAgain() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("deleted");
        r.buildAndAssertSuccess(p);
        String key = Keys.permalinks(Keys.item(p));
        assertThat(stored(p), containsString("lastSuccessfulBuild 1"));
        p.delete();
        ClusterFixture.await(
                "removed with the job's builds", () -> ClusterFixture.store().read(key) == null);
        cache().put(p, "lastFailedBuild", PeepholePermalink.Cache.NONE); // a resolution finishing after the deletion
        assertNull(ClusterFixture.store().read(key), "not brought back");
    }

    @Test
    void aTargetCompletedElsewhereButNotPublishedYetIsKept() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("completing");
        r.buildAndAssertSuccess(p);
        // #2 completed on another controller, which set the permalink; its record is not published yet, and its
        // placeholder is still there
        PersistenceStore other = ClusterFixture.store().as("other");
        String prefix = Keys.item(p);
        RunningBuilds.Placeholder ph = new RunningBuilds.Placeholder(
                prefix, 2, "other", "c", "#2", 1700000000000L, 0, Map.of(), PersistenceStore.ABSENT);
        other.write(ph.getKey(), ph.toBytes(), PersistenceStore.ABSENT);
        String key = Keys.permalinks(prefix);
        other.write(
                key,
                "lastSuccessfulBuild 2\n".getBytes(StandardCharsets.UTF_8),
                ClusterFixture.store().read(key).version());
        ClusterFixture.awaitApplied();
        // this controller cannot load #2 yet, so resolves the permalink to #1
        cache().put(p, "lastSuccessfulBuild", new PeepholePermalink.Cache.Some(1));
        assertThat("not moved back", stored(p), containsString("lastSuccessfulBuild 2"));
    }

    @Test
    void deletingTheBuildAPermalinkNamesMovesItBack() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("moved-back");
        r.buildAndAssertSuccess(p);
        p.getBuildersList().add(new FailureBuilder());
        r.buildAndAssertStatus(Result.FAILURE, p);
        assertThat(stored(p), containsString("lastFailedBuild 2"));
        p.getBuildByNumber(2).delete();
        assertThat("whichever of the deletion's listeners runs first", stored(p), containsString("lastFailedBuild -1"));
        assertNull(p.getLastFailedBuild());
    }
}
