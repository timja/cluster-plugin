package io.jenkins.plugins.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.FreeStyleProject;
import hudson.model.User;
import io.jenkins.plugins.cluster.spi.ClusterBlobStore;
import io.jenkins.plugins.cluster.spi.ClusterBlobStoreProvider;
import io.jenkins.plugins.cluster.spi.InMemoryClusterBlobStore;
import io.jenkins.plugins.cluster.spi.InMemoryClusterStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import jenkins.persistence.PersistenceStore;
import jenkins.persistence.PersistenceStoreProvider;
import jenkins.persistence.PersistenceStores;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

/** A file-based home started once with {@code importHome=true} is imported into the store, which it then runs on. */
class HomeImporterTest {

    @RegisterExtension
    private final JenkinsSessionExtension sessions = new JenkinsSessionExtension();

    /** The store the second session imports into, and runs on; none in the first. */
    private static volatile InMemoryClusterStore store;

    private static volatile InMemoryClusterBlobStore blobs;

    @AfterEach
    void reset() {
        store = null;
        blobs = null;
        HomeImporter.IMPORT = false;
    }

    @Test
    void aHomeWithAJobAUserAndGlobalConfigurationIsImportedIntoAnEmptyStore() throws Throwable {
        sessions.then(r -> {
            r.createFreeStyleProject("p").setDescription("imported job");
            User alice = User.getById("alice", true);
            alice.setFullName("Alice Imported");
            alice.save();
            r.jenkins.setSystemMessage("imported message");
            r.jenkins.save();
        });
        store = new InMemoryClusterStore();
        blobs = new InMemoryClusterBlobStore();
        HomeImporter.IMPORT = true;
        sessions.then(r -> {
            assertTrue(PersistenceStores.get() == store, "running on the store imported into");
            assertNotNull(store.read("config.xml"), "the global configuration is in the store");
            assertNotNull(store.read("jobs/p/config.xml"), "the job is in the store");
            assertTrue(
                    store.children("users").stream().anyMatch(u -> {
                        PersistenceStore.Entry e = store.read("users/" + u + "/config.xml");
                        return e != null && new String(e.content(), StandardCharsets.UTF_8).contains("Alice Imported");
                    }),
                    "the user is in the store: " + store.children("users"));
            // and it loaded them from there
            FreeStyleProject p = r.jenkins.getItemByFullName("p", FreeStyleProject.class);
            assertNotNull(p, "the job loads");
            assertEquals("imported job", p.getDescription());
            assertEquals("imported message", r.jenkins.getSystemMessage());
            assertEquals("Alice Imported", User.getById("alice", false).getFullName());
            assertNotNull(store.read(KeyCheck.KEY), "the imported home's keys became the cluster's");
        });
    }

    @Test
    void aHomeIsNotImportedIntoAStoreWithOtherKeys() throws Throwable {
        sessions.then(r -> {
            InMemoryClusterStore other = new InMemoryClusterStore();
            other.write(KeyCheck.KEY, "{AQAAABAAAAAQ}".getBytes(StandardCharsets.UTF_8), PersistenceStore.ABSENT);
            HomeImporter.IMPORT = true;
            IllegalStateException e =
                    assertThrows(IllegalStateException.class, () -> ClusterStartup.beforeJoining(other));
            assertTrue(e.getMessage().contains("Not importing"), e.getMessage());
        });
    }

    /** Creates the store as a backend's provider does: checks the keys, and imports. */
    @TestExtension
    public static final class ImportingStores implements PersistenceStoreProvider {
        @CheckForNull
        @Override
        public PersistenceStore create() {
            InMemoryClusterStore s = store;
            if (s == null) {
                return null;
            }
            try {
                ClusterStartup.beforeJoining(s);
                ClusterStartup.afterJoining(s);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return s;
        }
    }

    @TestExtension
    public static final class Blobs implements ClusterBlobStoreProvider {
        @CheckForNull
        @Override
        public ClusterBlobStore create() {
            return store == null ? null : blobs;
        }
    }
}
