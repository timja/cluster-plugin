package io.jenkins.plugins.cnj.store;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Schema changes that must not block a running cluster (Target 15): expand, backfill, contract.
 *
 * <ul>
 * <li><b>Expand</b>, at startup in the schema transaction: additive changes that take no time (nullable columns).
 * Indexes are built here instead, with {@code create index concurrently}, outside any transaction, so writes go on.
 * <li><b>Backfill</b>, in the background: in batches, one transaction each, which also records
 * the cursor (batches of {@link #batch()} rows); one controller at a time (the {@code owner}, taken over if it is no longer a live member), resuming from
 * the cursor. New code writes the new form itself, so the backfill only covers rows written before, or by older
 * controllers.
 * <li><b>Contract</b>: only once the backfill is done and no live controller speaks an older protocol than the
 * migration needs (it might still write the old form).
 * </ul>
 *
 * Progress is in {@code jenkins_schema_migrations}, shown in the cluster view.
 */
final class SchemaMigrations {

    private static final Logger LOGGER = Logger.getLogger(SchemaMigrations.class.getName());

    static int batch() {
        return Integer.getInteger(SchemaMigrations.class.getName() + ".batch", 1000);
    }
    static final long PAUSE_MILLIS = Long.getLong(SchemaMigrations.class.getName() + ".pauseMillis", 50);

    /** One migration. */
    interface Migration {
        @NonNull
        String id();

        /** {@code create index concurrently if not exists …} statements. */
        default List<String> indexes() {
            return List.of();
        }

        /**
         * Updates the next batch of rows after {@code cursor} (null: from the start).
         *
         * @return the cursor after this batch, or null once there is nothing left
         */
        @CheckForNull
        default String backfill(@NonNull Connection c, @CheckForNull String cursor, int batch) throws SQLException {
            return null;
        }

        /** The protocol every live controller must speak before {@link #contract} runs; 0 for none. */
        default int contractProtocol() {
            return 0;
        }

        default void contract(@NonNull Connection c) throws SQLException {}
    }

    /** The prefix index of keys (Target 14), built without blocking writes on a large table. */
    static final Migration KEY_INDEX = new Migration() {
        @Override
        public String id() {
            return "store-key-prefix-index";
        }

        @Override
        public List<String> indexes() {
            return List.of("create index concurrently if not exists jenkins_store_key_prefix on jenkins_store (key text_pattern_ops)");
        }
    };

    /** {@code jenkins_store.size}: each value's size, so that the store's size is known without reading its content. */
    static final Migration STORE_SIZE = new Migration() {
        @Override
        public String id() {
            return "store-size";
        }

        @Override
        public String backfill(Connection c, String cursor, int batch) throws SQLException {
            try (PreparedStatement ps = c.prepareStatement("with b as (select key from jenkins_store where key ~>~ ? and size is null"
                    + " order by key using ~<~ limit ?) update jenkins_store s set size = octet_length(s.content) from b"
                    + " where s.key = b.key returning s.key")) {
                ps.setString(1, cursor == null ? "" : cursor);
                ps.setInt(2, batch);
                String last = null;
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String k = rs.getString(1);
                        if (last == null || k.compareTo(last) > 0) {
                            last = k;
                        }
                    }
                }
                return last;
            }
        }

        @Override
        public int contractProtocol() {
            return 2; // older controllers write without it
        }

        @Override
        public void contract(Connection c) throws SQLException {
            try (Statement s = c.createStatement()) {
                s.execute("update jenkins_store set size = octet_length(content) where size is null"); // written meanwhile
                s.execute("alter table jenkins_store alter column size set not null");
            }
        }
    };

    static final List<Migration> ALL = List.of(KEY_INDEX, STORE_SIZE);

    /** A migration's progress, as recorded. */
    record Progress(String id, String state, String owner, long rows) {}

    private final PostgresPersistenceStore store;

    SchemaMigrations(PostgresPersistenceStore store) {
        this.store = store;
    }

    /** Creates the progress table; part of the startup schema transaction. */
    static void expand(Statement s) throws SQLException {
        s.execute("create table if not exists jenkins_schema_migrations (id text primary key, state text not null default 'pending',"
                + " owner text, cursor text, rows_done bigint not null default 0, started_at timestamptz, finished_at timestamptz)");
        s.execute("alter table jenkins_store add column if not exists size int"); // nullable: instant
    }

    /** Runs what is left of every migration this controller can run now; called periodically. */
    void runPending() {
        for (Migration m : ALL) {
            try {
                run(m);
            } catch (SQLException | RuntimeException e) {
                LOGGER.log(Level.WARNING, "Schema migration " + m.id() + " failed; it is retried", e);
            }
        }
    }

    private void run(Migration m) throws SQLException {
        Progress p = claim(m);
        if (p == null || p.state().equals("done")) {
            return;
        }
        if (p.state().equals("pending") || p.state().equals("indexing")) {
            setState(m, "indexing");
            for (String sql : m.indexes()) {
                createIndexConcurrently(sql);
            }
            setState(m, "backfilling");
        }
        if (!p.state().equals("contracting")) {
            String cursor = cursorOf(m);
            while (true) {
                String c0 = cursor;
                String next = store.migrationTransaction(c -> {
                    String n = m.backfill(c, c0, batch());
                    try (PreparedStatement ps = c.prepareStatement("update jenkins_schema_migrations set cursor = ?,"
                            + " rows_done = rows_done + ? where id = ? and owner = ?")) {
                        ps.setString(1, n == null ? c0 : n);
                        ps.setInt(2, n == null ? 0 : batch());
                        ps.setString(3, m.id());
                        ps.setString(4, store.getOrigin());
                        if (ps.executeUpdate() == 0) {
                            throw new SQLException("lost the ownership of " + m.id());
                        }
                    }
                    return n;
                });
                if (next == null) {
                    break;
                }
                cursor = next;
                try {
                    Thread.sleep(PAUSE_MILLIS); // leave room for the cluster's own work
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            setState(m, "contracting");
        }
        try {
            if (m.contractProtocol() > 0 && !store.peersBelow(m.contractProtocol()).isEmpty()) {
                return; // an older controller is still alive: later
            }
        } catch (java.io.IOException e) {
            return; // cannot tell: later
        }
        store.migrationTransaction(c -> {
            m.contract(c);
            return null;
        });
        store.migrationTransaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("update jenkins_schema_migrations set state = 'done', finished_at = now(),"
                    + " owner = null where id = ?")) {
                ps.setString(1, m.id());
                ps.executeUpdate();
            }
            return null;
        });
        LOGGER.info(() -> "Schema migration " + m.id() + " done");
    }

    /** Takes a migration over unless a live controller has it; null if another controller runs it. */
    @CheckForNull
    private Progress claim(Migration m) throws SQLException {
        Set<String> live;
        try {
            live = store.aliveMembers();
        } catch (java.io.IOException e) {
            return null;
        }
        return store.migrationTransaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("insert into jenkins_schema_migrations (id) values (?) on conflict do nothing")) {
                ps.setString(1, m.id());
                ps.executeUpdate();
            }
            String state;
            String owner;
            long rows;
            try (PreparedStatement ps = c.prepareStatement("select state, owner, rows_done from jenkins_schema_migrations where id = ? for update")) {
                ps.setString(1, m.id());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    state = rs.getString(1);
                    owner = rs.getString(2);
                    rows = rs.getLong(3);
                }
            }
            if (state.equals("done")) {
                return new Progress(m.id(), state, owner, rows);
            }
            if (owner != null && !owner.equals(store.getOrigin()) && live.contains(owner)) {
                return null;
            }
            try (PreparedStatement ps = c.prepareStatement("update jenkins_schema_migrations set owner = ?,"
                    + " started_at = coalesce(started_at, now()) where id = ?")) {
                ps.setString(1, store.getOrigin());
                ps.setString(2, m.id());
                ps.executeUpdate();
            }
            if (owner != null && !owner.equals(store.getOrigin())) {
                LOGGER.info(() -> "Taking schema migration " + m.id() + " over from " + owner + ", which is gone");
            }
            return new Progress(m.id(), state, store.getOrigin(), rows);
        });
    }

    private String cursorOf(Migration m) throws SQLException {
        return store.migrationTransaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("select cursor from jenkins_schema_migrations where id = ?")) {
                ps.setString(1, m.id());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    private void setState(Migration m, String state) throws SQLException {
        store.migrationTransaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("update jenkins_schema_migrations set state = ? where id = ?")) {
                ps.setString(1, state);
                ps.setString(2, m.id());
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Outside any transaction, as Postgres requires; an invalid index left by an interrupted build is built again. */
    private void createIndexConcurrently(String sql) throws SQLException {
        String name = sql.replaceFirst("(?i).*if not exists\\s+(\\S+)\\s+on.*", "$1");
        try (Connection c = store.directConnection()) {
            c.setAutoCommit(true);
            try (PreparedStatement ps = c.prepareStatement("select i.indisvalid from pg_index i join pg_class r on r.oid = i.indexrelid"
                    + " where r.relname = ?")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && !rs.getBoolean(1)) {
                        try (Statement s = c.createStatement()) {
                            s.execute("drop index concurrently if exists " + name);
                        }
                    }
                }
            }
            try (Statement s = c.createStatement()) {
                s.execute("set statement_timeout = 0"); // building an index on a large table takes its time
                s.execute(sql);
            }
        }
    }

    /** Every migration's progress, for the cluster view. */
    List<Progress> progress() throws SQLException {
        return store.migrationTransaction(c -> {
            List<Progress> result = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("select id, state, owner, rows_done from jenkins_schema_migrations order by id");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new Progress(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)));
                }
            }
            return result;
        });
    }
}
