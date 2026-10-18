package io.jenkins.plugins.cluster.config;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleProject;
import io.jenkins.plugins.cluster.ClusterFixture;
import java.nio.charset.StandardCharsets;
import jenkins.persistence.PersistenceStore;
import jenkins.persistence.StoredConfigReconciler.Reason;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** Conflicting saves merge or are refused; items changed elsewhere reload field by field. */
@WithJenkins
class ClusterReconcilerTest {

    @BeforeAll
    static void cluster() {
        ClusterFixture.activate();
    }

    @AfterAll
    static void noCluster() {
        ClusterFixture.deactivate();
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void disjointChangesMergeAndTheSamePartChangedOnBothSidesIsRefused(JenkinsRule r) {
        ClusterReconciler reconciler = new ClusterReconciler();
        String base = "<project><description>d</description><disabled>false</disabled></project>";
        String mine = "<project><description>mine</description><disabled>false</disabled></project>";
        String theirs = "<project><description>d</description><disabled>true</disabled></project>";
        long merged = ConflictCounts.merged();
        byte[] result = reconciler.merge("jobs/p/config.xml", b(base), b(mine), b(theirs));
        assertThat(new String(result, StandardCharsets.UTF_8), containsString("<description>mine</description>"));
        assertThat(new String(result, StandardCharsets.UTF_8), containsString("<disabled>true</disabled>"));
        assertEquals(merged + 1, ConflictCounts.merged());
        long refused = ConflictCounts.refused();
        assertNull(reconciler.merge(
                "jobs/p/config.xml",
                b(base),
                b(mine),
                b("<project><description>theirs</description><disabled>false</disabled></project>")));
        assertEquals(refused + 1, ConflictCounts.refused());
        assertNull(reconciler.merge("jobs/p/nextBuildNumber", b("1"), b("2"), b("3")), "not XML");
    }

    @Test
    void anItemChangedElsewhereIsReloadedFieldByFieldKeepingAPendingLocalChange(JenkinsRule r) throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("p");
        p.setDescription("before"); // saved: in sync with this
        // changed elsewhere: the description
        FreeStyleProject copy = (FreeStyleProject)
                p.getConfigFile().getXStream().fromXML(p.getConfigFile().asString());
        String theirs = p.getConfigFile()
                .asString()
                .replace("<description>before</description>", "<description>elsewhere</description>");
        // and here, not saved yet: another field
        java.lang.reflect.Field disabled = hudson.model.AbstractProject.class.getDeclaredField("disabled");
        disabled.setAccessible(true);
        disabled.setBoolean(p, true);
        assertTrue(new ClusterReconciler()
                .reload(p.getConfigFile(), p, new PersistenceStore.Entry(b(theirs), 99), Reason.CHANGED_ELSEWHERE));
        assertEquals("elsewhere", p.getDescription());
        assertTrue(p.isDisabled(), "the change made here is kept");
        assertFalse(copy.isDisabled());
    }

    @Test
    void theSameFieldChangedOnBothSidesIsLeftToTheDefaultReload(JenkinsRule r) throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("q");
        p.setDescription("before");
        String theirs = p.getConfigFile()
                .asString()
                .replace("<description>before</description>", "<description>elsewhere</description>");
        java.lang.reflect.Field description = hudson.model.AbstractItem.class.getDeclaredField("description");
        description.setAccessible(true);
        description.set(p, "here");
        assertFalse(
                new ClusterReconciler()
                        .reload(
                                p.getConfigFile(),
                                p,
                                new PersistenceStore.Entry(b(theirs), 99),
                                Reason.CHANGED_ELSEWHERE),
                "the stored version wins: core reloads it");
    }

    @Test
    void afterASaveHereTheStoredVersionWinsOutrightWhicheverThreadReloads(JenkinsRule r) throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("after-save");
        p.setDescription("before"); // saved: in sync with this
        String theirs = p.getConfigFile().asString().replace("<disabled>false</disabled>", "<disabled>true</disabled>");
        // what a refused save of this controller left in memory
        java.lang.reflect.Field description = hudson.model.AbstractItem.class.getDeclaredField("description");
        description.setAccessible(true);
        description.set(p, "refused");
        assertTrue(new ClusterReconciler()
                .reload(p.getConfigFile(), p, new PersistenceStore.Entry(b(theirs), 99), Reason.AFTER_SAVE_HERE));
        assertEquals("before", p.getDescription(), "the refused edit is gone");
        assertTrue(p.isDisabled(), "the change made elsewhere is applied");
    }

    @Test
    void aChangeMadeElsewhereKeepsAPendingLocalChangeWhicheverThreadReloads(JenkinsRule r) throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("elsewhere");
        p.setDescription("before"); // saved: in sync with this
        String theirs = p.getConfigFile().asString().replace("<disabled>false</disabled>", "<disabled>true</disabled>");
        java.lang.reflect.Field description = hudson.model.AbstractItem.class.getDeclaredField("description");
        description.setAccessible(true);
        description.set(p, "here, not saved yet");
        java.util.concurrent.atomic.AtomicReference<Object> result =
                new java.util.concurrent.atomic.AtomicReference<>();
        // on the thread core reloads on after a save here: the reason, not the thread, tells what to do
        Thread reload = new Thread(
                () -> {
                    try {
                        result.set(new ClusterReconciler()
                                .reload(
                                        p.getConfigFile(),
                                        p,
                                        new PersistenceStore.Entry(b(theirs), 99),
                                        Reason.CHANGED_ELSEWHERE));
                    } catch (Exception e) {
                        result.set(e);
                    }
                },
                "Persistence store reload");
        reload.start();
        reload.join(10_000);
        assertFalse(reload.isAlive());
        assertEquals(Boolean.TRUE, result.get());
        assertEquals("here, not saved yet", p.getDescription(), "the change made here is kept");
        assertTrue(p.isDisabled(), "the change made elsewhere is applied");
    }

    @Test
    void anItemIsReloadedWithoutHoldingItsLockWhileItWaitsForItsBuildsLock(JenkinsRule r) throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("locks");
        p.setDescription("before"); // saved: in sync with this
        String key = "jobs/locks/config.xml";
        String theirs = new String(ClusterFixture.store().read(key).content(), StandardCharsets.UTF_8)
                .replace("<description>before</description>", "<description>elsewhere</description>");
        ClusterFixture.store().loseEvents(true); // reloaded below, as a change made elsewhere is
        try {
            ClusterFixture.store().as("other").write(key, b(theirs), PersistenceStore.ANY);
        } finally {
            ClusterFixture.store().loseEvents(false);
        }
        PersistenceStore.Entry entry = ClusterFixture.store().read(key);
        java.util.concurrent.atomic.AtomicReference<Exception> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
        // through core, which calls the reconciler under the item's lock and then the item's onLoad without it
        Thread reload = new Thread(
                () -> {
                    try (hudson.security.ACLContext ignored = hudson.security.ACL.as2(hudson.security.ACL.SYSTEM2)) {
                        jenkins.persistence.PersistenceStores.applyingRemoteChange(() -> {
                            jenkins.persistence.StoredReloads.reload(p.getConfigFile(), p, entry);
                            return null;
                        });
                    } catch (Exception e) {
                        failure.set(e);
                    }
                },
                "reload");
        java.lang.management.ThreadMXBean threads = java.lang.management.ManagementFactory.getThreadMXBean();
        // a new build holds the job's build lock, and asks for the job's lock to be numbered
        synchronized (p.getLazyBuildMixIn()) {
            reload.start();
            java.lang.management.ThreadInfo info;
            long deadline = System.nanoTime() + 10_000_000_000L;
            do {
                assertTrue(System.nanoTime() < deadline, "the reload never waited for the build lock");
                Thread.sleep(10);
                info = threads.getThreadInfo(new long[] {reload.getId()}, true, false)[0];
            } while (info.getThreadState() != Thread.State.BLOCKED
                    || info.getLockInfo().getIdentityHashCode() != System.identityHashCode(p.getLazyBuildMixIn()));
            for (java.lang.management.MonitorInfo held : info.getLockedMonitors()) {
                assertTrue(
                        held.getIdentityHashCode() != System.identityHashCode(p),
                        "the reload holds the job's lock while it waits for the build lock: a new build deadlocks");
            }
        }
        reload.join(10_000);
        assertFalse(reload.isAlive());
        assertNull(failure.get());
        assertEquals("elsewhere", p.getDescription());
    }

    @Test
    void aRefusedEditDoesNotStayInMemoryToBeSavedLater(JenkinsRule r) throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("refused");
        // too large for core to keep what it was loaded from: a conflicting save cannot be merged, it is refused
        String big = "x".repeat(1024 * 1024 + 1);
        p.setDescription(big);
        String key = "jobs/refused/config.xml";
        // changed elsewhere (another field), its event not here yet
        String theirs = new String(ClusterFixture.store().read(key).content(), StandardCharsets.UTF_8)
                .replace("<disabled>false</disabled>", "<disabled>true</disabled>");
        ClusterFixture.store().loseEvents(true);
        try {
            ClusterFixture.store().as("other").write(key, b(theirs), PersistenceStore.ANY);
        } finally {
            ClusterFixture.store().loseEvents(false);
        }
        assertThrows(java.io.IOException.class, () -> p.setDescription("refused"), "the save is refused");
        ClusterFixture.await("the reload after the refused save", jenkins.persistence.StoredReloads::isIdle);
        assertTrue(p.isDisabled(), "the change made elsewhere is applied");
        assertEquals(big, p.getDescription(), "and the refused edit is gone, not kept to be saved by the next save");
    }

    @Test
    void whatAnItemWasInSyncWithIsForgottenWithTheItem(JenkinsRule r) throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("forgotten");
        p.setDescription("saved"); // in sync with this
        String key = "jobs/forgotten/config.xml";
        assertTrue(ClusterReconciler.isSynced(key));
        assertFalse(
                new java.io.File(p.getRootDir(), "config.xml").exists(),
                "no local copy of the stored configuration is made for it");
        p.delete();
        assertFalse(ClusterReconciler.isSynced(key), "nothing is kept for an item that no longer exists");
    }
}
