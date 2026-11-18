package io.jenkins.plugins.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class ClusterStartupTest {

    @Test
    void aControllerWhoseStoreWentBackRestartsOnce(JenkinsRule r) {
        List<Integer> exits = new CopyOnWriteArrayList<>();
        IntConsumer exit = ClusterStartup.exit;
        ClusterStartup.exit = exits::add;
        ClusterStartup.restartedForTests();
        try {
            ClusterStartup.storeWentBack("a test restored it");
            ClusterStartup.storeWentBack("and again");
            // a test Jenkins cannot restart itself: it exits instead, for its container to be restarted
            assertTrue(
                    r.jenkins.isQuietingDown() || exits.equals(List.of(ClusterStartup.STORE_WENT_BACK_EXIT)),
                    "restarting: " + exits);
            assertTrue(exits.size() <= 1, "once");
            if (!r.jenkins.isQuietingDown()) {
                assertEquals(List.of(ClusterStartup.STORE_WENT_BACK_EXIT), exits);
            }
        } finally {
            ClusterStartup.exit = exit;
            ClusterStartup.restartedForTests();
        }
    }
}
