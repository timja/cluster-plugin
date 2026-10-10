package io.jenkins.plugins.cnj.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.kohsuke.stapler.bind.BoundObjectTable;

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

    @Test
    void ownerOfBoundObjects() {
        String old = BoundObjectTable.ID_PREFIX;
        try {
            BoundObjectTable.ID_PREFIX = "jenkins-b";
            String uuid = "0f8fad5b-d9cb-469f-a165-70867728950e";
            assertEquals("jenkins-a", BoundRequestForwarder.ownerOf("/$stapler/bound/jenkins-a-" + uuid + "/render"));
            assertNull(BoundRequestForwarder.ownerOf("/$stapler/bound/jenkins-b-" + uuid + "/render"), "ours");
            assertNull(BoundRequestForwarder.ownerOf("/$stapler/bound/" + uuid + "/render"), "not prefixed");
            assertNull(BoundRequestForwarder.ownerOf("/$stapler/bound/script/foo"), "script endpoint");
            assertEquals(
                    "jenkins-a",
                    BoundRequestForwarder.ownerOf("/$stapler/bound/script/$stapler/bound/jenkins-a-" + uuid),
                    "the script of a bound object");
            assertNull(BoundRequestForwarder.ownerOf("/job/x/"), "not bound");
        } finally {
            BoundObjectTable.ID_PREFIX = old;
        }
    }
}
