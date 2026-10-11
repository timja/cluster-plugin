package io.jenkins.plugins.cnj.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ClusterPluginSetTest {

    @Test
    void pluginChangesAreRecognized() {
        assertEquals(new ClusterPluginSet.Change("install", null), ClusterPluginSet.changeOf("POST", "/manage/pluginManager/install"));
        assertEquals("install", ClusterPluginSet.changeOf("POST", "/pluginManager/installPlugins").kind());
        assertEquals("install", ClusterPluginSet.changeOf("POST", "/manage/pluginManager/uploadPlugin").kind());
        assertEquals(new ClusterPluginSet.Change("doUninstall", "mock-slave"),
                ClusterPluginSet.changeOf("POST", "/manage/pluginManager/plugin/mock-slave/doUninstall"));
        assertEquals(new ClusterPluginSet.Change("makeDisabled", "git"), ClusterPluginSet.changeOf("POST", "/pluginManager/plugin/git/makeDisabled/"));
        assertEquals("downgrade", ClusterPluginSet.changeOf("POST", "/updateCenter/downgrade").kind());
        assertEquals("restart", ClusterPluginSet.changeOf("POST", "/manage/pluginManager/updates/safeRestart").kind());
        assertEquals("restart", ClusterPluginSet.changeOf("POST", "/updateCenter/cancelRestart").kind());
    }

    @Test
    void everythingElsePasses() {
        assertNull(ClusterPluginSet.changeOf("GET", "/manage/pluginManager/install"), "viewing");
        assertNull(ClusterPluginSet.changeOf("GET", "/manage/pluginManager/available"));
        assertNull(ClusterPluginSet.changeOf("POST", "/manage/pluginManager/checkUpdatesServer"));
        assertNull(ClusterPluginSet.changeOf("POST", "/safeRestart"), "Manage Jenkins > restart: one controller, on purpose");
        assertNull(ClusterPluginSet.changeOf("POST", "/job/install"));
    }

    @Test
    void whatEachModeRefuses() {
        ClusterPluginSet.Change install = new ClusterPluginSet.Change("install", null);
        ClusterPluginSet.Change uninstall = new ClusterPluginSet.Change("doUninstall", "git");
        assertNull(ClusterPluginSet.refusal(ClusterPluginSet.Mode.cluster, install, true));
        assertNull(ClusterPluginSet.refusal(ClusterPluginSet.Mode.cluster, uninstall, true));
        assertEquals(ClusterPluginSet.DOWNGRADE, ClusterPluginSet.refusal(ClusterPluginSet.Mode.cluster, new ClusterPluginSet.Change("downgrade", null), true));
        assertEquals(ClusterPluginSet.CORE_UPGRADE, ClusterPluginSet.refusal(ClusterPluginSet.Mode.cluster, new ClusterPluginSet.Change("upgrade", null), true));
        assertEquals(ClusterPluginSet.PROTECTED_CHANGE, ClusterPluginSet.refusal(ClusterPluginSet.Mode.cluster,
                new ClusterPluginSet.Change("doUninstall", ClusterPlugins.PROTECTED), true));
        assertEquals(ClusterPluginSet.FIXED, ClusterPluginSet.refusal(ClusterPluginSet.Mode.cluster, install, false), "no blob storage: cannot share");
        ClusterPluginSet.Change restart = new ClusterPluginSet.Change("restart", null);
        assertEquals(ClusterPluginSet.RESTART, ClusterPluginSet.refusal(ClusterPluginSet.Mode.cluster, restart, true), "the cluster restarts by itself");
        assertNull(ClusterPluginSet.refusal(ClusterPluginSet.Mode.fixed, restart, true), "nothing to roll out: a restart is a restart");
        assertEquals(ClusterPluginSet.PROTECTED_CHANGE, ClusterPluginSet.refusal(ClusterPluginSet.Mode.cluster,
                new ClusterPluginSet.Change("install", ClusterPlugins.PROTECTED), true), "e.g. the CLI installing the store plugin");
        assertEquals(ClusterPluginSet.FIXED, ClusterPluginSet.refusal(ClusterPluginSet.Mode.fixed, install, true));
        assertNull(ClusterPluginSet.refusal(ClusterPluginSet.Mode.local, uninstall, true));
    }
}
