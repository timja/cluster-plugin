package io.jenkins.plugins.cluster;

import static io.jenkins.plugins.cluster.ClusterPlugins.Action.DELETE;
import static io.jenkins.plugins.cluster.ClusterPlugins.Action.DISABLE;
import static io.jenkins.plugins.cluster.ClusterPlugins.Action.ENABLE;
import static io.jenkins.plugins.cluster.ClusterPlugins.Action.LOAD;
import static io.jenkins.plugins.cluster.ClusterPlugins.Action.NONE;
import static io.jenkins.plugins.cluster.ClusterPlugins.Action.REPLACE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.cluster.ClusterPlugins.Entry;
import io.jenkins.plugins.cluster.ClusterPlugins.Overlay;
import io.jenkins.plugins.cluster.ClusterPlugins.State;
import io.jenkins.plugins.cluster.spi.ClusterBlobStore;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
        assertEquals(
                NONE, ClusterPlugins.decide(installed("2.0"), "2.1", true), "never downgrades: a newer image wins");
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
        assertTrue(
                ClusterPlugins.needsRestart(state(State.installed), false, null, "1.0", true),
                "enabled, not yet running");
    }

    @Test
    void overlayKeepsOrderAndCountsGenerations() {
        Overlay o = Overlay.EMPTY
                .with("dep", installed("1.0"))
                .with("top", installed("2.0"))
                .with("dep", installed("1.1"));
        assertEquals(3, o.generation());
        assertEquals(
                List.of("dep", "top"), List.copyOf(o.plugins().keySet()), "dependencies first, also after an upgrade");
        Overlay parsed = Overlay.parse(o.toBytes());
        assertEquals(o, parsed);
        assertEquals(Overlay.EMPTY, Overlay.parse(null));
        assertEquals(
                state(State.uninstalled),
                Overlay.parse(Overlay.EMPTY.with("x", state(State.uninstalled)).toBytes())
                        .plugins()
                        .get("x"));
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
        assertEquals(
                "Up to date (the image's plugins)", ClusterPlugins.describe(ClusterPlugins.status(0, false, false), 0));
        assertEquals("Up to date", ClusterPlugins.describe(ClusterPlugins.status(3, false, false), 3));
        assertEquals("Applying 2 changes", ClusterPlugins.describe(ClusterPlugins.status(1, false, false), 3));
        assertEquals("Applying 1 change", ClusterPlugins.describe(ClusterPlugins.status(2, false, false), 3));
        assertEquals(
                "Restart pending (one controller at a time)",
                ClusterPlugins.describe(ClusterPlugins.status(3, true, false), 3));
        assertEquals(
                "Needs attention: still differs after restarting for it",
                ClusterPlugins.describe(ClusterPlugins.status(3, true, true), 3));
        assertEquals("Unknown (an older version)", ClusterPlugins.describe(null, 3));
        // Target 15: what is being applied, and what failed
        assertEquals(
                "Applying rebuild",
                ClusterPlugins.describe(ClusterPlugins.status(2, false, false, "rebuild", java.util.List.of()), 3));
        assertEquals(
                "Up to date; could not apply a, b",
                ClusterPlugins.describe(ClusterPlugins.status(3, false, false, null, java.util.List.of("a", "b")), 3));
        assertEquals(
                "Restart pending (one controller at a time); could not apply a",
                ClusterPlugins.describe(ClusterPlugins.status(3, true, false, null, java.util.List.of("a")), 3));
        assertEquals(
                "3 restart-pending",
                ClusterPlugins.status(3, true, false, null, java.util.List.of()),
                "unchanged for older readers");
    }

    @Test
    void disablingAndEnablingKeepTheArchiveOfAPluginTheClusterInstalled() {
        Entry disabled = ClusterPlugins.next(installed("2.0"), State.disabled, "cnj-1", 2);
        assertEquals(new Entry(State.disabled, "2.0", "abc", "cluster-plugins/p/abc.jpi", "cnj-1", 2), disabled);
        assertEquals(
                disabled,
                Overlay.parse(Overlay.EMPTY.with("p", disabled).toBytes())
                        .plugins()
                        .get("p"),
                "stored as such");
        assertEquals(NONE, ClusterPlugins.decide(disabled, null, true), "not fetched while disabled");
        Entry enabled = ClusterPlugins.next(disabled, State.installed, "cnj-2", 3);
        assertEquals(new Entry(State.installed, "2.0", "abc", "cluster-plugins/p/abc.jpi", "cnj-2", 3), enabled);
        assertEquals(LOAD, ClusterPlugins.decide(enabled, null, true), "a home without it gets it again");
        Entry uninstalled = ClusterPlugins.next(disabled, State.uninstalled, "cnj-1", 4);
        assertNull(uninstalled.blob());
        assertNull(
                ClusterPlugins.next(state(State.disabled), State.installed, "cnj-1", 5)
                        .version(),
                "an image plugin");
        assertNull(ClusterPlugins.next(null, State.disabled, "cnj-1", 5).version());
    }

    private static byte[] plugin(String name, String version) throws Exception {
        Manifest m = new Manifest();
        m.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        m.getMainAttributes().putValue("Short-Name", name);
        m.getMainAttributes().putValue("Plugin-Version", version);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new JarOutputStream(out, m).close();
        return out.toByteArray();
    }

    private static Entry shared(Map<String, byte[]> blobs, String name, String version, byte[] archive, Path tmp)
            throws Exception {
        File f = tmp.resolve(name + "-" + version + ".tmp").toFile();
        Files.write(f.toPath(), archive);
        String sha = ClusterPlugins.sha256(f);
        String blob = ClusterPlugins.BLOBS + name + "/" + sha + ".jpi";
        blobs.put(blob, archive);
        return new Entry(State.installed, version, sha, blob, "cnj-0", 1);
    }

    private static ClusterBlobStore blobStore(Map<String, byte[]> blobs) throws IOException {
        ClusterBlobStore store = new io.jenkins.plugins.cluster.spi.InMemoryClusterBlobStore();
        for (Map.Entry<String, byte[]> e : blobs.entrySet()) {
            store.put(e.getKey(), new ByteArrayInputStream(e.getValue()), e.getValue().length);
        }
        return store;
    }

    @Test
    void aFreshHomeFetchesTheClusterPluginsItLacksBeforeLoadingAnything(@TempDir Path tmp) throws Exception {
        File plugins = Files.createDirectories(tmp.resolve("home/plugins")).toFile();
        Files.write(new File(plugins, "image.jpi").toPath(), plugin("image", "1.0"));
        Files.write(new File(plugins, "same.jpi").toPath(), plugin("same", "3.0"));
        Map<String, byte[]> blobs = new HashMap<>();
        Entry added = shared(blobs, "added", "1.2", plugin("added", "1.2"), tmp);
        Entry upgraded = shared(blobs, "image", "1.1", plugin("image", "1.1"), tmp);
        Entry same = shared(blobs, "same", "3.0", plugin("same", "3.0"), tmp);
        Entry corrupt = new Entry(State.installed, "1.0", "0000", "cluster-plugins/corrupt/0000.jpi", "cnj-0", 1);
        blobs.put(corrupt.blob(), plugin("corrupt", "1.0"));
        Overlay overlay = Overlay.EMPTY
                .with("added", added)
                .with("image", upgraded)
                .with("same", same)
                .with("corrupt", corrupt)
                .with("../escape", added)
                .with("cluster", added)
                .with("off", ClusterPlugins.next(added, State.disabled, "cnj-0", 2));

        Map<String, Entry> missing = ClusterPlugins.missingAtBoot(overlay, plugins);
        assertEquals(
                List.of("added", "image", "corrupt"),
                List.copyOf(missing.keySet()),
                "not one it has, a disabled one, a cluster plugin or a name that is not a file name");
        assertEquals(
                List.of("added", "image"), ClusterPlugins.fetchAtBoot(missing, blobStore(blobs), plugins), "verified");
        assertArrayEquals(plugin("added", "1.2"), Files.readAllBytes(new File(plugins, "added.jpi").toPath()));
        assertArrayEquals(plugin("image", "1.1"), Files.readAllBytes(new File(plugins, "image.jpi").toPath()));
        assertTrue(
                new File(plugins, "added.jpi" + ClusterPlugins.MARKER).isFile(),
                "the image entrypoint leaves it alone");
        assertFalse(new File(plugins, "corrupt.jpi").exists());
        assertEquals(
                List.of("corrupt"),
                List.copyOf(ClusterPlugins.missingAtBoot(overlay, plugins).keySet()),
                "fetched once");
    }

    @Test
    void onlyAHomeThatLoadedNothingIsFresh(@TempDir Path home) throws Exception {
        assertTrue(ClusterPlugins.isFreshHome(home.toFile()));
        Files.createDirectories(home.resolve("jobs"));
        assertTrue(ClusterPlugins.isFreshHome(home.toFile()));
        Files.createDirectories(home.resolve("jobs/p"));
        assertFalse(ClusterPlugins.isFreshHome(home.toFile()), "items");
        Files.delete(home.resolve("jobs/p"));
        Files.writeString(home.resolve("queue.xml"), "<queue/>");
        assertFalse(
                ClusterPlugins.isFreshHome(home.toFile()),
                "a saved queue, which restarting before loading it would empty");
    }

    private static void plugin(File file, String name, String version) throws Exception {
        java.util.jar.Manifest m = new java.util.jar.Manifest();
        m.getMainAttributes().put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0");
        m.getMainAttributes().putValue("Short-Name", name);
        m.getMainAttributes().putValue("Plugin-Version", version);
        try (java.util.jar.JarOutputStream out =
                new java.util.jar.JarOutputStream(Files.newOutputStream(file.toPath()), m)) {
            // a manifest is all it takes
        }
    }

    @Test
    void theNewestKnownVersionIsTheClustersOrTheOneHere(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        File plugins = dir.toFile();
        assertNull(ClusterPlugins.newestKnown("git", null, plugins), "never installed");
        plugin(new File(plugins, "git.jpi"), "git", "5.1");
        assertEquals("5.1", ClusterPlugins.newestKnown("git", null, plugins), "the image's");
        Entry newer = new Entry(State.installed, "5.3", "x", "b", "c1", 0);
        assertEquals("5.3", ClusterPlugins.newestKnown("git", newer, plugins), "installed elsewhere, not here yet");
        Entry older = new Entry(State.installed, "5.0", "x", "b", "c1", 0);
        assertEquals("5.1", ClusterPlugins.newestKnown("git", older, plugins));
        Entry uninstalled = new Entry(State.uninstalled, null, null, null, "c1", 0);
        Files.delete(plugins.toPath().resolve("git.jpi"));
        assertNull(
                ClusterPlugins.newestKnown("git", uninstalled, plugins),
                "uninstalled first: an older version may be installed");
        plugin(new File(plugins, "legacy.hpi"), "legacy", "1.2");
        assertEquals("1.2", ClusterPlugins.newestKnown("legacy", null, plugins));
    }
}
