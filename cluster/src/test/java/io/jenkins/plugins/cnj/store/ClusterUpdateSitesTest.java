package io.jenkins.plugins.cnj.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class ClusterUpdateSitesTest {

    private static final ClusterUpdateSites.Shared AT_100 = new ClusterUpdateSites.Shared(100, "abc", "cluster-updates/default.json/abc", null, "cnj-0");

    @Test
    void newerWinsEitherWay() {
        assertEquals(ClusterUpdateSites.Direction.PUBLISH, ClusterUpdateSites.direction(50, null), "nothing shared yet");
        assertEquals(ClusterUpdateSites.Direction.NONE, ClusterUpdateSites.direction(0, null), "nothing anywhere");
        assertEquals(ClusterUpdateSites.Direction.TAKE, ClusterUpdateSites.direction(0, AT_100), "a fresh home");
        assertEquals(ClusterUpdateSites.Direction.TAKE, ClusterUpdateSites.direction(99, AT_100), "checked elsewhere since");
        assertEquals(ClusterUpdateSites.Direction.NONE, ClusterUpdateSites.direction(100, AT_100), "taken already (same modification time)");
        assertEquals(ClusterUpdateSites.Direction.PUBLISH, ClusterUpdateSites.direction(101, AT_100), "checked here since");
    }

    @Test
    void indexRoundTrips() {
        Map<String, ClusterUpdateSites.Shared> files = new TreeMap<>();
        files.put("default.json", AT_100);
        files.put("hudson.tasks.Maven.MavenInstaller", new ClusterUpdateSites.Shared(7, "def", "b2", "b1", "cnj-1"));
        assertEquals(files, ClusterUpdateSites.parse(ClusterUpdateSites.toBytes(files)));
        assertTrue(ClusterUpdateSites.parse(null).isEmpty());
    }

    @Test
    void onlyDataFilesAreShared() {
        assertTrue(ClusterUpdateSites.shareable("default.json"));
        assertTrue(ClusterUpdateSites.shareable("hudson.tasks.Maven.MavenInstaller"));
        assertFalse(ClusterUpdateSites.shareable(".default.json.cluster.tmp"));
        assertFalse(ClusterUpdateSites.shareable("default.json.bak"));
        assertFalse(ClusterUpdateSites.shareable("atomic123.tmp"));
    }

    @Test
    void requestsThatSeeOrRefreshTheData() {
        assertTrue(ClusterUpdateSites.CHECK.matcher("/manage/pluginManager/checkUpdatesServer").matches());
        assertTrue(ClusterUpdateSites.CHECK.matcher("/pluginManager/checkUpdatesServer/").matches());
        assertTrue(ClusterUpdateSites.PAGES.matcher("/manage/pluginManager/available").matches());
        assertTrue(ClusterUpdateSites.PAGES.matcher("/manage/pluginManager/pluginsSearch").matches());
        assertTrue(ClusterUpdateSites.PAGES.matcher("/updateCenter/api/json").matches());
        assertFalse(ClusterUpdateSites.PAGES.matcher("/job/pluginManager").matches());
        assertFalse(ClusterUpdateSites.PAGES.matcher("/manage/pluginManagerX").matches());
    }
}
