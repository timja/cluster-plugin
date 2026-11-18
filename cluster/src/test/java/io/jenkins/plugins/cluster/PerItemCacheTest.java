package io.jenkins.plugins.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleProject;
import io.jenkins.plugins.cluster.spi.Keys;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockFolder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class PerItemCacheTest {

    @BeforeAll
    static void cluster() {
        ClusterFixture.activate();
    }

    @AfterAll
    static void noCluster() {
        ClusterFixture.deactivate();
    }

    @Test
    void aValueLoadedWhileAChangeWasToldIsLoadedAgainRatherThanCached(JenkinsRule r) throws Exception {
        PerItemCache<String> cache = PerItemCache.create();
        AtomicInteger loads = new AtomicInteger();
        String value = cache.load("_items/a", k -> {
            if (loads.incrementAndGet() == 1) {
                cache.changed(k); // the change of this load's read arrives meanwhile
                return "stale";
            }
            return "fresh";
        });
        assertEquals("fresh", value);
        assertEquals("fresh", cache.get("_items/a"));
        assertEquals(2, loads.get());
        assertEquals("fresh", cache.load("_items/a", k -> "never loaded"), "cached");

        PerItemCache<String> busy = PerItemCache.create();
        assertEquals("changing", busy.load("_items/b", k -> {
            busy.changed(k);
            return "changing";
        }));
        assertNull(busy.get("_items/b"), "changing all the time: not cached");
    }

    @Test
    void anItemIsForgottenWithWhatIsBelowIt(JenkinsRule r) {
        PerItemCache<String> cache = PerItemCache.create();
        cache.put("_items/a", "a");
        cache.put("_items/a/builds/1/build.xml", "a#1");
        cache.put("_items/ab", "another item");
        cache.changed("_items/a");
        PerItemCache.forgetAll("_items/a");
        assertFalse(cache.contains("_items/a"));
        assertFalse(cache.contains("_items/a/builds/1/build.xml"));
        assertEquals("another item", cache.get("_items/ab"));
        assertEquals(0, cache.generation("_items/a"));
    }

    @Test
    void itemsDeletedOrMovedAreForgotten(JenkinsRule r) throws Exception {
        PerItemCache<String> cache = PerItemCache.create();
        FreeStyleProject p = r.createFreeStyleProject("p");
        cache.put(Keys.item(p) + "/builds/1/build.xml", "by identity");
        cache.put("jobs/p/config.xml", "by directory");
        MockFolder f = r.createFolder("f");
        FreeStyleProject q = f.createProject(FreeStyleProject.class, "q");
        cache.put("jobs/f/jobs/q/config.xml", "by directory, in a folder");
        cache.put(Keys.item(q), "q");

        q.renameTo("q2");
        assertFalse(cache.contains("jobs/f/jobs/q/config.xml"), "the old directory is gone");
        assertTrue(cache.contains(Keys.item(q)), "the identity stays");

        p.delete();
        assertFalse(cache.contains(Keys.item(p) + "/builds/1/build.xml"));
        assertFalse(cache.contains("jobs/p/config.xml"));
        assertTrue(cache.contains(Keys.item(q)));
    }
}
