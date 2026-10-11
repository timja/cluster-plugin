package io.jenkins.plugins.cnj.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HomeWritesTest {

    @Test
    void localByDesign() {
        for (String key : new String[] {
            "plugins/git.jpi", "jobs/p/builds/1/build.xml", "jobs/f/jobs/p/workspace/x", "jobs/p/running/3.xml", "queue.xml",
            "secrets/master.key", "logs/tasks/x.log", "caches/git-1/HEAD", "users/users.xml", "secret.key",
            "org.jenkins.plugins.lockableresources.LockableResourcesManager.xml", "users/alice_1/apiTokenStats.xml", "userContent/readme.txt"
        }) {
            assertFalse(HomeWrites.reportable(key, null), key);
        }
        for (String key : new String[] {"foo-bad/state.xml", "jobs/p/indexing/indexing.xml", "userContent/notes.txt"}) {
            assertTrue(HomeWrites.reportable(key, null), key);
        }
    }

    @Test
    void patterns() {
        assertEquals("jobs/*/indexing/indexing.xml", HomeWrites.pattern("jobs/mbp/indexing/indexing.xml"));
        assertEquals("jobs/*/jobs/*/state.xml", HomeWrites.pattern("jobs/f/jobs/p/state.xml"));
        assertEquals("users/*/x.xml", HomeWrites.pattern("users/alice_123/x.xml"));
        assertEquals("foo/*/x", HomeWrites.pattern("foo/42/x"));
    }
}
