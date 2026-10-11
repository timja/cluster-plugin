package io.jenkins.plugins.cnj.store;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import jenkins.persistence.ConflictException;
import jenkins.persistence.PersistenceStore;
import jenkins.persistence.PersistenceStore.ChangeEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class PostgresPersistenceStoreTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    private PostgresPersistenceStore a;
    private PostgresPersistenceStore b;

    @BeforeEach
    void setUp() throws Exception {
        try (var c = POSTGRES.createConnection("");
                var s = c.createStatement()) {
            s.execute(
                    "drop table if exists jenkins_store, jenkins_store_events, jenkins_members, jenkins_trigger_claims, jenkins_build_leases, jenkins_queue");
        }
        a = store();
        b = store();
    }

    @AfterEach
    void tearDown() {
        a.close();
        b.close();
    }

    private static PostgresPersistenceStore store() throws Exception {
        return new PostgresPersistenceStore(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword(),
                key -> PostgresPersistenceStoreProvider.OWNED.matcher(key).matches());
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void controllersStartingAtOnceCreateTheSchemaOnce() throws Exception {
        a.close();
        b.close();
        try (var c = POSTGRES.createConnection("");
                var st = c.createStatement()) {
            st.execute(
                    "drop table if exists jenkins_store, jenkins_store_events, jenkins_members, jenkins_trigger_claims,"
                            + " jenkins_build_leases, jenkins_queue");
            st.execute("drop sequence if exists jenkins_store_version, jenkins_queue_ids");
        }
        var pool = java.util.concurrent.Executors.newFixedThreadPool(6);
        try {
            var stores = new java.util.ArrayList<java.util.concurrent.Future<PostgresPersistenceStore>>();
            for (int i = 0; i < 6; i++) {
                stores.add(pool.submit(PostgresPersistenceStoreTest::store));
            }
            for (var f : stores) {
                f.get().close(); // none fails
            }
        } finally {
            pool.shutdownNow();
        }
        a = store();
        b = store();
    }

    @Test
    void owns() {
        assertTrue(a.owns("jobs/p/config.xml"));
        assertTrue(a.owns("jobs/f/jobs/p/config.xml"));
        assertTrue(a.owns("jobs/mb/branches/main/config.xml"));
        assertFalse(a.owns("config.xml"));
        assertFalse(a.owns("jobs/p/builds/1/build.xml"));
        assertTrue(a.owns("jobs/p/nextBuildNumber"));
        assertTrue(a.owns("jobs/mb/branches/main/scm-revision-hash.xml"));
        assertTrue(a.owns("jobs/mb/branches/main/scm-last-seen-revision-hash.xml"));
        assertFalse(a.owns("jobs/mb/state.xml"));
        assertFalse(a.owns("jobs/p/builds/1/build.xml"));
    }

    @Test
    void readWriteAcrossInstances() throws Exception {
        assertNull(a.read("jobs/p/config.xml"));
        long v1 = a.write("jobs/p/config.xml", bytes("one"), PersistenceStore.ABSENT);
        PersistenceStore.Entry e = b.read("jobs/p/config.xml");
        assertArrayEquals(bytes("one"), e.content());
        assertEquals(v1, e.version());
        long v2 = b.write("jobs/p/config.xml", bytes("two"), v1);
        assertTrue(v2 > v1);
    }

    @Test
    void compareAndSet() throws Exception {
        long v1 = a.write("jobs/p/config.xml", bytes("one"), PersistenceStore.ABSENT);
        assertThrows(ConflictException.class, () -> b.write("jobs/p/config.xml", bytes("x"), PersistenceStore.ABSENT));
        b.write("jobs/p/config.xml", bytes("two"), v1);
        ConflictException c =
                assertThrows(ConflictException.class, () -> a.write("jobs/p/config.xml", bytes("three"), v1));
        assertEquals("jobs/p/config.xml", c.getKey());
        assertThrows(ConflictException.class, () -> a.delete("jobs/p/config.xml", v1));
        a.write("jobs/p/config.xml", bytes("four"), PersistenceStore.ANY);
    }

    @Test
    void identicalWriteIsNoOp() throws Exception {
        long v1 = a.write("jobs/p/config.xml", bytes("same"), PersistenceStore.ANY);
        assertEquals(v1, b.write("jobs/p/config.xml", bytes("same"), PersistenceStore.ANY));
        assertEquals(v1, a.write("jobs/p/config.xml", bytes("same"), v1));
    }

    @Test
    void deleteThenRecreateGetsNewVersion() throws Exception {
        long v1 = a.write("jobs/p/config.xml", bytes("one"), PersistenceStore.ANY);
        a.delete("jobs/p/config.xml", v1);
        assertNull(b.read("jobs/p/config.xml"));
        long v2 = a.write("jobs/p/config.xml", bytes("one"), PersistenceStore.ABSENT);
        assertNotEquals(v1, v2);
    }

    @Test
    void children() throws Exception {
        a.write("jobs/a/config.xml", bytes("a"), PersistenceStore.ANY);
        a.write("jobs/f/config.xml", bytes("f"), PersistenceStore.ANY);
        a.write("jobs/f/jobs/x/config.xml", bytes("x"), PersistenceStore.ANY);
        a.write("jobs/under_score/config.xml", bytes("u"), PersistenceStore.ANY);
        assertThat(b.children("jobs"), containsInAnyOrder("a", "f", "under_score"));
        assertThat(b.children("jobs/f/jobs"), contains("x"));
        assertTrue(b.children("jobs/a/jobs").isEmpty());
    }

    @Test
    void moveSubtree() throws Exception {
        a.write("jobs/f/config.xml", bytes("f"), PersistenceStore.ANY);
        a.write("jobs/f/jobs/x/config.xml", bytes("x"), PersistenceStore.ANY);
        a.write("jobs/fx/config.xml", bytes("not part of f"), PersistenceStore.ANY);
        a.move("jobs/f", "jobs/g");
        assertNull(b.read("jobs/f/config.xml"));
        assertArrayEquals(bytes("x"), b.read("jobs/g/jobs/x/config.xml").content());
        assertArrayEquals(bytes("not part of f"), b.read("jobs/fx/config.xml").content());
        assertThrows(ConflictException.class, () -> a.move("jobs/g", "jobs/fx"));
    }

    @Test
    void eventsInOrderWithOrigin() throws Exception {
        List<ChangeEvent> seen = new CopyOnWriteArrayList<>();
        try (AutoCloseable sub = b.subscribe(seen::add)) {
            Thread.sleep(1500); // let the listener establish its starting point
            long v = a.write("jobs/p/config.xml", bytes("one"), PersistenceStore.ANY);
            a.write("jobs/p/config.xml", bytes("one"), PersistenceStore.ANY); // no-op, no event
            a.move("jobs/p", "jobs/q");
            a.delete("jobs/q/config.xml", PersistenceStore.ANY);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (seen.size() < 3 && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }
            assertEquals(3, seen.size(), seen::toString);
            assertEquals(
                    new ChangeEvent(ChangeEvent.Kind.WRITTEN, "jobs/p/config.xml", null, v, a.getOrigin()),
                    seen.get(0));
            assertEquals(ChangeEvent.Kind.MOVED, seen.get(1).kind());
            assertEquals("jobs/q/config.xml", seen.get(1).key());
            assertEquals("jobs/p/config.xml", seen.get(1).oldKey());
            assertEquals(ChangeEvent.Kind.DELETED, seen.get(2).kind());
            assertNotEquals(b.getOrigin(), seen.get(0).origin());
        }
    }

    @Test
    void listenerCatchesUpAfterReconnect() throws Exception {
        List<ChangeEvent> seen = new CopyOnWriteArrayList<>();
        try (AutoCloseable sub = b.subscribe(seen::add)) {
            Thread.sleep(1500);
            // kill every connection of the listener; changes made while it is down must still arrive
            try (var c = POSTGRES.createConnection("");
                    var s = c.createStatement()) {
                s.execute(
                        "select pg_terminate_backend(pid) from pg_stat_activity where pid <> pg_backend_pid() and (query like 'listen%' or query like '%jenkins_store_events where seq%')");
            }
            a.write("jobs/r/config.xml", bytes("while down"), PersistenceStore.ANY);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (seen.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }
            assertEquals("jobs/r/config.xml", seen.get(0).key());
        }
    }

    @Test
    void eventCommittedAfterALaterOneIsStillDelivered() throws Exception {
        List<ChangeEvent> seen = new CopyOnWriteArrayList<>();
        try (AutoCloseable sub = b.subscribe(seen::add)) {
            Thread.sleep(1500);
            try (var slow = POSTGRES.createConnection("")) {
                slow.setAutoCommit(false);
                try (var s = slow.createStatement()) {
                    // takes a sequence number now, commits later (as a slow transaction would)
                    s.execute("insert into jenkins_store_events (kind, key, old_key, version, origin)"
                            + " values ('WRITTEN', 'jobs/slow/config.xml', null, 1, 'elsewhere')");
                }
                a.write("jobs/fast/config.xml", bytes("fast"), PersistenceStore.ANY); // a later number, committed first
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (seen.stream().noneMatch(e -> e.key().equals("jobs/fast/config.xml")) && System.nanoTime() < deadline) {
                    Thread.sleep(100);
                }
                slow.commit();
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (seen.stream().noneMatch(e -> e.key().equals("jobs/slow/config.xml")) && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }
            assertTrue(seen.stream().anyMatch(e -> e.key().equals("jobs/slow/config.xml")), "delivered late: " + seen);
        }
    }

    @Test
    void prefixListingsTreatWildcardsLiterally() throws Exception {
        a.write("jobs/a_b/config.xml", bytes("x"), PersistenceStore.ANY);
        a.write("jobs/aXb/other.xml", bytes("y"), PersistenceStore.ANY);
        a.write("jobs/a%b/third.xml", bytes("z"), PersistenceStore.ANY);
        assertEquals(java.util.Set.of("config.xml"), a.children("jobs/a_b"), "_ is not a wildcard");
        assertEquals(java.util.Set.of("third.xml"), a.children("jobs/a%b"), "nor is %");
        a.move("jobs/a_b", "jobs/moved");
        assertEquals(java.util.Set.of("config.xml"), a.children("jobs/moved"));
        assertEquals(java.util.Set.of("other.xml"), a.children("jobs/aXb"), "untouched by the move");
    }

    @Test
    void membership() throws Exception {
        a.startHeartbeat();
        b.startHeartbeat();
        assertThat(a.aliveMembers(), containsInAnyOrder(a.getOrigin(), b.getOrigin()));
        b.close();
        assertThat(a.aliveMembers(), contains(a.getOrigin()));
        b = store();
    }

    @Test
    void capsHoldAcrossControllers() throws Exception {
        a.heartbeat();
        b.heartbeat();
        java.util.Map<String, Integer> caps = java.util.Map.of("cloud", 3, "template", 2);
        assertTrue(a.addWithinCaps(caps, 1));
        assertTrue(b.addWithinCaps(caps, 1));
        assertFalse(b.addWithinCaps(caps, 1), "the template cap (2) holds across both");
        assertTrue(b.addWithinCaps(java.util.Map.of("cloud", 3, "other-template", 2), 1), "the cloud has room for a third");
        assertFalse(a.addWithinCaps(java.util.Map.of("cloud", 3, "other-template", 2), 1), "but not a fourth");
        a.subtractFromCounters(java.util.Set.of("cloud", "template"), 1);
        assertTrue(b.addWithinCaps(caps, 1), "fits once released");
        // a controller that stops heartbeating no longer counts
        try (var c = POSTGRES.createConnection("");
                var s = c.createStatement()) {
            s.execute("update jenkins_members set heartbeat_at = now() - interval '1 hour' where origin = '" + b.getOrigin() + "'");
        }
        assertTrue(a.addWithinCaps(caps, 2), "b's share lapsed with its membership");
    }

    @Test
    void leases() throws Exception {
        a.heartbeat();
        b.heartbeat();
        String key = "jobs/p/running/1.xml";
        long first = a.leases().acquire(key);
        assertTrue(first > 0);
        assertEquals(first, a.leases().acquire(key), "re-acquiring keeps the epoch");
        assertEquals(0, b.leases().acquire(key), "held by a live controller");
        assertTrue(a.leases().holds(key, first));
        assertFalse(b.leases().holds(key, first));

        // a stops heartbeating: its lease lapses and b takes over with a higher epoch
        try (var c = POSTGRES.createConnection("");
                var s = c.createStatement()) {
            s.execute("update jenkins_members set heartbeat_at = now() - interval '1 hour' where origin = '"
                    + a.getOrigin() + "'");
        }
        assertEquals(0, b.leases().acquire(key), "b only just connected: it does not judge a gone yet (as after an outage)");
        assertNull(b.liveOrigins(), "cannot tell yet");
        b.steadyForTests();
        long second = b.leases().acquire(key);
        assertTrue(second > first);
        assertFalse(a.leases().holds(key, first), "fenced");
        assertTrue(b.leases().holds(key, second));
        a.leases().release(key, first); // a stale release does nothing
        assertTrue(b.leases().holds(key, second));
        b.leases().release(key, second);
        assertFalse(b.leases().holds(key, second));
        assertTrue(a.leases().acquire(key) > second, "free again");
    }

    private static jenkins.persistence.ClusterQueue.Record waiting(long id, String job, String origin) {
        return new jenkins.persistence.ClusterQueue.Record(
                id,
                job,
                job,
                "<list/>",
                System.currentTimeMillis() + 60_000,
                jenkins.persistence.ClusterQueue.State.WAITING,
                origin,
                null,
                null,
                0);
    }

    @Test
    void sharedQueue() throws Exception {
        var qa = a.queue();
        var qb = b.queue();
        java.util.List<jenkins.persistence.ClusterQueue.Record> seenByB =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        try (AutoCloseable sub = qb.subscribe(seenByB::add)) {
            long id1 = qa.nextId();
            assertEquals(id1, qa.publish(waiting(id1, "p", a.getOrigin())).id());
            long id2 = qb.nextId();
            assertTrue(id2 > id1, "ids unique across controllers");
            assertEquals(
                    id1,
                    qb.publish(waiting(id2, "p", b.getOrigin())).id(),
                    "folds into the waiting item for the same job");
            assertNull(qb.get(id2));

            assertTrue(qb.claim(id1));
            assertFalse(qa.claim(id1), "claimed once");
            assertTrue(qb.claim(id1), "claiming again is harmless for the claimer");
            assertFalse(qa.cancel(id1), "no longer waiting");
            qb.started(id1, 7);
            assertEquals(
                    jenkins.persistence.ClusterQueue.State.STARTED, qa.get(id1).state());
            assertEquals(7, qa.get(id1).buildNumber());

            long id3 = qa.nextId();
            qa.publish(waiting(id3, "p", a.getOrigin())); // a new item once the previous one left the waiting state
            assertTrue(qa.cancel(id3));
            assertTrue(qa.pending().isEmpty());

            // an item claimed by a controller which is gone is put back
            a.heartbeat();
            b.heartbeat();
            long id4 = qa.nextId();
            qa.publish(waiting(id4, "q", a.getOrigin()));
            assertTrue(qa.claim(id4));
            assertEquals(0, b.reclaimAndPrune(), "a is alive");
            try (var c = POSTGRES.createConnection("");
                    var st = c.createStatement()) {
                st.execute("update jenkins_members set heartbeat_at = now() - interval '1 hour' where origin = '"
                        + a.getOrigin() + "'");
            }
            assertEquals(0, b.reclaimAndPrune(), "b only just connected: no reclaim yet (as after an outage)");
            b.steadyForTests();
            assertEquals(1, b.reclaimAndPrune());
            assertEquals(
                    jenkins.persistence.ClusterQueue.State.WAITING, qb.get(id4).state());
            assertTrue(qb.claim(id4));

            for (int i = 0;
                    i < 100
                            && seenByB.stream()
                                    .noneMatch(r -> r.id() == id4
                                            && r.claimedBy() != null
                                            && r.claimedBy().equals(b.getOrigin()));
                    i++) {
                Thread.sleep(100);
            }
            assertTrue(
                    seenByB.stream()
                            .anyMatch(r -> r.id() == id4 && b.getOrigin().equals(r.claimedBy())),
                    "changes reach the other controller");
        }
    }

    @Test
    void claims() throws Exception {
        assertTrue(a.claim("job", "trigger", 100));
        assertFalse(b.claim("job", "trigger", 100));
        assertFalse(a.claim("job", "trigger", 100));
        assertTrue(b.claim("job", "trigger", 101));
        assertTrue(b.claim("other", "trigger", 100));
    }

    @Test
    void rendezvousOwnership() {
        java.util.List<String> three = List.of("m1", "m2", "m3");
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (int i = 0; i < 300; i++) {
            String name = "job" + i;
            String owner = PostgresTriggerCoordinator.ownerOf(name, three);
            counts.merge(owner, 1, Integer::sum);
            // removing a member only moves the items it owned
            for (String gone : three) {
                java.util.List<String> two = new java.util.ArrayList<>(three);
                two.remove(gone);
                if (!owner.equals(gone)) {
                    assertEquals(owner, PostgresTriggerCoordinator.ownerOf(name, two));
                }
            }
        }
        for (String m : three) {
            assertTrue(counts.get(m) > 60, counts::toString);
        }
        assertEquals("folder", PostgresTriggerCoordinator.topLevel("folder/sub/job"));
    }
}
