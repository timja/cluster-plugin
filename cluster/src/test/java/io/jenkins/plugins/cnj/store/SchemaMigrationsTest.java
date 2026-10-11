package io.jenkins.plugins.cnj.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import jenkins.persistence.PersistenceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Background schema migrations (Target 15): backfilled in batches while writes go on, resumed by another controller. */
@Testcontainers
class SchemaMigrationsTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    private PostgresPersistenceStore a;
    private PostgresPersistenceStore b;

    private static PostgresPersistenceStore store() throws Exception {
        return new PostgresPersistenceStore(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), key -> true);
    }

    private static long query(String sql) throws Exception {
        try (Connection c = POSTGRES.createConnection(""); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void exec(String sql) throws Exception {
        try (Connection c = POSTGRES.createConnection(""); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        exec("drop table if exists jenkins_store, jenkins_store_events, jenkins_members, jenkins_trigger_claims, jenkins_build_leases,"
                + " jenkins_queue, jenkins_schema_migrations");
        System.setProperty(SchemaMigrations.class.getName() + ".batch", "100");
        a = store();
        b = store();
    }

    @AfterEach
    void tearDown() {
        a.close();
        b.close();
        System.clearProperty(SchemaMigrations.class.getName() + ".batch");
    }

    @Test
    void backfilledWhileWritesGoOnAndResumedByAnotherController() throws Exception {
        for (int i = 0; i < 2000; i++) {
            a.write("jobs/j" + i + "/config.xml", ("<project>" + i + "</project>").getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
        }
        // as written by older controllers: no size, and no index yet
        exec("update jenkins_store set size = null");
        exec("drop index if exists jenkins_store_key_prefix");
        Thread runner = new Thread(a.migrations::runPending);
        runner.start();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        while (query("select coalesce(max(rows_done), 0) from jenkins_schema_migrations where id = 'store-size'") < 300
                && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        // the controller running it dies midway; writes go on meanwhile
        runner.interrupt();
        runner.join();
        a.close();
        long before = query("select count(*) from jenkins_store where size is null");
        assertTrue(before > 0 && before < 2000, "stopped midway: " + before + " rows left");
        for (int i = 0; i < 50; i++) {
            b.write("jobs/new" + i + "/config.xml", "<project/>".getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
        }
        b.migrations.runPending(); // takes it over (its owner is no live member) and finishes it
        assertEquals(0, query("select count(*) from jenkins_store where size is null or size <> octet_length(content)"));
        assertEquals(1, query("select count(*) from pg_index i join pg_class r on r.oid = i.indexrelid"
                + " where r.relname = 'jenkins_store_key_prefix' and i.indisvalid"), "the index, built concurrently");
        assertEquals(2, query("select count(*) from jenkins_schema_migrations where state = 'done'"));
        assertEquals(0, query("select count(*) from information_schema.columns where table_name = 'jenkins_store'"
                + " and column_name = 'size' and is_nullable = 'YES'"), "contracted: no older controller is alive");
        a = store(); // for tearDown
    }
}
