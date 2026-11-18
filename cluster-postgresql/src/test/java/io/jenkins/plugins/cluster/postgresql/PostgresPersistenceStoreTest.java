package io.jenkins.plugins.cluster.postgresql;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.cluster.spi.ClusterQueue;
import io.jenkins.plugins.cluster.spi.LaunchLeases;
import io.jenkins.plugins.cluster.spi.LeaseLostException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import jenkins.persistence.ConflictException;
import jenkins.persistence.PersistenceStore;
import jenkins.persistence.PersistenceStore.ChangeEvent;
import jenkins.persistence.PersistenceStore.Precondition;
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
                    "drop table if exists jenkins_store, jenkins_store_events, jenkins_members, jenkins_trigger_claims, jenkins_build_leases,"
                            + " jenkins_queue, jenkins_launch_leases, jenkins_schema_migrations");
        }
        a = store();
        b = store();
    }

    /** How long until {@code observer} takes {@code other} for gone, at most {@code limitMillis} (then -1). */
    private static long goneAfter(PostgresPersistenceStore observer, PostgresPersistenceStore other, long limitMillis)
            throws Exception {
        long start = System.nanoTime();
        while (System.nanoTime() - start < java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(limitMillis)) {
            if (!observer.aliveMembers().contains(other.getOrigin())) {
                return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            }
            Thread.sleep(100);
        }
        return -1;
    }

    @Test
    void aDeadProcessIsGoneSoonButAPausedOneOnlyAfterTheTimeout() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(PostgresPersistenceStore.SESSION_LOCKS);
        a.startHeartbeat();
        b.startHeartbeat();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (!b.holdsLivenessLockForTests() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        b.heartbeat(); // its row names the lock
        b.stopHeartbeatForTests(true); // the process died: its connections close, freeing the lock
        long dead = goneAfter(a, b, PostgresPersistenceStore.MEMBER_TIMEOUT_MILLIS + 5000);
        assertTrue(dead >= 0 && dead < PostgresPersistenceStore.FAST_DEAD_MILLIS + 1500, "gone after " + dead + " ms");
        b.close();
        b = store();
        b.startHeartbeat();
        deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (!b.holdsLivenessLockForTests() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        b.heartbeat();
        b.stopHeartbeatForTests(false); // paused, or cut off: its session (and lock) lives on
        long paused = goneAfter(a, b, PostgresPersistenceStore.MEMBER_TIMEOUT_MILLIS + 5000);
        assertTrue(
                paused
                        >= PostgresPersistenceStore.MEMBER_TIMEOUT_MILLIS
                                - PostgresPersistenceStore.HEARTBEAT_MILLIS
                                - 500,
                "a paused controller is not taken for gone early: " + paused + " ms");
    }

    @Test
    void aControllerWhoseLockIsGoneStopsActingForItsLeasesFirst() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(PostgresPersistenceStore.SESSION_LOCKS);
        b.startHeartbeat();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (!b.holdsLivenessLockForTests() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        b.heartbeat();
        b.stopHeartbeatForTests(true);
        assertFalse(b.mayHaveLapsed(), "just heartbeated");
        Thread.sleep(PostgresPersistenceStore.FAST_DEAD_MILLIS - PostgresPersistenceStore.HEARTBEAT_MILLIS + 300);
        assertTrue(b.mayHaveLapsed(), "before the others can take it for gone");
    }

    @AfterEach
    void tearDown() {
        a.close();
        b.close();
    }

    private static PostgresPersistenceStore store() throws Exception {
        return new PostgresPersistenceStore(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void readsBeforeTheStoreIsSetUp() throws Exception {
        a.write("cluster/plugins.json", bytes("{}"), PersistenceStore.ABSENT);
        assertArrayEquals(
                bytes("{}"),
                PostgresPersistenceStore.readEarly(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), "cluster/plugins.json"));
        assertNull(PostgresPersistenceStore.readEarly(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), "cluster/none"));
        try (var c = POSTGRES.createConnection("");
                var s = c.createStatement()) {
            s.execute("drop table jenkins_store");
        }
        assertNull(
                PostgresPersistenceStore.readEarly(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), "cluster/plugins.json"),
                "no controller has started yet");
    }

    @Test
    void controllersStartingAtOnceCreateTheSchemaOnce() throws Exception {
        a.close();
        b.close();
        try (var c = POSTGRES.createConnection("");
                var st = c.createStatement()) {
            st.execute(
                    "drop table if exists jenkins_store, jenkins_store_events, jenkins_members, jenkins_trigger_claims,"
                            + " jenkins_build_leases, jenkins_queue, jenkins_launch_leases, jenkins_schema_migrations");
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
    void aStoreOfTheEarlierLayoutIsRefusedNotMigrated() throws Exception {
        a.close();
        b.close();
        try (var c = POSTGRES.createConnection("");
                var st = c.createStatement()) {
            // as an earlier version left it: build data keyed by item path, and no record of the layout
            st.execute("delete from jenkins_store where key = '" + PostgresPersistenceStore.LAYOUT_KEY + "'");
            st.execute("insert into jenkins_store (key, content, sha256, version, updated_by)"
                    + " values ('jobs/p/builds/1/build.xml', 'x', 'x', 1, 'old')");
        }
        IllegalStateException e = assertThrows(IllegalStateException.class, PostgresPersistenceStoreTest::store);
        assertTrue(e.getMessage().contains("earlier layout"), e.getMessage());
        try (var c = POSTGRES.createConnection("");
                var st = c.createStatement()) {
            st.execute("delete from jenkins_store");
        }
        a = store(); // an emptied database is used afresh
        b = store();
        assertNotNull(a.read(PostgresPersistenceStore.LAYOUT_KEY));
        assertEquals(
                io.jenkins.plugins.cluster.spi.ClusterMember.PROTOCOL,
                a.protocolFloor(),
                "earlier versions refuse to join");
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
        long fVersion = a.read("jobs/f/config.xml").version();
        List<PersistenceStore.Moved> moved = a.move("jobs/f", "jobs/g", List.of());
        assertEquals(
                List.of("jobs/f/config.xml", "jobs/f/jobs/x/config.xml"),
                moved.stream().map(PersistenceStore.Moved::from).toList());
        assertEquals(fVersion, moved.get(0).fromVersion());
        assertEquals(b.read("jobs/g/config.xml").version(), moved.get(0).version(), "the version it has now");
        assertNull(b.read("jobs/f/config.xml"));
        assertArrayEquals(bytes("x"), b.read("jobs/g/jobs/x/config.xml").content());
        assertArrayEquals(bytes("not part of f"), b.read("jobs/fx/config.xml").content());
        assertThrows(ConflictException.class, () -> a.move("jobs/g", "jobs/fx", List.of()));
    }

    @Test
    void moveChecksItsPreconditionsInTheSameTransaction() throws Exception {
        long item = a.write("jobs/a/config.xml", bytes("a"), PersistenceStore.ABSENT);
        a.write("jobs/a/jobs/x/config.xml", bytes("x"), PersistenceStore.ABSENT);
        long folder = a.write("jobs/f/config.xml", bytes("f"), PersistenceStore.ABSENT);
        List<Precondition> into = List.of(
                new Precondition("jobs/a/config.xml", item),
                new Precondition("jobs/f/config.xml", PersistenceStore.PRESENT),
                new Precondition("jobs/f/jobs/a/config.xml", PersistenceStore.ABSENT));
        // the source changed since it was read here
        b.write("jobs/a/config.xml", bytes("a2"), item);
        ConflictException stale = assertThrows(ConflictException.class, () -> a.move("jobs/a", "jobs/f/jobs/a", into));
        assertEquals("jobs/a/config.xml", stale.getKey());
        assertNotNull(a.read("jobs/a/jobs/x/config.xml"), "nothing moved");
        long now = a.read("jobs/a/config.xml").version();
        // the destination's parent is gone (deleted, or moved into the item being moved)
        a.delete("jobs/f/config.xml", folder);
        ConflictException parent = assertThrows(
                ConflictException.class,
                () -> a.move(
                        "jobs/a",
                        "jobs/f/jobs/a",
                        List.of(
                                new Precondition("jobs/a/config.xml", now),
                                new Precondition("jobs/f/config.xml", PersistenceStore.PRESENT))));
        assertEquals("jobs/f/config.xml", parent.getKey());
        a.write("jobs/f/config.xml", bytes("f"), PersistenceStore.ABSENT);
        // something is at the destination
        long there = a.write("jobs/f/jobs/a/config.xml", bytes("there"), PersistenceStore.ABSENT);
        assertThrows(
                ConflictException.class,
                () -> a.move("jobs/a", "jobs/f/jobs/a", List.of(new Precondition("jobs/a/config.xml", now))));
        a.delete("jobs/f/jobs/a/config.xml", there);
        List<PersistenceStore.Moved> moved = a.move(
                "jobs/a",
                "jobs/f/jobs/a",
                List.of(
                        new Precondition("jobs/a/config.xml", now),
                        new Precondition("jobs/f/config.xml", PersistenceStore.PRESENT),
                        new Precondition("jobs/f/jobs/a/config.xml", PersistenceStore.ABSENT)));
        assertEquals(2, moved.size());
        assertArrayEquals(bytes("a2"), b.read("jobs/f/jobs/a/config.xml").content());
        assertArrayEquals(bytes("x"), b.read("jobs/f/jobs/a/jobs/x/config.xml").content());
    }

    @Test
    void writesFencedByALease() throws Exception {
        String lease = "_items/u/running/1";
        long epoch = a.leases().acquire(lease);
        assertTrue(
                a.leases().writeIfHeld(lease, epoch, "_items/u/builds/1/build.xml", bytes("mine"), PersistenceStore.ANY)
                        > 0);
        assertThrows(
                LeaseLostException.class,
                () -> b.leases()
                        .writeIfHeld(
                                lease, epoch, "_items/u/builds/1/build.xml", bytes("not b's"), PersistenceStore.ANY));
        a.leases().release(lease, epoch);
        assertThrows(
                LeaseLostException.class,
                () -> a.leases()
                        .writeIfHeld(lease, epoch, "_items/u/builds/1/build.xml", bytes("lost"), PersistenceStore.ANY));
        assertArrayEquals(bytes("mine"), b.read("_items/u/builds/1/build.xml").content());
    }

    @Test
    void foldsCompareAndSet() throws Exception {
        ClusterQueue q = a.queue();
        long id = q.nextId();
        q.publish(new ClusterQueue.Record(
                id,
                "uuid-p",
                "p",
                "p",
                "<actions/>",
                System.currentTimeMillis() + 60_000,
                ClusterQueue.State.WAITING,
                a.getOrigin(),
                null,
                null,
                0));
        assertTrue(q.update(id, "<actions/>", "<actions><first/></actions>", System.currentTimeMillis()));
        assertFalse(
                b.queue().update(id, "<actions/>", "<actions><second/></actions>", System.currentTimeMillis()),
                "read before the first: merge again");
        assertEquals("<actions><first/></actions>", q.get(id).actions());
        // a request which must run on b pins the item, unless it is pinned already
        assertTrue(b.queue()
                .update(
                        id,
                        "<actions><first/></actions>",
                        "<actions><second/></actions>",
                        System.currentTimeMillis(),
                        b.getOrigin()));
        assertEquals(b.getOrigin(), q.get(id).pinnedTo());
        assertTrue(q.update(
                id,
                "<actions><second/></actions>",
                "<actions><third/></actions>",
                System.currentTimeMillis(),
                a.getOrigin()));
        assertEquals(b.getOrigin(), q.get(id).pinnedTo());
        assertEquals("<actions><third/></actions>", q.get(id).actions());
    }

    @Test
    void claimsOnlyAsPinnedWhenRead() throws Exception {
        ClusterQueue q = a.queue();
        long id = q.nextId();
        q.publish(new ClusterQueue.Record(
                id,
                "uuid-pins",
                "pins",
                "pins",
                "<actions/>",
                System.currentTimeMillis() + 60_000,
                ClusterQueue.State.WAITING,
                a.getOrigin(),
                null,
                null,
                0));
        // a request which must run on b was folded in after a read the item unpinned
        assertTrue(b.queue()
                .update(id, "<actions/>", "<actions><b/></actions>", System.currentTimeMillis(), b.getOrigin()));
        assertFalse(q.claim(id, null));
        assertEquals(ClusterQueue.State.WAITING, q.get(id).state());
        assertTrue(b.queue().claim(id, b.getOrigin()));
        assertEquals(b.getOrigin(), q.get(id).claimedBy());
    }

    @Test
    void idsTakenAhead() throws Exception {
        long before = a.queue().nextId();
        long[] ids = a.queue().nextIds(64);
        assertEquals(64, ids.length);
        assertEquals(64, java.util.Arrays.stream(ids).distinct().count());
        assertTrue(java.util.Arrays.stream(ids).allMatch(id -> id > before));
        assertTrue(b.queue().nextId() > java.util.Arrays.stream(ids).max().getAsLong(), "not handed out again");
    }

    @Test
    void eventsInOrderWithOrigin() throws Exception {
        List<ChangeEvent> seen = new CopyOnWriteArrayList<>();
        try (AutoCloseable sub = b.subscribe(seen::add)) {
            Thread.sleep(1500); // let the listener establish its starting point
            long v = a.write("jobs/p/config.xml", bytes("one"), PersistenceStore.ANY);
            a.write("jobs/p/config.xml", bytes("one"), PersistenceStore.ANY); // no-op, no event
            a.move("jobs/p", "jobs/q", List.of());
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
                while (seen.stream().noneMatch(e -> e.key().equals("jobs/fast/config.xml"))
                        && System.nanoTime() < deadline) {
                    Thread.sleep(100);
                }
                slow.commit();
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (seen.stream().noneMatch(e -> e.key().equals("jobs/slow/config.xml"))
                    && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }
            assertTrue(seen.stream().anyMatch(e -> e.key().equals("jobs/slow/config.xml")), "delivered late: " + seen);
        }
    }

    private static void await(String what, java.util.concurrent.Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.call()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out waiting for " + what);
            }
            Thread.sleep(50);
        }
    }

    private static boolean seenKey(List<ChangeEvent> seen, String key) {
        return seen.stream().anyMatch(e -> e.key().equals(key));
    }

    /** Opens a transaction as the store's would be (same application name) which takes an event number now. */
    private static java.sql.Connection slowTransaction(String key) throws Exception {
        java.sql.Connection slow = POSTGRES.createConnection("");
        slow.setAutoCommit(false);
        try (var s = slow.createStatement()) {
            s.execute("set application_name = '" + PostgresPersistenceStore.APPLICATION_NAME + "'");
            s.execute("insert into jenkins_store_events (kind, key, old_key, version, origin)" + " values ('WRITTEN', '"
                    + key + "', null, 1, 'elsewhere')");
        }
        return slow;
    }

    private static void killListeners() throws Exception {
        try (var c = POSTGRES.createConnection("");
                var s = c.createStatement()) {
            s.execute("select pg_terminate_backend(pid) from pg_stat_activity where pid <> pg_backend_pid()"
                    + " and (query like 'listen%' or query like '%jenkins_store_events where seq%'"
                    + " or query like '%pg_stat_activity%')");
        }
    }

    @Test
    void anEventOfATransactionOpenAsTheListenerSubscribesIsDelivered() throws Exception {
        List<ChangeEvent> seen = new CopyOnWriteArrayList<>();
        try (java.sql.Connection slow = slowTransaction("jobs/slow/config.xml")) {
            a.write("jobs/fast/config.xml", bytes("fast"), PersistenceStore.ANY); // a later number, committed first
            try (AutoCloseable sub = b.subscribe(seen::add)) {
                // whatever is read from now on was changed before, or has its event delivered
                slow.commit();
                await(
                        "the earlier event, committed after the subscription",
                        () -> seenKey(seen, "jobs/slow/config.xml"));
                assertFalse(seenKey(seen, "jobs/fast/config.xml"), "committed before: " + seen);
            }
        }
    }

    @Test
    void aSkippedNumberIsWaitedForWhileATransactionThatMayCommitItIsOpen() throws Exception {
        long hole = PostgresPersistenceStore.HOLE_MILLIS;
        PostgresPersistenceStore.HOLE_MILLIS = 1000;
        try {
            List<ChangeEvent> seen = new CopyOnWriteArrayList<>();
            try (AutoCloseable sub = b.subscribe(seen::add);
                    java.sql.Connection slow = slowTransaction("jobs/slow/config.xml")) {
                a.write("jobs/fast/config.xml", bytes("fast"), PersistenceStore.ANY);
                await("the later event", () -> seenKey(seen, "jobs/fast/config.xml"));
                long skippedAt = System.currentTimeMillis();
                // well past the time after which a number was forgotten, its transaction taken for rolled back
                await("the slow transaction to outlast the wait", () -> System.currentTimeMillis() - skippedAt > 3000);
                slow.commit();
                await("the earlier event, committed late", () -> seenKey(seen, "jobs/slow/config.xml"));
            }
        } finally {
            PostgresPersistenceStore.HOLE_MILLIS = hole;
        }
    }

    @Test
    void aListenerAsksForACatchUpWhenTheEventsAfterTheLastOneDeliveredMayBeGone() throws Exception {
        List<String> catchUps = new CopyOnWriteArrayList<>();
        b.resyncNeeded = catchUps::add;
        List<ChangeEvent> seen = new CopyOnWriteArrayList<>();
        List<String> restarts = new CopyOnWriteArrayList<>();
        b.storeWentBack = restarts::add;
        try (AutoCloseable sub = b.subscribe(seen::add)) {
            try (var c = POSTGRES.createConnection("");
                    var s = c.createStatement()) {
                // made long ago (as the last change of a quiet cluster would be), so prunable by now
                s.execute(
                        "insert into jenkins_store_events (kind, key, old_key, version, origin, at)"
                                + " values ('WRITTEN', 'jobs/one/config.xml', null, 1, 'elsewhere', now() - interval '2 days')");
            }
            await("the first event", () -> seenKey(seen, "jobs/one/config.xml"));
            // pruned while the listener was away: the events until now are gone
            try (var c = POSTGRES.createConnection("");
                    var s = c.createStatement()) {
                s.execute("delete from jenkins_store_events");
            }
            killListeners();
            a.write("jobs/two/config.xml", bytes("two"), PersistenceStore.ANY);
            await("a catch-up to be asked for", () -> !catchUps.isEmpty());
            await("the events still there to be delivered", () -> seenKey(seen, "jobs/two/config.xml"));
            assertTrue(catchUps.get(0).contains("no longer has the last change event"), catchUps::toString);
            assertEquals(List.of(), restarts, "pruned, not gone back: no restart");
        }
    }

    @Test
    void aListenerAsksForACatchUpWhenTheStoreWasRestoredToAnEarlierPoint() throws Exception {
        List<String> catchUps = new CopyOnWriteArrayList<>();
        b.resyncNeeded = catchUps::add;
        List<String> restarts = new CopyOnWriteArrayList<>();
        b.storeWentBack = restarts::add;
        List<ChangeEvent> seen = new CopyOnWriteArrayList<>();
        a.write("jobs/before/config.xml", bytes("before"), PersistenceStore.ANY);
        try (AutoCloseable sub = b.subscribe(seen::add)) {
            for (int i = 0; i < 5; i++) {
                a.write("jobs/after" + i + "/config.xml", bytes("after"), PersistenceStore.ANY);
            }
            await("the events", () -> seenKey(seen, "jobs/after4/config.xml"));
            // as a point-in-time restore to before them: the events and their numbers go back
            try (var c = POSTGRES.createConnection("");
                    var s = c.createStatement()) {
                s.execute("delete from jenkins_store_events where key like 'jobs/after%'");
                s.execute("select setval('jenkins_store_events_seq_seq', (select max(seq) from jenkins_store_events))");
            }
            killListeners();
            await("a restart to be asked for", () -> !restarts.isEmpty());
            assertTrue(restarts.get(0).contains("went back"), restarts::toString);
            assertTrue(!catchUps.isEmpty(), "and a catch-up meanwhile");
            seen.clear();
            a.write("jobs/restored/config.xml", bytes("restored"), PersistenceStore.ANY);
            await("events to be delivered again", () -> seenKey(seen, "jobs/restored/config.xml"));
        }
    }

    @Test
    void aStoreRestoredAndChangedSinceBeyondWhereItWasIsSeenToHaveGoneBack() throws Exception {
        List<String> restarts = new CopyOnWriteArrayList<>();
        b.storeWentBack = restarts::add;
        b.resyncNeeded = x -> {};
        List<ChangeEvent> seen = new CopyOnWriteArrayList<>();
        a.write("jobs/before/config.xml", bytes("before"), PersistenceStore.ANY);
        try (AutoCloseable sub = b.subscribe(seen::add)) {
            for (int i = 0; i < 3; i++) {
                a.write("jobs/after" + i + "/config.xml", bytes("after"), PersistenceStore.ANY);
            }
            await("the events", () -> seenKey(seen, "jobs/after2/config.xml"));
            try (var c = POSTGRES.createConnection("");
                    var s = c.createStatement()) {
                s.execute("delete from jenkins_store_events where key like 'jobs/after%'");
                s.execute("select setval('jenkins_store_events_seq_seq', (select max(seq) from jenkins_store_events))");
                // the restored database is written to before this listener reconnects: its numbers pass where they were
                for (int i = 0; i < 6; i++) {
                    s.execute("insert into jenkins_store_events (kind, key, old_key, version, origin)"
                            + " values ('WRITTEN', 'jobs/new" + i + "/config.xml', null, 1, 'elsewhere')");
                }
            }
            killListeners();
            await("a restart to be asked for", () -> !restarts.isEmpty());
        }
    }

    @Test
    void wentBackOrPruned() {
        long day = TimeUnit.DAYS.toMillis(1);
        String recent = "jobs/p/config.xml@7@" + (10 * day);
        assertTrue(PostgresPersistenceStore.wentBack(recent, "jobs/q/config.xml@3@1", 5, 9, 10 * day), "renumbered");
        assertTrue(PostgresPersistenceStore.wentBack(recent, null, 5, 4, 10 * day), "numbers lower");
        assertTrue(PostgresPersistenceStore.wentBack(recent, null, 5, 9, 10 * day + 1000), "too recent to be pruned");
        assertFalse(PostgresPersistenceStore.wentBack(recent, null, 5, 9, 12 * day), "pruned");
    }

    @Test
    void aListenerThatCouldNotReadWhereEventsStartAsksForACatchUp() throws Exception {
        List<String> catchUps = new CopyOnWriteArrayList<>();
        PostgresPersistenceStore c =
                new PostgresPersistenceStore(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()) {
                    @Override
                    Baseline baseline(java.sql.Connection connection) throws java.sql.SQLException {
                        if (catchUps.isEmpty() && !failed) {
                            failed = true;
                            throw new java.sql.SQLException("down");
                        }
                        return super.baseline(connection);
                    }

                    private boolean failed;
                };
        c.resyncNeeded = catchUps::add;
        try (AutoCloseable sub = c.subscribe(e -> {})) {
            await("a catch-up to be asked for", () -> !catchUps.isEmpty());
            assertTrue(catchUps.get(0).contains("started after"), catchUps::toString);
        } finally {
            c.close();
        }
    }

    @Test
    void childrenSkipOverEverythingBelowEachChild() throws Exception {
        // names sorting just before and after '/' (".", "-", "0"), nested keys, a key equal to a child's name
        for (String key : List.of(
                "jobs/a/config.xml",
                "jobs/a/builds/1/build.xml",
                "jobs/a/builds/2/build.xml",
                "jobs/a.b/config.xml",
                "jobs/a-c/config.xml",
                "jobs/a0/config.xml",
                "jobs/b/jobs/c/config.xml",
                "jobs/z",
                "jobsx/config.xml",
                "jobs.y/config.xml")) {
            a.write(key, bytes("x"), PersistenceStore.ANY);
        }
        assertEquals(java.util.Set.of("a", "a.b", "a-c", "a0", "b", "z"), a.children("jobs"));
        assertEquals(java.util.Set.of("1", "2"), a.children("jobs/a/builds"));
        assertEquals(java.util.Set.of(), a.children("nothing"));
        for (int i = 0; i < 500; i++) {
            a.write("jobs/many/builds/" + i + "/build.xml", bytes("x"), PersistenceStore.ANY);
        }
        assertEquals(500, a.children("jobs/many/builds").size());
        assertTrue(a.children("jobs").contains("many"));
    }

    @Test
    void deleteAllDeletesBelowAPrefixWithEvents() throws Exception {
        for (int i = 0; i < 50; i++) {
            a.write("jobs/gone/builds/" + i + "/build.xml", bytes("x"), PersistenceStore.ANY);
        }
        a.write("jobs/gone/config.xml", bytes("kept"), PersistenceStore.ANY);
        a.write("jobs/gone2/builds/1/build.xml", bytes("kept"), PersistenceStore.ANY);
        java.util.List<PersistenceStore.ChangeEvent> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        b.subscribe(seen::add);
        Thread.sleep(1500); // let the listener establish its starting point
        assertEquals(50, a.deleteAll("jobs/gone/builds"));
        assertTrue(a.children("jobs/gone/builds").isEmpty());
        assertNotNull(a.read("jobs/gone/config.xml"));
        assertNotNull(a.read("jobs/gone2/builds/1/build.xml"), "a sibling with a longer name is not below the prefix");
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (seen.stream()
                                .filter(e -> e.kind() == PersistenceStore.ChangeEvent.Kind.DELETED)
                                .count()
                        < 50
                && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertEquals(
                50,
                seen.stream()
                        .filter(e -> e.kind() == PersistenceStore.ChangeEvent.Kind.DELETED)
                        .count(),
                "an event each");
    }

    @Test
    void prefixListingsTreatWildcardsLiterally() throws Exception {
        a.write("jobs/a_b/config.xml", bytes("x"), PersistenceStore.ANY);
        a.write("jobs/aXb/other.xml", bytes("y"), PersistenceStore.ANY);
        a.write("jobs/a%b/third.xml", bytes("z"), PersistenceStore.ANY);
        assertEquals(java.util.Set.of("config.xml"), a.children("jobs/a_b"), "_ is not a wildcard");
        assertEquals(java.util.Set.of("third.xml"), a.children("jobs/a%b"), "nor is %");
        a.move("jobs/a_b", "jobs/moved", List.of());
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
        assertTrue(
                b.addWithinCaps(java.util.Map.of("cloud", 3, "other-template", 2), 1),
                "the cloud has room for a third");
        assertFalse(a.addWithinCaps(java.util.Map.of("cloud", 3, "other-template", 2), 1), "but not a fourth");
        a.subtractFromCounters(java.util.Set.of("cloud", "template"), 1);
        assertTrue(b.addWithinCaps(caps, 1), "fits once released");
        // a controller that stops heartbeating no longer counts
        try (var c = POSTGRES.createConnection("");
                var s = c.createStatement()) {
            s.execute("update jenkins_members set heartbeat_at = now() - interval '1 hour' where origin = '"
                    + b.getOrigin() + "'");
        }
        assertTrue(a.addWithinCaps(caps, 2), "b's share lapsed with its membership");
    }

    @Test
    void leases() throws Exception {
        a.heartbeat();
        b.heartbeat();
        String key = "_items/u/running/1";
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
        assertEquals(
                0, b.leases().acquire(key), "b only just connected: it does not judge a gone yet (as after an outage)");
        assertFalse(b.canJudgeLiveness(), "cannot tell yet");
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

    private static ClusterQueue.Record waiting(long id, String job, String origin) {
        return new ClusterQueue.Record(
                id,
                "uuid-" + job,
                job,
                job,
                "<list/>",
                System.currentTimeMillis() + 60_000,
                ClusterQueue.State.WAITING,
                origin,
                null,
                null,
                0);
    }

    @Test
    void sharedQueue() throws Exception {
        var qa = a.queue();
        var qb = b.queue();
        java.util.List<ClusterQueue.Record> seenByB = new java.util.concurrent.CopyOnWriteArrayList<>();
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

            assertTrue(qb.claim(id1, null));
            assertFalse(qa.claim(id1, null), "claimed once");
            assertTrue(qb.claim(id1, null), "claiming again is harmless for the claimer");
            assertFalse(qa.cancel(id1), "no longer waiting, and claimed by another");
            assertTrue(qb.started(id1, 7));
            assertFalse(qa.started(id1, 7), "not claimed by a");
            assertTrue(qb.started(id1, 7), "recorded again by the same build: a retry");
            assertFalse(qb.started(id1, 8), "not by another");
            assertEquals(ClusterQueue.State.STARTED, qa.get(id1).state());
            assertEquals(7, qa.get(id1).buildNumber());

            long id3 = qa.nextId();
            qa.publish(waiting(id3, "p", a.getOrigin())); // a new item once the previous one left the waiting state
            assertTrue(qa.claim(id3, null));
            assertFalse(qb.cancel(id3), "claimed by another");
            assertTrue(qa.cancel(id3), "claimed here but not started: its copy here was cancelled");
            assertTrue(qa.pending().isEmpty());

            // an item claimed by a controller which is gone is put back
            a.heartbeat();
            b.heartbeat();
            long id4 = qa.nextId();
            qa.publish(waiting(id4, "q", a.getOrigin()));
            assertTrue(qa.claim(id4, null));
            assertEquals(0, b.reclaimAndPrune(), "a is alive");
            try (var c = POSTGRES.createConnection("");
                    var st = c.createStatement()) {
                st.execute("update jenkins_members set heartbeat_at = now() - interval '1 hour' where origin = '"
                        + a.getOrigin() + "'");
            }
            assertEquals(0, b.reclaimAndPrune(), "b only just connected: no reclaim yet (as after an outage)");
            b.steadyForTests();
            assertEquals(1, b.reclaimAndPrune());
            assertEquals(ClusterQueue.State.WAITING, qb.get(id4).state());
            assertFalse(
                    qa.started(id4, 3),
                    "a's claim was put back: a build a starts for it now is not recorded, and stopped");
            assertTrue(qb.claim(id4, null));

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

            // an item claimed by the gone controller while an equal one waits: that one stands for it
            long id8 = qa.nextId();
            qa.publish(waiting(id8, "s", a.getOrigin()));
            assertTrue(qa.claim(id8, null));
            long id9 = qb.nextId();
            assertEquals(id9, qb.publish(waiting(id9, "s", b.getOrigin())).id());
            assertEquals(0, b.reclaimAndPrune(), "none put back");
            assertEquals(ClusterQueue.State.CANCELLED, qb.get(id8).state());
            assertEquals(ClusterQueue.State.WAITING, qb.get(id9).state());
            assertTrue(qb.cancel(id9));

            // an item a gone controller recorded as started, with a number but nothing else (it died before the
            // build's placeholder): listed, and queued again
            long id5 = qa.nextId();
            qa.publish(waiting(id5, "r", a.getOrigin()));
            assertTrue(qa.claim(id5, null));
            assertTrue(qa.started(id5, 9));
            assertEquals(
                    java.util.List.of(new PostgresPersistenceStore.StartedItem(id5, "uuid-r", "r", 9)),
                    b.startedByGone().stream().filter(i -> i.id() == id5).toList());
            assertTrue(b.startedByGone().stream().noneMatch(i -> i.id() == id1), "started by a live controller");
            b.requeueStarted(id5);
            assertEquals(ClusterQueue.State.WAITING, qb.get(id5).state());
            assertEquals(0, qb.get(id5).buildNumber());

            // the started items of a deleted item are forgotten, by its identity: they are never queued again
            long id6 = qa.nextId();
            qa.publish(waiting(id6, "d", a.getOrigin()));
            assertTrue(qa.claim(id6, null));
            assertTrue(qa.started(id6, 1));
            long id7 = qa.nextId();
            qa.publish(waiting(id7, "dx", a.getOrigin()));
            assertTrue(qa.claim(id7, null));
            assertTrue(qa.started(id7, 1));
            b.cancelStartedOf("uuid-d");
            assertEquals(ClusterQueue.State.CANCELLED, qb.get(id6).state());
            assertEquals(ClusterQueue.State.STARTED, qb.get(id7).state(), "another item");
            assertTrue(b.startedByGone().stream().noneMatch(i -> i.id() == id6));
        }
    }

    @Test
    void launchLeases() throws Exception {
        a.heartbeat();
        b.heartbeat();
        LaunchLeases la = a.launchLeases();
        LaunchLeases lb = b.launchLeases();
        assertNull(la.holder("ssh-1"));
        long first = la.acquire("ssh-1");
        assertTrue(first > 0);
        assertEquals(first, la.acquire("ssh-1"), "kept by its holder");
        assertEquals(0, lb.acquire("ssh-1"), "held by a live controller");
        assertEquals(new LaunchLeases.Holder(a.getOrigin(), first), lb.holder("ssh-1"));
        lb.release("ssh-1", first); // not b's
        assertEquals(a.getOrigin(), lb.holder("ssh-1").origin());
        // a is gone: b takes it over, with a higher epoch, once it can judge
        try (var c = POSTGRES.createConnection("");
                var s = c.createStatement()) {
            s.execute("update jenkins_members set heartbeat_at = now() - interval '1 hour' where origin = '"
                    + a.getOrigin() + "'");
        }
        assertEquals(0, lb.acquire("ssh-1"), "b only just connected: it does not judge a gone yet");
        b.steadyForTests();
        long second = lb.acquire("ssh-1");
        assertTrue(second > first);
        assertEquals(
                new LaunchLeases.Holder(b.getOrigin(), second),
                la.holder("ssh-1"),
                "alive or not, the holder is known");
        la.release("ssh-1", first); // a stale release does nothing
        assertEquals(b.getOrigin(), la.holder("ssh-1").origin());
        lb.release("ssh-1", second);
        assertNull(la.holder("ssh-1"));
    }

    @Test
    void runningPlaceholdersAreThoseOfItems() throws Exception {
        a.write("_items/u/running/3.xml", bytes("p"), PersistenceStore.ABSENT);
        a.write("_items/u/running/3.stop", bytes("s"), PersistenceStore.ABSENT);
        a.write("_items/u/builds/2/build.xml", bytes("b"), PersistenceStore.ABSENT);
        a.write("jobs/x/running/1.xml", bytes("routed"), PersistenceStore.ABSENT);
        assertEquals(
                java.util.Set.of("_items/u/running/3.xml"),
                b.runningPlaceholders().keySet());
    }

    @Test
    void claims() throws Exception {
        assertTrue(a.claim("job", "trigger", 100));
        assertFalse(b.claim("job", "trigger", 100));
        assertFalse(a.claim("job", "trigger", 100));
        assertTrue(b.claim("job", "trigger", 101));
        assertTrue(b.claim("other", "trigger", 100));
    }
}
