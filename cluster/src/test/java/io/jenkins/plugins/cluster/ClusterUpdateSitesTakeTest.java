package io.jenkins.plugins.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.Util;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;
import jenkins.persistence.PersistenceStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** Names listed in the cluster's update-site index never become paths outside {@code updates/}. */
@WithJenkins
class ClusterUpdateSitesTakeTest {

    @BeforeAll
    static void cluster() {
        ClusterFixture.activate();
    }

    @AfterAll
    static void noCluster() {
        ClusterFixture.deactivate();
    }

    private static ClusterUpdateSites.Shared share(String blob, String content) throws Exception {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        ClusterFixture.blobs().put(blob, new ByteArrayInputStream(bytes), bytes.length);
        String sha = Util.toHexString(MessageDigest.getInstance("SHA-256").digest(bytes));
        return new ClusterUpdateSites.Shared(System.currentTimeMillis(), sha, blob, null, "other");
    }

    @Test
    void namesThatAreNotOneFileInUpdatesAreNeverTaken(JenkinsRule r) throws Exception {
        File home = r.jenkins.getRootDir();
        File updates = new File(home, "updates");
        Files.createDirectories(updates.toPath());
        Path outside = Files.createTempFile("outside", ".json");
        Files.writeString(outside, "untouched");
        Files.setLastModifiedTime(outside, FileTime.fromMillis(1000)); // older than the cluster's
        Files.createSymbolicLink(updates.toPath().resolve("linked.json"), outside);
        Map<String, ClusterUpdateSites.Shared> files = new TreeMap<>();
        files.put("../evil.json", share("cluster-updates/evil1", "evil"));
        files.put("../init.groovy.d/evil.groovy", share("cluster-updates/evil2", "evil"));
        files.put("sub/evil.json", share("cluster-updates/evil3", "evil"));
        files.put("..", share("cluster-updates/evil4", "evil"));
        files.put("linked.json", share("cluster-updates/evil5", "evil"));
        files.put("good.json", share("cluster-updates/good", "good"));
        ClusterFixture.store().write(ClusterUpdateSites.KEY, ClusterUpdateSites.toBytes(files), PersistenceStore.ANY);

        ClusterUpdateSites.sync(false);

        assertEquals("good", Files.readString(new File(updates, "good.json").toPath()), "a plain name is taken");
        assertFalse(new File(home, "evil.json").exists(), "../ never leaves updates/");
        assertFalse(new File(home, "init.groovy.d/evil.groovy").exists(), "../ never leaves updates/");
        assertFalse(new File(updates, "sub").exists(), "no subdirectory is made");
        assertEquals("untouched", Files.readString(outside), "a link is never written through");
        assertTrue(Files.isSymbolicLink(updates.toPath().resolve("linked.json")), "nor replaced");
        Files.delete(outside);
    }
}
