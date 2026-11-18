package io.jenkins.plugins.cluster.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.junit.jupiter.RealJenkinsExtension;

/**
 * Builds across two real controllers ({@link RealCluster}): what a controller killed or restarted under a running build
 * leaves to the other.
 */
class RealClusterTest {

    @RegisterExtension
    private final RealCluster cluster = new RealCluster("a", "b");

    private static String ticks(int ticks, int secondsPerTick) {
        return "for (int i = 1; i <= " + ticks + "; i++) { echo \"tick ${i}\"; sleep " + secondsPerTick
                + " }; echo 'done'";
    }

    @Test
    void aJobCreatedOnOneControllerBuildsFromEither() throws Throwable {
        RealJenkinsExtension a = cluster.controller("a");
        RealJenkinsExtension b = cluster.controller("b");
        cluster.pipeline("p", "echo 'hello'");
        cluster.schedule(b, "p");
        cluster.awaitResult("p", 1, "SUCCESS", 120);
        assertFalse(cluster.keys("_items/").isEmpty(), "its build record is in the store");
        cluster.schedule(a, "p");
        cluster.awaitResult("p", 2, "SUCCESS", 120);
        for (RealJenkinsExtension c : cluster.controllers()) {
            for (int n = 1; n <= 2; n++) {
                int build = n;
                RealCluster.await( // every controller has the record, wherever the build ran
                        "p#" + n + " to have ended on " + c.getName(),
                        60,
                        () -> "SUCCESS".equals(cluster.result(c, "p", build)));
            }
        }
        assertEquals(1, cluster.count("p", 1, "hello"));
    }

    @Test
    void aBuildWhoseControllerIsKilledIsAdoptedByTheOther() throws Throwable {
        cluster.pipeline("p", ticks(15, 2));
        cluster.schedule(cluster.controller("a"), "p");
        cluster.awaitLog("p", 1, "tick 3", 120);
        RealJenkinsExtension owner = cluster.awaitOwner("p", 1);
        Thread.sleep(5000); // its save point ships (a build killed before its first one is not adopted)
        cluster.kill(owner);
        RealCluster.await("p#1 to be adopted", 180, () -> cluster.owner("p", 1) == cluster.next(owner));
        cluster.awaitResult("p", 1, "SUCCESS", 240);
        assertEquals(1, cluster.count("p", 1, "tick 1"), "it went on where it was, it did not start again");
        cluster.start(owner);
    }

    @Test
    void aBuildGoesOnThroughARollingRestart() throws Throwable {
        cluster.pipeline("p", ticks(30, 2));
        cluster.schedule(cluster.controller("a"), "p");
        cluster.awaitLog("p", 1, "tick 2", 120);
        Thread.sleep(5000);
        for (RealJenkinsExtension c : cluster.controllers()) {
            cluster.restart(c);
        }
        cluster.awaitResult("p", 1, "SUCCESS", 300);
        assertEquals(1, cluster.count("p", 1, "tick 1"), "it went on where it was, it did not start again");
        assertEquals(1, cluster.count("p", 1, "done"));
    }
}
