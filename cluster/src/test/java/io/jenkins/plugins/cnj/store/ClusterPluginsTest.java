package io.jenkins.plugins.cnj.store;

import static io.jenkins.plugins.cnj.store.ClusterPlugins.Action.DELETE;
import static io.jenkins.plugins.cnj.store.ClusterPlugins.Action.DISABLE;
import static io.jenkins.plugins.cnj.store.ClusterPlugins.Action.ENABLE;
import static io.jenkins.plugins.cnj.store.ClusterPlugins.Action.LOAD;
import static io.jenkins.plugins.cnj.store.ClusterPlugins.Action.NONE;
import static io.jenkins.plugins.cnj.store.ClusterPlugins.Action.REPLACE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.cnj.store.ClusterPlugins.Entry;
import io.jenkins.plugins.cnj.store.ClusterPlugins.Overlay;
import io.jenkins.plugins.cnj.store.ClusterPlugins.State;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClusterPluginsTest {

    private static Entry installed(String version) {
        return new Entry(State.installed, version, "abc", "cluster-plugins/p/abc.jpi", "cnj-0", 1);
    }

    private static Entry state(State s) {
        return new Entry(s, null, null, null, "cnj-0", 1);
    }

    @Test
    void installs() {
        assertEquals(LOAD, ClusterPlugins.decide(installed("2.0"), null, true), "missing here");
        assertEquals(REPLACE, ClusterPlugins.decide(installed("2.0"), "1.5", true), "older here");
        assertEquals(NONE, ClusterPlugins.decide(installed("2.0"), "2.0", true));
        assertEquals(NONE, ClusterPlugins.decide(installed("2.0"), "2.1", true), "never downgrades: a newer image wins");
        assertEquals(ENABLE, ClusterPlugins.decide(installed("2.0"), "2.0", false), "enabled in the cluster");
        assertEquals(ENABLE, ClusterPlugins.decide(state(State.installed), "3.0", false), "enabling an image plugin");
        assertEquals(NONE, ClusterPlugins.decide(state(State.installed), null, true), "no archive to install");
    }

    @Test
    void removals() {
        assertEquals(DELETE, ClusterPlugins.decide(state(State.uninstalled), "1.0", true));
        assertEquals(NONE, ClusterPlugins.decide(state(State.uninstalled), null, true));
        assertEquals(DISABLE, ClusterPlugins.decide(state(State.disabled), "1.0", true));
        assertEquals(NONE, ClusterPlugins.decide(state(State.disabled), "1.0", false));
        assertEquals(NONE, ClusterPlugins.decide(state(State.disabled), null, true));
    }

    @Test
    void restartsOnlyWhenWhatRunsDiffersFromTheFiles() {
        assertFalse(ClusterPlugins.needsRestart(installed("2.0"), true, "2.0", "2.0", true), "loaded live");
        assertTrue(ClusterPlugins.needsRestart(installed("2.0"), true, "1.5", "2.0", true), "upgraded on disk");
        assertTrue(ClusterPlugins.needsRestart(installed("2.0"), false, null, "2.0", true), "could not load live");
        assertTrue(ClusterPlugins.needsRestart(state(State.uninstalled), true, "1.0", null, true), "still loaded");
        assertFalse(ClusterPlugins.needsRestart(state(State.uninstalled), false, null, null, true));
        assertTrue(ClusterPlugins.needsRestart(state(State.disabled), true, "1.0", "1.0", false));
        assertFalse(ClusterPlugins.needsRestart(state(State.disabled), false, null, "1.0", false));
        assertTrue(ClusterPlugins.needsRestart(state(State.installed), false, null, "1.0", true), "enabled, not yet running");
    }

    @Test
    void overlayKeepsOrderAndCountsGenerations() {
        Overlay o = Overlay.EMPTY.with("dep", installed("1.0")).with("top", installed("2.0")).with("dep", installed("1.1"));
        assertEquals(3, o.generation());
        assertEquals(List.of("dep", "top"), List.copyOf(o.plugins().keySet()), "dependencies first, also after an upgrade");
        Overlay parsed = Overlay.parse(o.toBytes());
        assertEquals(o, parsed);
        assertEquals(Overlay.EMPTY, Overlay.parse(null));
        assertEquals(state(State.uninstalled), Overlay.parse(Overlay.EMPTY.with("x", state(State.uninstalled)).toBytes()).plugins().get("x"));
    }

    @Test
    void restartLockExpiresOnlyWithoutItsHolder() {
        long tenMinutes = ClusterPlugins.LOCK_TIMEOUT_MILLIS;
        assertFalse(ClusterPlugins.lockExpired(0, tenMinutes - 1, false));
        assertTrue(ClusterPlugins.lockExpired(0, tenMinutes + 1, false), "holder gone");
        assertFalse(ClusterPlugins.lockExpired(0, tenMinutes * 6, true), "holder still quieting down for a long build");
    }

    @Test
    void statusInWords() {
        assertEquals("Up to date (the image's plugins)", ClusterPlugins.describe(ClusterPlugins.status(0, false, false), 0));
        assertEquals("Up to date", ClusterPlugins.describe(ClusterPlugins.status(3, false, false), 3));
        assertEquals("Applying 2 changes", ClusterPlugins.describe(ClusterPlugins.status(1, false, false), 3));
        assertEquals("Applying 1 change", ClusterPlugins.describe(ClusterPlugins.status(2, false, false), 3));
        assertEquals("Restart pending (one controller at a time)", ClusterPlugins.describe(ClusterPlugins.status(3, true, false), 3));
        assertEquals("Needs attention: still differs after restarting for it", ClusterPlugins.describe(ClusterPlugins.status(3, true, true), 3));
        assertEquals("Unknown (an older version)", ClusterPlugins.describe(null, 3));
        // Target 15: what is being applied, and what failed
        assertEquals("Applying rebuild", ClusterPlugins.describe(ClusterPlugins.status(2, false, false, "rebuild", java.util.List.of()), 3));
        assertEquals("Up to date; could not apply a, b",
                ClusterPlugins.describe(ClusterPlugins.status(3, false, false, null, java.util.List.of("a", "b")), 3));
        assertEquals("Restart pending (one controller at a time); could not apply a",
                ClusterPlugins.describe(ClusterPlugins.status(3, true, false, null, java.util.List.of("a")), 3));
        assertEquals("3 restart-pending", ClusterPlugins.status(3, true, false, null, java.util.List.of()), "unchanged for older readers");
    }
}
