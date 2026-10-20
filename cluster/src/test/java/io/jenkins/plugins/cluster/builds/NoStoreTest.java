package io.jenkins.plugins.cluster.builds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.ExtensionList;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.slaves.DumbSlave;
import hudson.slaves.JNLPLauncher;
import io.jenkins.plugins.cluster.agents.AgentsElsewhere;
import io.jenkins.plugins.cluster.spi.ClusterStore;
import java.io.File;
import java.util.List;
import jenkins.model.ExternalBuilds;
import org.jenkins.plugins.lockableresources.LockCoordinator;
import org.jenkins.plugins.lockableresources.LockableResource;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.jenkinsci.plugins.workflow.support.steps.build.BuildTriggerRelay;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** Installed without a cluster store, the plugin leaves builds as they are upstream. */
@WithJenkins
class NoStoreTest {

    @Test
    void buildsAreLocalOnly(JenkinsRule r) throws Exception {
        assertNull(ClusterStore.get());
        FreeStyleProject p = r.createFreeStyleProject("p");
        FreeStyleBuild b = r.buildAndAssertSuccess(p);
        b.setDescription("edited");
        assertFalse(BuildRecords.isShared(p));
        assertEquals(List.of(), ExternalBuilds.get().running(p));
        assertFalse(ExternalBuilds.get().isBuilding(p));
        assertTrue(p.getActions(RunningElsewhereAction.class).isEmpty(), "no action, so no field in the REST API");
        assertEquals(2, p.getNextBuildNumber());
        r.jenkins.reload();
        p = r.jenkins.getItemByFullName("p", FreeStyleProject.class);
        assertEquals("edited", p.getBuildByNumber(1).getDescription());
        assertFalse(
                new File(p.getBuildByNumber(1).getRootDir(), BuildRecords.Manifest.NAME).exists(),
                "nothing of the plugin's in it");
        assertFalse(new File(p.getBuildByNumber(1).getRootDir(), BuildRecords.RecordVersion.NAME).exists());
    }

    @Test
    void locksAreLocalOnly(JenkinsRule r) {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        lrm.addResource(new LockableResource("res"), true);
        LockableResource res = lrm.fromName("res");
        assertTrue(lrm.reserve(List.of(res), "bob", null));
        assertFalse(LockCoordinator.get().isHeldElsewhere(res));
        lrm.unreserve(List.of(res));
        assertFalse(res.isReserved());
    }

    @Test
    void buildStepEventsAreNotRelayed(JenkinsRule r) throws Exception {
        FreeStyleBuild b = r.buildAndAssertSuccess(r.createFreeStyleProject("p"));
        assertNull(BuildTriggerRelay.get().returnAddress(b, "3"), "a build step's events stay on this controller");
    }

    @Test
    void agentsAreLaunchedWithoutLease(JenkinsRule r) throws Exception {
        DumbSlave agent = r.createOnlineSlave();
        assertTrue(agent.toComputer().isOnline());
    }

    @Test
    void anAgentNotConnectedIsShownOffline(JenkinsRule r) throws Exception {
        DumbSlave agent = new DumbSlave("inbound", r.createTmpDir().getPath(), new JNLPLauncher());
        r.jenkins.addNode(agent);
        assertNull(agent.toComputer().getConnectedElsewhere());
        assertNull(ExtensionList.lookupSingleton(AgentsElsewhere.class).connectedElsewhere(agent.toComputer()));
    }
}
