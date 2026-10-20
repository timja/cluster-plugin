package io.jenkins.plugins.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class RemoteLockForwarderTest {

    @Test
    void callsAboutALockGoToTheControllerNamedInItsId() {
        String api = "/lockable-resources/remote/v1/";
        assertEquals("cnj-1", RemoteLockForwarder.ownerOf(api + "acquire/cnj-1~6f1c", "cnj-0"));
        assertEquals("cnj-1", RemoteLockForwarder.ownerOf(api + "lease/cnj-1~6f1c/heartbeat", "cnj-0"));
        assertEquals("cnj-1", RemoteLockForwarder.ownerOf(api + "lease/cnj-1~6f1c/release", "cnj-0"));
        assertNull(RemoteLockForwarder.ownerOf(api + "acquire/cnj-0~6f1c", "cnj-0"), "this controller's");
        assertNull(RemoteLockForwarder.ownerOf(api + "acquire", "cnj-0"), "a new lock: any controller takes it");
        assertNull(RemoteLockForwarder.ownerOf(api + "acquire/6f1c", "cnj-0"), "no controller named");
        assertNull(RemoteLockForwarder.ownerOf(api + "acquire/a%2Fb~6f1c", "cnj-0"), "not a controller name");
        assertNull(RemoteLockForwarder.ownerOf(api + "resources", "cnj-0"));
        assertNull(RemoteLockForwarder.ownerOf("/job/cnj-1~x/acquire/cnj-1~6f1c", "cnj-0"));
    }
}
