package io.jenkins.plugins.cluster.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleProject;
import io.jenkins.plugins.cluster.ClusterFixture;
import io.jenkins.plugins.cluster.PerItemCache;
import io.jenkins.plugins.cluster.spi.Keys;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import jenkins.persistence.PersistenceStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** Files plugins keep in item directories are shared by the item's identity, and never written outside them. */
@WithJenkins
class ItemFilesTest {

    @BeforeAll
    static void cluster() {
        ClusterFixture.activate();
        System.setProperty(ItemFiles.class.getName() + ".enabled", "true");
    }

    @AfterAll
    static void noCluster() {
        ClusterFixture.deactivate();
        System.clearProperty(ItemFiles.class.getName() + ".enabled");
    }

    @Test
    void sharedAndTakenByAnotherControllersCopy(JenkinsRule r) throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("p");
        Path state = p.getRootDir().toPath().resolve("plugin-state.json");
        Files.writeString(state, "{\"v\":1}");
        ItemFiles.Place place = ItemFiles.of(p);
        assertEquals(Keys.itemFile(Keys.item(p), ItemFiles.MANIFEST), place.manifest());
        ItemFiles.publish(place);
        assertNotNull(ClusterFixture.store().read(place.manifest()));
        assertTrue(new String(ClusterFixture.store().read(place.manifest()).content(), StandardCharsets.UTF_8)
                .contains("plugin-state.json"));
        assertFalse(
                new String(ClusterFixture.store().read(place.manifest()).content(), StandardCharsets.UTF_8)
                        .contains("config.xml"),
                "what the store holds already is not shared again");
        // as another controller, which never had it
        Files.delete(state);
        Files.delete(p.getRootDir().toPath().resolve(ItemFiles.KNOWN));
        PerItemCache.forgetAllForTests();
        ItemFiles.take(place);
        assertEquals("{\"v\":1}", Files.readString(state));
    }

    @Test
    void aManifestNamingAFileOutsideTheDirectoryIsNotTaken(JenkinsRule r, @TempDir Path outside) throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("q");
        ItemFiles.Place place = ItemFiles.of(p);
        String sha = "0".repeat(64);
        ClusterFixture.blobs().put(place.blob(sha), new java.io.ByteArrayInputStream(new byte[] {1}), 1);
        File escape = new File(p.getRootDir(), "../escaped.txt");
        ClusterFixture.store()
                .as("other")
                .write(
                        place.manifest(),
                        ("{\"../escaped.txt\":\"" + sha + "\",\"builds/1/build.xml\":\"" + sha + "\",\"config.xml\":\""
                                        + sha + "\"}")
                                .getBytes(StandardCharsets.UTF_8),
                        PersistenceStore.ANY);
        ItemFiles.take(place);
        assertFalse(escape.exists());
        assertFalse(new File(p.getRootDir(), "builds/1/build.xml").exists());
        assertFalse(
                Files.readString(p.getConfigFile().getFile().toPath()).isEmpty(), "its configuration is not replaced");
    }

    @Test
    void filesSharedElsewhereWhoseEventWasLostAreTakenOnCatchingUp(JenkinsRule r) throws Exception {
        FreeStyleProject p = r.createFreeStyleProject("caught-up");
        ItemFiles.Place place = ItemFiles.of(p);
        byte[] content = "{\"v\":2}".getBytes(StandardCharsets.UTF_8);
        String sha = java.util.HexFormat.of()
                .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content));
        ClusterFixture.blobs().put(place.blob(sha), new java.io.ByteArrayInputStream(content), content.length);
        ClusterFixture.store().loseEvents(true);
        try {
            ClusterFixture.store()
                    .as("other")
                    .write(
                            place.manifest(),
                            ("{\"plugin-state.json\":\"" + sha + "\"}").getBytes(StandardCharsets.UTF_8),
                            PersistenceStore.ANY);
        } finally {
            ClusterFixture.store().loseEvents(false);
        }
        Path state = p.getRootDir().toPath().resolve("plugin-state.json");
        assertFalse(Files.exists(state), "its event was lost");
        io.jenkins.plugins.cluster.spi.Resync.runAll();
        assertEquals("{\"v\":2}", Files.readString(state));
    }
}
