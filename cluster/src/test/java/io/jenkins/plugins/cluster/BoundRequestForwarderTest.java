package io.jenkins.plugins.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class BoundRequestForwarderTest {

    /** As Stapler's {@code BoundObjectTable.serverIdOf}: {@code <server id>-<UUID>}, parsed from the right. */
    private static final Function<String, String> SERVER_ID_OF = id -> {
        int dash = id.length() - 37;
        if (dash <= 0 || id.charAt(dash) != '-') {
            return null;
        }
        try {
            UUID.fromString(id.substring(dash + 1));
        } catch (IllegalArgumentException e) {
            return null;
        }
        return id.substring(0, dash);
    };

    @Test
    void ownerOfBoundObjects() {
        String uuid = "0f8fad5b-d9cb-469f-a165-70867728950e";
        assertEquals(
                "jenkins-a",
                BoundRequestForwarder.ownerOf(
                        "/$stapler/bound/jenkins-a-" + uuid + "/render", "jenkins-b", SERVER_ID_OF));
        assertNull(
                BoundRequestForwarder.ownerOf(
                        "/$stapler/bound/jenkins-b-" + uuid + "/render", "jenkins-b", SERVER_ID_OF),
                "ours");
        assertNull(
                BoundRequestForwarder.ownerOf("/$stapler/bound/" + uuid + "/render", "jenkins-b", SERVER_ID_OF),
                "not prefixed");
        assertNull(
                BoundRequestForwarder.ownerOf("/$stapler/bound/script/foo", "jenkins-b", SERVER_ID_OF),
                "script endpoint");
        assertEquals(
                "jenkins-a",
                BoundRequestForwarder.ownerOf(
                        "/$stapler/bound/script/$stapler/bound/jenkins-a-" + uuid, "jenkins-b", SERVER_ID_OF),
                "the script of a bound object");
        assertNull(BoundRequestForwarder.ownerOf("/job/x/", "jenkins-b", SERVER_ID_OF), "not bound");
    }

    @Test
    void withoutStaplerSupportNothingIsForwarded() {
        // core's Stapler has neither BoundObjectTable.getIdPrefix nor serverIdOf yet: ids are never prefixed
        String uuid = "0f8fad5b-d9cb-469f-a165-70867728950e";
        assertNull(BoundRequestForwarder.ownerOf("/$stapler/bound/jenkins-a-" + uuid + "/render", "", id -> null));
    }
}
