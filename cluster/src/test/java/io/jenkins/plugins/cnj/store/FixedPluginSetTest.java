package io.jenkins.plugins.cnj.store;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FixedPluginSetTest {

    @Test
    void pluginChangesAreRecognized() {
        assertTrue(FixedPluginSet.isChange("POST", "/manage/pluginManager/install"));
        assertTrue(FixedPluginSet.isChange("POST", "/pluginManager/installPlugins"));
        assertTrue(FixedPluginSet.isChange("POST", "/manage/pluginManager/uploadPlugin"));
        assertTrue(FixedPluginSet.isChange("POST", "/pluginManager/installNecessaryPlugins"));
        assertTrue(FixedPluginSet.isChange("POST", "/manage/pluginManager/plugin/mock-slave/doUninstall"));
        assertTrue(FixedPluginSet.isChange("POST", "/pluginManager/plugin/git/makeDisabled/"));
        assertTrue(FixedPluginSet.isChange("POST", "/updateCenter/upgrade"));
    }

    @Test
    void everythingElsePasses() {
        assertFalse(FixedPluginSet.isChange("GET", "/manage/pluginManager/install"), "viewing");
        assertFalse(FixedPluginSet.isChange("GET", "/manage/pluginManager/available"));
        assertFalse(FixedPluginSet.isChange("POST", "/manage/pluginManager/checkUpdatesServer"));
        assertFalse(FixedPluginSet.isChange("POST", "/updateCenter/safeRestart"));
        assertFalse(FixedPluginSet.isChange("POST", "/job/install"));
    }
}
