package io.jenkins.plugins.cluster.postgresql;

import static io.jenkins.plugins.cluster.postgresql.RealCluster.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import hudson.model.FreeStyleProject;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.StringParameterDefinition;
import hudson.tasks.Shell;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jenkins.plugins.lockableresources.LockableResource;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.jenkins.plugins.lockableresources.RequiredResourcesProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.RealJenkinsExtension;

/**
 * The {@code lock} step, and freestyle builds needing a resource, across the two controllers of a cluster
 * ({@link RealCluster}), killed, stopped and started as real ones are. Only lockable-resources' public API and real
 * builds are used, so the test does not depend on how the cluster plugin shares locks.
 *
 * <p>Every lock body prints {@code HOLD <resource> <epoch ms>} when it starts and {@code FREE <resource> <epoch ms>}
 * when it ends. Both controllers run on this host's clock, so no two holds of a resource, read from the consoles of
 * every build, may overlap.
 */
class LocksAcrossControllersTest {

    /** {@code name} or {@code name:label}, declared on every controller as configuration as code would. */
    private static final String RESOURCES = "res,ra,rb,fres,p1:pool,p2:pool";

    @RegisterExtension
    private final RealCluster cluster = new RealCluster("a", "b")
            .configure(c -> c.withPackageLogger(LockableResourcesManager.class, Level.FINE))
            .onStart(r -> declare(r, RESOURCES));

    private RealJenkinsExtension a;
    private RealJenkinsExtension b;

    @BeforeEach
    void controllers() {
        a = cluster.controller("a");
        b = cluster.controller("b");
    }

    // ------------------------------------------------------------------ run on a controller

    private static void declare(JenkinsRule r, String resources) throws IOException {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        for (String spec : resources.split(",")) {
            String[] parts = spec.split(":");
            if (lrm.fromName(parts[0]) == null) {
                LockableResource res = new LockableResource(parts[0]);
                if (parts.length > 1) {
                    res.setLabels(parts[1]);
                }
                lrm.addResource(res, true);
            }
        }
    }

    /** A freestyle job needing {@code fres}, with a parameter so that requests for it are not folded in the queue. */
    private static void createFreestyle(JenkinsRule r, String name) throws Exception {
        FreeStyleProject p = r.jenkins.createProject(FreeStyleProject.class, name);
        p.addProperty(new RequiredResourcesProperty("fres", null, null, null, null));
        p.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("ROUND", "0")));
        p.setConcurrentBuild(true);
        p.getBuildersList()
                .add(new Shell("echo \"HOLD fres $(date +%s%3N)\"; sleep 6; echo \"FREE fres $(date +%s%3N)\""));
        p.save();
    }

    /** Every resource free (not locked, reserved or queued for) and no lock step waiting, as this controller sees it. */
    private static Boolean allFreeHere(JenkinsRule r) {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        synchronized (LockableResourcesManager.syncResources) {
            return lrm.getResources().stream().allMatch(LockableResource::isFree)
                    && lrm.getCurrentQueuedContext().isEmpty();
        }
    }

    private static String stateHere(JenkinsRule r) {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        StringBuilder s = new StringBuilder();
        synchronized (LockableResourcesManager.syncResources) {
            for (LockableResource res : lrm.getResources()) {
                s.append(res.getName())
                        .append(" free=")
                        .append(res.isFree())
                        .append(" locked=")
                        .append(res.isLocked())
                        .append(" reservedBy=")
                        .append(res.getReservedBy())
                        .append(" cause=")
                        .append(res.getLockCause())
                        .append('\n');
            }
            s.append("queued=").append(lrm.getCurrentQueuedContext().size());
        }
        return s.toString();
    }

    private static Boolean heldHere(JenkinsRule r, String resource) {
        LockableResource res = LockableResourcesManager.get().fromName(resource);
        return res != null && (res.isLocked() || res.isReserved());
    }

    private static void reserve(JenkinsRule r, String resource, String user) {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        assertTrue(lrm.reserve(new ArrayList<>(List.of(lrm.fromName(resource))), user), "reserved " + resource);
    }

    private static void unreserve(JenkinsRule r, String resource) {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        lrm.unreserve(new ArrayList<>(List.of(lrm.fromName(resource))));
    }

    // ------------------------------------------------------------------ from the test

    private void freestyle(String name) throws Throwable {
        cluster.create(name, LocksAcrossControllersTest::createFreestyle);
    }

    private void awaitAllFree() throws Throwable {
        await("every resource to be free on both controllers", 60, () -> {
            for (RealJenkinsExtension c : cluster.alive()) {
                if (!c.runRemotely(LocksAcrossControllersTest::allFreeHere)) {
                    return false;
                }
            }
            return true;
        });
    }

    private String state() throws Throwable {
        StringBuilder s = new StringBuilder();
        for (RealJenkinsExtension c : cluster.alive()) {
            s.append("== ")
                    .append(c.getName())
                    .append('\n')
                    .append((String) c.runRemotely(LocksAcrossControllersTest::stateHere))
                    .append('\n');
        }
        return s.toString();
    }

    /** The lock keys left in the store. */
    private List<String> lockKeys() throws Exception {
        return cluster.keys("_locks/");
    }

    /** A lock body: HOLD, ticks, FREE. */
    private static String body(String resource, int ticks, int secondsPerTick) {
        return "echo \"HOLD " + resource + " ${new Date().getTime()}\"; for (int i = 1; i <= " + ticks
                + "; i++) { echo \"tick ${i}\"; sleep " + secondsPerTick + " }; echo \"FREE " + resource
                + " ${new Date().getTime()}\"";
    }

    private static final Pattern HOLD = Pattern.compile("^(HOLD|FREE) (\\S+) (\\d+)$", Pattern.MULTILINE);

    private static String job(String build) {
        return build.substring(0, build.indexOf('#'));
    }

    private static int number(String build) {
        return Integer.parseInt(build.substring(build.indexOf('#') + 1));
    }

    /** Every HOLD has its FREE, and no two holds of a resource overlap; returns the holds of each resource. */
    private Map<String, Integer> assertNoOverlap(String... builds) throws Throwable {
        Map<String, List<long[]>> spans = new HashMap<>();
        Map<String, String> who = new HashMap<>();
        for (String build : builds) {
            Map<String, Long> open = new HashMap<>();
            Matcher m = HOLD.matcher(cluster.log(job(build), number(build)));
            while (m.find()) {
                String res = m.group(2);
                long at = Long.parseLong(m.group(3));
                if (m.group(1).equals("HOLD")) {
                    open.put(res, at);
                } else {
                    Long from = open.remove(res);
                    if (from == null) {
                        fail(build + " freed " + res + " it never held");
                    }
                    spans.computeIfAbsent(res, k -> new ArrayList<>()).add(new long[] {from, at});
                    who.put(res + "@" + from, build);
                }
            }
            if (!open.isEmpty()) {
                fail(build + " held " + open.keySet() + " but never freed it");
            }
        }
        Map<String, Integer> counts = new HashMap<>();
        spans.forEach((res, s) -> {
            s.sort((x, y) -> Long.compare(x[0], y[0]));
            for (int i = 1; i < s.size(); i++) {
                long[] prev = s.get(i - 1);
                long[] next = s.get(i);
                if (next[0] < prev[1]) {
                    fail(res + ": " + who.get(res + "@" + next[0]) + " took it at " + next[0] + " while "
                            + who.get(res + "@" + prev[0]) + " held it until " + prev[1]);
                }
            }
            counts.put(res, s.size());
        });
        return counts;
    }

    /** How many resources were held at once at most, across the given builds. */
    private int mostHeldAtOnce(String... builds) throws Throwable {
        List<long[]> events = new ArrayList<>();
        for (String build : builds) {
            Matcher m = HOLD.matcher(cluster.log(job(build), number(build)));
            while (m.find()) {
                events.add(new long[] {Long.parseLong(m.group(3)), m.group(1).equals("HOLD") ? 1 : -1});
            }
        }
        events.sort((x, y) -> x[0] != y[0] ? Long.compare(x[0], y[0]) : Long.compare(x[1], y[1]));
        int n = 0;
        int most = 0;
        for (long[] e : events) {
            n += (int) e[1];
            most = Math.max(most, n);
        }
        return most;
    }

    // ------------------------------------------------------------------ the tests

    /** 8 builds of one resource, a pool of 2 for 6 builds, and two resources taken in opposite orders. */
    @Test
    void buildsOnBothControllersTakeTurns() throws Throwable {
        List<String> contended = new ArrayList<>();
        for (int j = 1; j <= 8; j++) {
            cluster.pipeline("c" + j, "lock('res') { " + body("res", 2, 1) + " }");
            contended.add("c" + j + "#1");
        }
        for (int j = 1; j <= 8; j++) {
            cluster.schedule(j % 2 == 0 ? b : a, "c" + j);
        }
        List<String> pooled = new ArrayList<>();
        for (int j = 1; j <= 6; j++) {
            cluster.pipeline(
                    "q" + j, "lock(label: 'pool', quantity: 1, variable: 'R') { " + body("${env.R}", 3, 1) + " }");
            pooled.add("q" + j + "#1");
        }
        for (int j = 1; j <= 6; j++) {
            cluster.schedule(j % 2 == 0 ? b : a, "q" + j);
        }
        String both = "echo \"HOLD ra ${new Date().getTime()}\"; echo \"HOLD rb ${new Date().getTime()}\"; sleep 2;"
                + " echo \"FREE ra ${new Date().getTime()}\"; echo \"FREE rb ${new Date().getTime()}\"";
        List<String> crossed = new ArrayList<>();
        for (int j = 1; j <= 3; j++) {
            cluster.pipeline("ab" + j, "lock(resource: 'ra', extra: [[resource: 'rb']]) { " + both + " }");
            cluster.pipeline("ba" + j, "lock(resource: 'rb', extra: [[resource: 'ra']]) { " + both + " }");
            crossed.add("ab" + j + "#1");
            crossed.add("ba" + j + "#1");
        }
        for (int j = 1; j <= 3; j++) {
            cluster.schedule(a, "ab" + j);
            cluster.schedule(b, "ba" + j);
        }
        List<String> all = new ArrayList<>(contended);
        all.addAll(pooled);
        all.addAll(crossed);
        for (String build : all) {
            cluster.awaitResult(build.substring(0, build.indexOf('#')), 1, "SUCCESS", 600);
        }
        assertEquals(Map.of("res", 8), assertNoOverlap(contended.toArray(String[]::new)));
        Map<String, Integer> pool = assertNoOverlap(pooled.toArray(String[]::new));
        assertEquals(6, pool.values().stream().mapToInt(Integer::intValue).sum(), pool.toString());
        assertTrue(mostHeldAtOnce(pooled.toArray(String[]::new)) <= 2, "never more than the pool's 2 held at once");
        assertEquals(Map.of("ra", 3 * 2, "rb", 3 * 2), assertNoOverlap(crossed.toArray(String[]::new)));
        List<String> events = cluster.events("_locks/res");
        for (int i = 0; i < events.size(); i++) {
            assertEquals(
                    i % 2 == 0 ? "WRITTEN" : "DELETED", events.get(i), "res taken and released in turn: " + events);
        }
        awaitAllFree();
        assertEquals(List.of(), lockKeys());
    }

    /** The holder's controller dies: the other controller adopts the build, which keeps the resource to its end. */
    @Test
    void holderAdoptedAfterItsControllerIsKilledKeepsTheLock() throws Throwable {
        cluster.pipeline("h", "lock('res') { " + body("res", 15, 2) + " }");
        cluster.pipeline("w", "lock('res') { " + body("res", 2, 1) + " }");
        cluster.schedule(a, "h");
        cluster.awaitLog("h", 1, "tick 3", 120);
        RealJenkinsExtension holder = cluster.awaitOwner("h", 1);
        cluster.schedule(cluster.next(holder), "w");
        cluster.awaitLog("w", 1, "is not free, waiting for execution", 60);
        Thread.sleep(5000); // the waiting build's save point ships (a build killed before its first one is not adopted)
        cluster.kill(holder);
        await("h#1 to be adopted", 180, () -> cluster.owner("h", 1) == cluster.next(holder));
        assertFalse(cluster.logHas("w", 1, "HOLD res"), "the waiter has not got res while the adopted holder runs");
        cluster.awaitResult("h", 1, "SUCCESS", 240);
        assertEquals(1, cluster.count("h", 1, "tick 1"), "the holder went on where it was, it did not start again");
        cluster.awaitResult("w", 1, "SUCCESS", 180);
        assertNoOverlap("h#1", "w#1");
        cluster.start(holder);
        awaitAllFree();
        assertEquals(List.of(), lockKeys(), state());
    }

    /** The waiter's controller dies: the build is adopted while it waits, and still gets the resource, once. */
    @Test
    void waiterAdoptedAfterItsControllerIsKilledGetsTheLockOnce() throws Throwable {
        cluster.pipeline("h", "lock('res') { " + body("res", 12, 2) + " }");
        cluster.pipeline("w", "lock('res') { " + body("res", 2, 1) + " }");
        cluster.schedule(a, "h");
        cluster.awaitLog("h", 1, "tick 2", 120);
        RealJenkinsExtension holder = cluster.awaitOwner("h", 1);
        cluster.schedule(cluster.next(holder), "w");
        cluster.awaitLog("w", 1, "is not free, waiting for execution", 60);
        Thread.sleep(5000);
        RealJenkinsExtension waiter = cluster.awaitOwner("w", 1);
        cluster.kill(waiter);
        await("w#1 to be adopted", 180, () -> cluster.owner("w", 1) == cluster.next(waiter));
        cluster.awaitResult("h", 1, "SUCCESS", 240);
        cluster.awaitResult("w", 1, "SUCCESS", 180);
        assertEquals(1, cluster.count("w", 1, "HOLD res"), "the waiter held res once");
        assertNoOverlap("h#1", "w#1");
        cluster.start(waiter);
        awaitAllFree();
        assertEquals(List.of(), lockKeys(), state());
    }

    /** The waiter's controller is stopped cleanly and started again (a rolling update of that controller). */
    @Test
    void waiterWhoseControllerRestartsGetsTheLockOnce() throws Throwable {
        cluster.pipeline("h", "lock('res') { " + body("res", 20, 2) + " }");
        cluster.pipeline("w", "lock('res') { " + body("res", 2, 1) + " }");
        cluster.schedule(a, "h");
        cluster.awaitLog("h", 1, "tick 2", 120);
        RealJenkinsExtension holder = cluster.awaitOwner("h", 1);
        cluster.schedule(cluster.next(holder), "w");
        cluster.awaitLog("w", 1, "is not free, waiting for execution", 60);
        Thread.sleep(5000);
        RealJenkinsExtension waiter = cluster.awaitOwner("w", 1);
        cluster.restart(waiter);
        cluster.awaitResult("h", 1, "SUCCESS", 300);
        cluster.awaitResult("w", 1, "SUCCESS", 240);
        assertEquals(1, cluster.count("w", 1, "HOLD res"), "the waiter held res once");
        assertNoOverlap("h#1", "w#1");
        awaitAllFree();
        assertEquals(List.of(), lockKeys(), state());
    }

    /**
     * A rolling restart of the whole cluster while one build holds a resource and others wait for it: each controller
     * in turn is stopped (its JVM exits) and a replacement started, so every build is resumed or adopted at least once.
     */
    @Test
    void rollingRestartWhileBuildsHoldAndWait() throws Throwable {
        cluster.pipeline("h", "lock('res') { " + body("res", 30, 2) + " }");
        cluster.pipeline("w1", "lock('res') { " + body("res", 3, 1) + " }");
        cluster.pipeline("w2", "lock('res') { " + body("res", 3, 1) + " }");
        cluster.schedule(a, "h");
        cluster.awaitLog("h", 1, "tick 2", 120);
        cluster.schedule(a, "w1");
        cluster.schedule(b, "w2");
        cluster.awaitLog("w1", 1, "is not free, waiting for execution", 60);
        cluster.awaitLog("w2", 1, "is not free, waiting for execution", 60);
        Thread.sleep(5000);
        for (RealJenkinsExtension c : cluster.controllers()) {
            cluster.restart(c);
        }
        cluster.awaitResult("h", 1, "SUCCESS", 300);
        cluster.awaitResult("w1", 1, "SUCCESS", 240);
        cluster.awaitResult("w2", 1, "SUCCESS", 240);
        assertEquals(1, cluster.count("h", 1, "tick 1"), "the holder went on where it was, it did not start again");
        for (String w : List.of("w1", "w2")) {
            assertEquals(1, cluster.count(w, 1, "HOLD res"), w + " held res once");
        }
        assertNoOverlap("h#1", "w1#1", "w2#1");
        awaitAllFree();
        assertEquals(List.of(), lockKeys(), state());
    }

    /** A lock with a timeout gives up while a build on the other controller holds the resource. */
    @Test
    void lockTimesOutWhileHeldOnTheOtherController() throws Throwable {
        cluster.pipeline("h", "lock('res') { " + body("res", 15, 2) + " }");
        cluster.pipeline(
                "t",
                "lock(resource: 'res', timeoutForAllocateResource: 10, timeoutUnit: 'SECONDS') { echo 'GOT res' }");
        cluster.schedule(a, "h");
        cluster.awaitLog("h", 1, "tick 2", 120);
        RealJenkinsExtension holder = cluster.awaitOwner("h", 1);
        cluster.schedule(cluster.next(holder), "t");
        await("t#1 to end", 120, () -> cluster.result("t", 1) != null);
        assertFalse(cluster.logHas("t", 1, "GOT res"), "it did not run its body");
        assertFalse("SUCCESS".equals(cluster.result("t", 1)), "it did not succeed");
        cluster.awaitResult("h", 1, "SUCCESS", 120);
        cluster.schedule(b, "t");
        cluster.awaitResult("t", 2, "SUCCESS", 120);
        awaitAllFree();
    }

    /** A reservation made on one controller keeps a build on the other from locking the resource. */
    @Test
    void reservationOnOneControllerBlocksALockOnTheOther() throws Throwable {
        a.runRemotely(LocksAcrossControllersTest::reserve, "res", "alice");
        await("B to show res held", 30, () -> b.runRemotely(LocksAcrossControllersTest::heldHere, "res"));
        cluster.pipeline("w", "lock('res') { echo 'GOT res' }");
        cluster.schedule(b, "w");
        cluster.awaitLog("w", 1, "is not free, waiting for execution", 60);
        Thread.sleep(10_000);
        assertFalse(cluster.logHas("w", 1, "GOT res"), "it has not got res 10 s later");
        a.runRemotely(LocksAcrossControllersTest::unreserve, "res");
        cluster.awaitResult("w", 1, "SUCCESS", 120);
        awaitAllFree();
        assertEquals(List.of(), lockKeys(), state());
    }

    /** Freestyle builds needing one resource, started on both controllers at the same moment, never run together. */
    @Test
    void freestyleBuildsOnBothControllersTakeTurns() throws Throwable {
        freestyle("f1");
        freestyle("f2");
        for (int round = 1; round <= 4; round++) {
            String r = String.valueOf(round);
            Thread t = new Thread(() -> {
                try {
                    cluster.schedule(b, "f2", "ROUND", r);
                } catch (Throwable x) {
                    throw new AssertionError(x);
                }
            });
            t.start();
            cluster.schedule(a, "f1", "ROUND", r);
            t.join();
            Thread.sleep(1000);
        }
        List<String> builds = new ArrayList<>();
        for (String job : List.of("f1", "f2")) {
            for (int n = 1; n <= 4; n++) {
                cluster.awaitResult(job, n, "SUCCESS", 300);
                builds.add(job + "#" + n);
            }
        }
        assertEquals(Map.of("fres", 8), assertNoOverlap(builds.toArray(String[]::new)));
        awaitAllFree();
        assertEquals(List.of(), lockKeys(), state());
    }
}
