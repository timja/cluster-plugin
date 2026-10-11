package io.jenkins.plugins.cnj.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UpdateCenterRoutingTest {

    private static PostgresPersistenceStore.Member member(String name, boolean alive, MemberStatus.Info info) {
        return new PostgresPersistenceStore.Member("o-" + name, name, "http://" + name + ":8080/", 0, 0, alive, "v", 1, null,
                info == null ? null : info.toJson());
    }

    @Test
    void progressPagesAreRecognized() {
        for (String p : List.of("/manage/pluginManager/updates/", "/pluginManager/updates", "/manage/pluginManager/updates/body",
                "/updateCenter/", "/updateCenter/body", "/updateCenter/installStatus", "/updateCenter/incompleteInstallStatus",
                "/updateCenter/api/json", "/manage/pluginManager/updates/api/json")) {
            assertTrue(UpdateCenterRouting.PROGRESS.matcher(p).matches(), p);
        }
        for (String p : List.of("/manage/pluginManager/available", "/manage/pluginManager/install", "/updateCenter/connectionStatus",
                "/updateCenter/safeRestart", "/job/updateCenter/", "/manage/pluginManager/updatesX")) {
            assertFalse(UpdateCenterRouting.PROGRESS.matcher(p).matches(), p);
        }
    }

    @Test
    void theBrowsersControllerFirstThenTheOneInstalling() {
        MemberStatus.Info idle = new MemberStatus.Info(0, 2, Map.of(), 0, 0);
        MemberStatus.Info installing = new MemberStatus.Info(0, 2, Map.of(), 2, 1000);
        MemberStatus.Info installingLater = new MemberStatus.Info(0, 2, Map.of(), 1, 2000);
        List<PostgresPersistenceStore.Member> members = List.of(member("a", true, idle), member("b", true, installing), member("c", true, installingLater));
        assertEquals("a", UpdateCenterRouting.target(members, "a").name(), "the cookie wins, even before its heartbeat shows the install");
        assertEquals("c", UpdateCenterRouting.target(members, null).name(), "no cookie: the most recent install running");
        assertEquals("c", UpdateCenterRouting.target(members, "gone").name(), "a controller not alive any more");
        assertEquals("c", UpdateCenterRouting.target(List.of(member("a", false, idle), member("c", true, installingLater)), "a").name());
        assertNull(UpdateCenterRouting.target(List.of(member("a", true, idle), member("b", true, null)), null), "nothing running: answer here");
    }

    @Test
    void infoRoundTripsAndToleratesOlderMembers() {
        MemberStatus.Info info = new MemberStatus.Info(3, 6, Map.of("mock-1", new int[] {1, 2}), 1, 42);
        MemberStatus.Info back = MemberStatus.Info.parse(info.toJson());
        assertEquals(3, back.busy());
        assertEquals(6, back.total());
        assertArrayEquals(new int[] {1, 2}, back.agents().get("mock-1"));
        assertEquals(1, back.installing());
        assertEquals(42, back.installAt());
        assertEquals(MemberStatus.Info.NONE, MemberStatus.Info.parse(null));
        assertEquals(MemberStatus.Info.NONE, MemberStatus.Info.parse("not json"));
    }
}
