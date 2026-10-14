package io.jenkins.plugins.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.kohsuke.stapler.bind.BoundObjectTable;

class BoundRequestForwarderTest {

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
