package io.jenkins.plugins.cluster.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StaticStateOwnershipTest {

    @Test
    void staticStateIsOwnedExceptPerControllerFiles() {
        for (String shared : new String[] {
            "config.xml",
            "credentials.xml",
            "jenkins.model.JenkinsLocationConfiguration.xml",
            "users/alice_0123abcd/config.xml",
            "nodes/agent1/config.xml",
            "jobs/p/config.xml"
        }) {
            assertTrue(PostgresPersistenceStoreProvider.owns(shared), shared);
        }
        for (String local : new String[] {
            "queue.xml",
            "nodeMonitors.xml",
            "hudson.model.UpdateCenter.xml",
            "jenkins.install.InstallUtil.installingPlugins.xml",
            "org.jenkinsci.plugins.workflow.flow.FlowExecutionList.xml",
            "org.jenkins.plugins.lockableresources.LockableResourcesManager.xml",
            "users/alice_0123abcd/apiTokenStats.xml",
            "secrets/master.key",
            "jobs/p/builds/1/build.xml"
        }) {
            assertFalse(PostgresPersistenceStoreProvider.owns(local), local);
        }
    }

}
