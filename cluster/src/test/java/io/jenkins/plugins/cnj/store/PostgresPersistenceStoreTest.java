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
        try (var c = POSTGRES.createConnection(""); var s = c.createStatement()) {
            s.execute("drop table if exists jenkins_store, jenkins_store_events");
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
        return new PostgresPersistenceStore(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
                key -> PostgresPersistenceStoreProvider.ITEM_CONFIG.matcher(key).matches());
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void owns() {
        assertTrue(a.owns("jobs/p/config.xml"));
        assertTrue(a.owns("jobs/f/jobs/p/config.xml"));
        assertTrue(a.owns("jobs/mb/branches/main/config.xml"));
        assertFalse(a.owns("config.xml"));
        assertFalse(a.owns("jobs/p/builds/1/build.xml"));
        assertFalse(a.owns("jobs/p/nextBuildNumber"));
        assertFalse(a.owns("jobs/mb/state.xml"));
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
        ConflictException c = assertThrows(ConflictException.class, () -> a.write("jobs/p/config.xml", bytes("three"), v1));
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
            assertEquals(new ChangeEvent(ChangeEvent.Kind.WRITTEN, "jobs/p/config.xml", null, v, a.getOrigin()), seen.get(0));
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
            try (var c = POSTGRES.createConnection(""); var s = c.createStatement()) {
                s.execute("select pg_terminate_backend(pid) from pg_stat_activity where pid <> pg_backend_pid() and (query like 'listen%' or query like '%jenkins_store_events where seq%')");
            }
            a.write("jobs/r/config.xml", bytes("while down"), PersistenceStore.ANY);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (seen.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }
            assertEquals("jobs/r/config.xml", seen.get(0).key());
        }
    }
}
