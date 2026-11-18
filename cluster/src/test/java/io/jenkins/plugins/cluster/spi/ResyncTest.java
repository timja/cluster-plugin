package io.jenkins.plugins.cluster.spi;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.cluster.ClusterFixture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class ResyncTest {

    @BeforeAll
    static void cluster() {
        ClusterFixture.activate();
    }

    @AfterAll
    static void noCluster() {
        ClusterFixture.deactivate();
    }

    @Test
    void aRequestedCatchUpRunsEveryCacheCatchUpSoon(JenkinsRule r) throws Exception {
        int before = Counting.runs.get();
        Resync.request("a test asked for it");
        ClusterFixture.await("the catch-up", () -> Counting.runs.get() > before);
        // requests made while one waits to run are served by that run; one made later runs again
        int after = Counting.runs.get();
        Resync.request("again");
        ClusterFixture.await("the catch-up again", () -> Counting.runs.get() > after);
        assertTrue(Counting.runs.get() >= after + 1);
    }

    @TestExtension
    public static final class Counting extends Resync {
        static final AtomicInteger runs = new AtomicInteger();

        @Override
        public void catchUp() {
            runs.incrementAndGet();
        }
    }
}
