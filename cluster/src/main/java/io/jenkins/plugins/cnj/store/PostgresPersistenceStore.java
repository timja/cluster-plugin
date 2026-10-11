package io.jenkins.plugins.cnj.store;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Map;
import java.util.TreeMap;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.persistence.BuildLeases;
import jenkins.persistence.ClusterQueue;
import jenkins.persistence.ConflictException;
import jenkins.persistence.PersistenceStore;
import org.postgresql.PGConnection;

/**
 * {@link PersistenceStore} on PostgreSQL.
 *
 * <p>Entries live in {@code jenkins_store}. Versions come from one sequence, so they increase across all keys
 * and a deleted-then-recreated key never repeats a version.
 * Every modification also appends to {@code jenkins_store_events} in the same transaction;
 * subscribers read that log in order, woken by {@code NOTIFY} and by a polling fallback,
 * so no change is missed across reconnects.
 */
public class PostgresPersistenceStore implements PersistenceStore, AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(PostgresPersistenceStore.class.getName());
    static final String CHANNEL = "jenkins_store";
    /** Notified when a controller leaves the cluster cleanly (Target 15). */
    static final String MEMBERS_CHANNEL = "jenkins_members";
    /** Advisory lock serializing schema creation between controllers. */
    private static final long SCHEMA_LOCK = 0x6a656e6b696e73L;
    private static final long COUNTERS_LOCK = 0x636e6a636e7472L;

    private final String url;
    private final String user;
    private final String password;
    private final Predicate<String> owns;
    private final String origin = UUID.randomUUID().toString();
    /** Ordinary store traffic. */
    private final com.zaxxer.hikari.HikariDataSource work;
    /**
     * Heartbeats, leases and membership: a pool of their own, so that a busy or slow {@link #work} pool never makes
     * this controller look gone to the others (Target 14).
     */
    private final com.zaxxer.hikari.HikariDataSource control;

    private final List<Consumer<ChangeEvent>> listeners = new CopyOnWriteArrayList<>();
    private Thread listenerThread;
    private volatile boolean closed;

    public PostgresPersistenceStore(String url, String user, String password, Predicate<String> owns)
            throws IOException {
        this.url = url;
        this.user = user;
        this.password = password;
        this.owns = owns;
        this.work = pool("work", WORK_POOL_SIZE);
        this.control = pool("control", 2);
        try {
            withTransaction(c -> {
                try (Statement s = c.createStatement()) {
                    // several controllers starting at once: "create ... if not exists" is not safe concurrently in
                    // Postgres
                    s.execute("select pg_advisory_xact_lock(" + SCHEMA_LOCK + ")");
                    s.execute("create sequence if not exists jenkins_store_version");
                    s.execute("create table if not exists jenkins_store ("
                            + "key text primary key, content bytea not null, sha256 bytea not null, version bigint not null,"
                            + " updated_at timestamptz not null default now(), updated_by text not null)");
                    // Target 14 (scale): prefix listings (children, moves) use an index on keys; Target 15: it is built in the
                    // background (SchemaMigrations.KEY_INDEX), concurrently, so that a large table is not locked meanwhile
                    SchemaMigrations.expand(s);
                    s.execute(
                            "create table if not exists jenkins_members ("
                                    + "origin text primary key, heartbeat_at timestamptz not null, started_at timestamptz not null default now())");
                    s.execute("create table if not exists jenkins_trigger_claims ("
                            + "item text not null, trigger text not null, tick bigint not null, origin text not null,"
                            + " claimed_at timestamptz not null default now(), primary key (item, trigger, tick))");
                    s.execute("create table if not exists jenkins_build_leases ("
                            + "key text primary key, owner text not null, epoch bigint not null,"
                            + " acquired_at timestamptz not null default now())");
                    s.execute("alter table jenkins_members add column if not exists name text");
                    s.execute("alter table jenkins_members add column if not exists url text");
                    // Target 10: what each member runs, for rolling upgrades with mixed versions
                    s.execute("alter table jenkins_members add column if not exists version text");
                    s.execute("alter table jenkins_members add column if not exists protocol int");
                    // Target 13: which generation of the cluster's plugin set each member has applied
                    s.execute("alter table jenkins_members add column if not exists plugins text");
                    // Target 15: what else each member reports (executors, update-center jobs), as JSON
                    s.execute("alter table jenkins_members add column if not exists info text");
                    // Target 15: the key of the session lock each member holds while its process lives
                    s.execute("alter table jenkins_members add column if not exists lock_key int");
                    s.execute("create sequence if not exists jenkins_queue_ids");
                    s.execute("create table if not exists jenkins_queue ("
                            + "id bigint primary key, task text not null, dedup_key text not null, actions text not null,"
                            + " due_at bigint not null, state text not null, origin text not null, pinned_to text, claimed_by text,"
                            + " build_number int not null default 0, updated_at timestamptz not null default now())");
                    s.execute(
                            "create unique index if not exists jenkins_queue_waiting on jenkins_queue (dedup_key) where state = 'WAITING'");
                    // Target 7: which controller holds each agent's channel
                    s.execute("create table if not exists jenkins_channels ("
                            + "agent text primary key, origin text not null, since timestamptz not null default now())");
                    // Target 9: cluster-wide counters (e.g. cloud agent caps), one row per scope and controller
                    s.execute("create table if not exists jenkins_counters ("
                            + "scope text not null, origin text not null, count int not null, primary key (scope, origin))");
                    s.execute("create table if not exists jenkins_store_events ("
                            + "seq bigserial primary key, kind text not null, key text not null, old_key text,"
                            + " version bigint not null, origin text not null, at timestamptz not null default now())");
                    s.execute("create index if not exists jenkins_store_events_at on jenkins_store_events (at)");
                }
                return null;
            });
        } catch (SQLException e) {
            throw new IOException("Failed to initialize persistence store at " + url, e);
        }
    }

    @Override
    public boolean owns(@NonNull String key) {
        return owns.test(key);
    }

    @NonNull
    @Override
    public String getOrigin() {
        return origin;
    }

    @CheckForNull
    @Override
    public Entry read(@NonNull String key) throws IOException {
        try {
            return withTransaction(c -> {
                try (PreparedStatement ps =
                        c.prepareStatement("select content, version from jenkins_store where key = ?")) {
                    ps.setString(1, key);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? new Entry(rs.getBytes(1), rs.getLong(2)) : null;
                    }
                }
            });
        } catch (SQLException e) {
            throw new IOException("Failed to read " + key, e);
        }
    }

    @Override
    public long write(@NonNull String key, @NonNull byte[] content, long expectedVersion) throws IOException {
        byte[] sha = sha256(content);
        try {
            return withTransaction(c -> {
                long actual = ABSENT;
                byte[] actualSha = null;
                try (PreparedStatement ps =
                        c.prepareStatement("select version, sha256 from jenkins_store where key = ? for update")) {
                    ps.setString(1, key);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            actual = rs.getLong(1);
                            actualSha = rs.getBytes(2);
                        }
                    }
                }
                if (expectedVersion != ANY && expectedVersion != actual) {
                    throw new Conflict(new ConflictException(key, expectedVersion, actual));
                }
                if (actualSha != null && Arrays.equals(actualSha, sha)) {
                    return actual; // identical content: no new version, no event
                }
                long version = nextVersion(c);
                if (actual == ABSENT) {
                    try (PreparedStatement ps =
                            c.prepareStatement("insert into jenkins_store (key, content, sha256, version, updated_by, size)"
                                    + " values (?, ?, ?, ?, ?, ?) on conflict (key) do nothing")) {
                        ps.setString(1, key);
                        ps.setBytes(2, content);
                        ps.setBytes(3, sha);
                        ps.setLong(4, version);
                        ps.setString(5, origin);
                        ps.setInt(6, content.length);
                        if (ps.executeUpdate() == 0) {
                            // created concurrently by someone else since our select
                            throw new Conflict(new ConflictException(key, expectedVersion, -1));
                        }
                    }
                } else {
                    try (PreparedStatement ps =
                            c.prepareStatement("update jenkins_store set content = ?, sha256 = ?, version = ?,"
                                    + " updated_at = now(), updated_by = ?, size = ? where key = ?")) {
                        ps.setBytes(1, content);
                        ps.setBytes(2, sha);
                        ps.setLong(3, version);
                        ps.setString(4, origin);
                        ps.setInt(5, content.length);
                        ps.setString(6, key);
                        ps.executeUpdate();
                    }
                }
                event(c, ChangeEvent.Kind.WRITTEN, key, null, version);
                return version;
            });
        } catch (Conflict e) {
            if (expectedVersion == ANY) {
                return write(key, content, ANY); // lost a creation race; overwrite as requested
            }
            throw e.conflict;
        } catch (SQLException e) {
            throw new IOException("Failed to write " + key, e);
        }
    }

    @Override
    public void delete(@NonNull String key, long expectedVersion) throws IOException {
        try {
            withTransaction(c -> {
                try (PreparedStatement ps =
                        c.prepareStatement("delete from jenkins_store where key = ? returning version")) {
                    ps.setString(1, key);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            return null;
                        }
                        long actual = rs.getLong(1);
                        if (expectedVersion != ANY && expectedVersion != actual) {
                            throw new Conflict(new ConflictException(key, expectedVersion, actual));
                        }
                    }
                }
                event(c, ChangeEvent.Kind.DELETED, key, null, nextVersion(c));
                return null;
            });
        } catch (Conflict e) {
            throw e.conflict;
        } catch (SQLException e) {
            throw new IOException("Failed to delete " + key, e);
        }
    }

    @NonNull
    @Override
    public Set<String> children(@NonNull String parentKey) throws IOException {
        String prefix = parentKey + "/";
        try {
            return withTransaction(c -> {
                Set<String> names = new TreeSet<>();
                // a skip scan over the key index (Target 15): one probe per child, however many keys are below it
                // (listing jobs no longer reads every build record). Keys of one child are contiguous in byte order;
                // the next child starts at or after <prefix><child>'0' ('0' sorts right after '/')
                String upper = prefix.substring(0, prefix.length() - 1) + "0";
                try (PreparedStatement ps = c.prepareStatement("with recursive s(seg) as ("
                        + "(select split_part(substr(key, ?), '/', 1) from jenkins_store where key ~>=~ ? and key ~<~ ? order by key using ~<~ limit 1)"
                        + " union all"
                        + " select (select split_part(substr(j.key, ?), '/', 1) from jenkins_store j where j.key ~>=~ (? || s.seg || '0')"
                        + " and j.key ~<~ ? order by j.key using ~<~ limit 1) from s where s.seg is not null)"
                        + " select seg from s where seg is not null")) {
                    ps.setInt(1, prefix.length() + 1);
                    ps.setString(2, prefix);
                    ps.setString(3, upper);
                    ps.setInt(4, prefix.length() + 1);
                    ps.setString(5, prefix);
                    ps.setString(6, upper);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            names.add(rs.getString(1));
                        }
                    }
                }
                return names;
            });
        } catch (SQLException e) {
            throw new IOException("Failed to list " + parentKey, e);
        }
    }

    /** Every key below a prefix in one statement, with a deletion event each (Target 15). */
    @Override
    public int deleteAll(@NonNull String prefix) throws IOException {
        String lower = prefix + "/";
        String upper = prefix + "0";
        try {
            return withTransaction(c -> {
                int n;
                try (PreparedStatement ps = c.prepareStatement("with d as (delete from jenkins_store where key ~>=~ ? and key ~<~ ? returning key)"
                        + " insert into jenkins_store_events (kind, key, old_key, version, origin)"
                        + " select 'DELETED', key, null, nextval('jenkins_store_version'), ? from d")) {
                    ps.setString(1, lower);
                    ps.setString(2, upper);
                    ps.setString(3, origin);
                    n = ps.executeUpdate();
                }
                if (n > 0) {
                    notifyChanged(c);
                }
                return n;
            });
        } catch (SQLException e) {
            throw new IOException("Failed to delete " + prefix + "/…", e);
        }
    }

    @Override
    public void move(@NonNull String fromPrefix, @NonNull String toPrefix) throws IOException {
        try {
            withTransaction(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "select 1 from jenkins_store where key = ? or key like ? escape '\\' limit 1")) {
                    ps.setString(1, toPrefix);
                    ps.setString(2, likePrefix(toPrefix + "/"));
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            throw new Conflict(new ConflictException(toPrefix, ABSENT, -1));
                        }
                    }
                }
                List<String> keys = new ArrayList<>();
                try (PreparedStatement ps = c.prepareStatement(
                        "select key from jenkins_store where key = ? or key like ? escape '\\' order by length(key), key for update")) {
                    ps.setString(1, fromPrefix);
                    ps.setString(2, likePrefix(fromPrefix + "/"));
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            keys.add(rs.getString(1));
                        }
                    }
                }
                for (String oldKey : keys) {
                    String newKey = toPrefix + oldKey.substring(fromPrefix.length());
                    long version = nextVersion(c);
                    try (PreparedStatement ps = c.prepareStatement(
                            "update jenkins_store set key = ?, version = ?, updated_at = now(), updated_by = ? where key = ?")) {
                        ps.setString(1, newKey);
                        ps.setLong(2, version);
                        ps.setString(3, origin);
                        ps.setString(4, oldKey);
                        ps.executeUpdate();
                    }
                    event(c, ChangeEvent.Kind.MOVED, newKey, oldKey, version);
                }
                return null;
            });
        } catch (Conflict e) {
            throw e.conflict;
        } catch (SQLException e) {
            throw new IOException("Failed to move " + fromPrefix + " to " + toPrefix, e);
        }
    }

    // ------------------------------------------------------------------ cluster membership and trigger claims

    /** How often a controller records that it is alive. */
    static final long HEARTBEAT_MILLIS =
            Long.getLong(PostgresPersistenceStore.class.getName() + ".heartbeatMillis", 2_000);
    /** How long after its last heartbeat a controller is considered gone. */
    static final long MEMBER_TIMEOUT_MILLIS =
            Long.getLong(PostgresPersistenceStore.class.getName() + ".memberTimeoutMillis", 15_000);

    /**
     * Session locks for liveness (Target 15): each controller holds a Postgres advisory lock on a connection of its own,
     * and names it in its membership row. A controller whose process died loses its connection, so the database frees
     * the lock at once; it is then taken for gone {@link #FAST_DEAD_MILLIS} after its last heartbeat instead of after
     * the {@link #MEMBER_TIMEOUT_MILLIS}. A paused or partitioned controller keeps its connection (TCP), so its lock,
     * and is judged by the timeout as before: pauses never make a controller look dead sooner. Off with
     * {@code -Dio.jenkins.plugins.cnj.store.PostgresPersistenceStore.sessionLocks=false}, e.g. behind a pooler in
     * transaction mode, which does not keep sessions.
     */
    static final boolean SESSION_LOCKS = !"false".equals(System.getProperty(PostgresPersistenceStore.class.getName() + ".sessionLocks"));
    /** How long after its last heartbeat a controller whose session lock is free is taken for gone. */
    static final long FAST_DEAD_MILLIS = 3 * HEARTBEAT_MILLIS;
    /** The first key of the advisory locks (the second is the member's {@code lock_key}). */
    static final int LOCK_CLASS = 0x434a;

    /** The liveness condition beyond the heartbeat timeout, for a members table alias ({@code "m."} or {@code ""}). */
    static String fast(String alias) {
        if (!SESSION_LOCKS) {
            return "";
        }
        return " and (" + alias + "heartbeat_at > now() - interval '" + FAST_DEAD_MILLIS + " milliseconds' or " + alias + "lock_key is null"
                + " or exists (select 1 from pg_locks l where l.locktype = 'advisory' and l.granted and l.classid = " + LOCK_CLASS
                + " and l.objid = " + alias + "lock_key::oid and l.objsubid = 2))";
    }

    private Thread heartbeatThread;
    /** {@link System#nanoTime} of the last heartbeat that reached the database. */
    private volatile long lastHeartbeatOk = System.nanoTime() - TimeUnit.DAYS.toNanos(1);

    /**
     * Whether the other controllers may have considered this one gone by now, because its heartbeat has not
     * reached the database for the member timeout (measured locally, so e.g. a paused JVM notices on waking).
     */
    private final java.util.concurrent.atomic.AtomicLong lapses = new java.util.concurrent.atomic.AtomicLong();

    /**
     * How many times this controller's membership may have lapsed (heartbeats missing for longer than the timeout).
     * When this changes, work done on behalf of leases should check that they are still held.
     */
    long lapseCount() {
        return lapses.get();
    }

    boolean mayHaveLapsed() {
        long since = System.nanoTime() - lastHeartbeatOk;
        if (since > TimeUnit.MILLISECONDS.toNanos(MEMBER_TIMEOUT_MILLIS)) {
            return true;
        }
        // our row names a session lock we no longer hold: the others take us for gone FAST_DEAD_MILLIS after our last
        // heartbeat, so stop acting for our leases a heartbeat before that
        return lockNamed && !lockHeld && since > TimeUnit.MILLISECONDS.toNanos(FAST_DEAD_MILLIS - HEARTBEAT_MILLIS);
    }

    /** The second key of this controller's session lock. */
    final int LOCK_KEY = origin.hashCode() & 0x7fffffff;
    /** Whether this controller holds its session lock now. */
    private volatile boolean lockHeld;
    /** Whether this controller's membership row (as last written) names its session lock. */
    private volatile boolean lockNamed;
    private Thread lockThread;
    /** Woken to compare members at once: a controller announced it left. */
    private final java.util.concurrent.Semaphore wake = new java.util.concurrent.Semaphore(0);

    /** Holds the session lock on a connection of its own, checking it every second and taking it again if lost. */
    private void holdLock() {
        while (!closed) {
            try (Connection c = dataSource().getConnection()) {
                c.setAutoCommit(true);
                try (PreparedStatement ps = c.prepareStatement("select pg_try_advisory_lock(?, ?)")) {
                    ps.setInt(1, LOCK_CLASS);
                    ps.setInt(2, LOCK_KEY);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        if (!rs.getBoolean(1)) {
                            LOGGER.warning("Another session holds this controller's liveness lock key; going by heartbeats only");
                            return;
                        }
                    }
                }
                lockHeld = true;
                while (!closed) {
                    Thread.sleep(1000);
                    if (!c.isValid(2)) {
                        throw new SQLException("liveness lock connection lost");
                    }
                }
            } catch (InterruptedException e) {
                return;
            } catch (SQLException | RuntimeException e) {
                if (lockHeld) {
                    LOGGER.log(Level.WARNING, "Lost the liveness lock; taking it again", e);
                }
            } finally {
                lockHeld = false;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** {@link System#nanoTime} since when this controller's heartbeats have reached the database without a lapse. */
    private volatile long stableSince = System.nanoTime();

    /**
     * Whether this controller may judge other controllers gone (and take over their builds and queue items).
     * Only after its own heartbeats have reached the database without a lapse for longer than the member timeout:
     * right after a database outage every heartbeat is stale, including the live controllers' own, and those must
     * get the chance to heartbeat again first (Target 14).
     */
    /**
     * For tests: stops heartbeating without leaving, as a process that died ({@code releaseLock}: its connections close)
     * or one that is paused or cut off ({@code !releaseLock}: its session, so its lock, lives on).
     */
    synchronized void stopHeartbeatForTests(boolean releaseLock) throws InterruptedException {
        if (heartbeatThread != null) {
            heartbeatThread.interrupt();
            heartbeatThread.join();
        }
        if (releaseLock && lockThread != null) {
            lockThread.interrupt();
            lockThread.join();
        }
    }

    boolean holdsLivenessLockForTests() {
        return lockHeld;
    }

    /** Whether this controller runs schema migrations in the background (Target 15); on by default. */
    static final boolean MIGRATE = !"false".equals(System.getProperty(PostgresPersistenceStore.class.getName() + ".migrate"));
    final SchemaMigrations migrations = new SchemaMigrations(this);
    private Thread migrationThread;

    /** A transaction for {@link SchemaMigrations}, on the work pool. */
    <T> T migrationTransaction(Work<T> work) throws SQLException {
        return withTransaction(work);
    }

    /** A connection outside the pools, for statements Postgres refuses in a transaction ({@code create index concurrently}). */
    Connection directConnection() throws SQLException {
        return dataSource().getConnection();
    }

    /** For tests: as if this controller had been steadily connected for long. */
    void steadyForTests() {
        stableSince = System.nanoTime() - TimeUnit.HOURS.toNanos(1);
    }

    boolean canJudgeLiveness() {
        return !mayHaveLapsed()
                && System.nanoTime() - stableSince > TimeUnit.MILLISECONDS.toNanos(MEMBER_TIMEOUT_MILLIS + HEARTBEAT_MILLIS);
    }

    private final BuildLeases leases = new BuildLeases() {
        @Override
        public long acquire(@NonNull String key) throws IOException {
            try {
                boolean judge = canJudgeLiveness();
                return withControl(c -> {
                    if (!judge) {
                        // a new build's lease only: taking one from a controller that looks gone waits until this one
                        // has been steadily connected (right after an outage they all look gone)
                        try (PreparedStatement ps = c.prepareStatement(
                                "insert into jenkins_build_leases (key, owner, epoch) values (?, ?, nextval('jenkins_store_version'))"
                                        + " on conflict (key) do nothing returning epoch")) {
                            ps.setString(1, key);
                            ps.setString(2, origin);
                            try (ResultSet rs = ps.executeQuery()) {
                                if (rs.next()) {
                                    return rs.getLong(1);
                                }
                            }
                        }
                        try (PreparedStatement ps =
                                c.prepareStatement("select owner, epoch from jenkins_build_leases where key = ?")) {
                            ps.setString(1, key);
                            try (ResultSet rs = ps.executeQuery()) {
                                return rs.next() && rs.getString(1).equals(origin) ? rs.getLong(2) : 0L;
                            }
                        }
                    }
                    try (PreparedStatement ps = c.prepareStatement(
                            "insert into jenkins_build_leases (key, owner, epoch) values (?, ?, nextval('jenkins_store_version'))"
                                    + " on conflict (key) do update set owner = excluded.owner, epoch = excluded.epoch, acquired_at = now()"
                                    + " where jenkins_build_leases.owner <> excluded.owner and not exists (select 1 from jenkins_members m"
                                    + " where m.origin = jenkins_build_leases.owner and m.heartbeat_at > now() - ? * interval '1 millisecond'" + fast("m.") + ")"
                                    + " returning epoch")) {
                        ps.setString(1, key);
                        ps.setString(2, origin);
                        ps.setLong(3, MEMBER_TIMEOUT_MILLIS);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) {
                                return rs.getLong(1);
                            }
                        }
                    }
                    // not taken: already ours, or held by a live controller
                    try (PreparedStatement ps =
                            c.prepareStatement("select owner, epoch from jenkins_build_leases where key = ?")) {
                        ps.setString(1, key);
                        try (ResultSet rs = ps.executeQuery()) {
                            return rs.next() && rs.getString(1).equals(origin) ? rs.getLong(2) : 0L;
                        }
                    }
                });
            } catch (SQLException e) {
                throw new IOException("Failed to acquire lease on " + key, e);
            }
        }

        @Override
        public boolean holds(@NonNull String key, long epoch) throws IOException {
            // only the database can tell that the lease was lost: while it cannot be reached this fails (unknown),
            // and the build carries on; its log and save points wait (mayHaveLapsed) until the heartbeat is back
            try {
                return withControl(c -> {
                    try (PreparedStatement ps = c.prepareStatement(
                            "select 1 from jenkins_build_leases where key = ? and owner = ? and epoch = ?")) {
                        ps.setString(1, key);
                        ps.setString(2, origin);
                        ps.setLong(3, epoch);
                        try (ResultSet rs = ps.executeQuery()) {
                            return rs.next();
                        }
                    }
                });
            } catch (SQLException e) {
                throw new IOException("Failed to check lease on " + key, e);
            }
        }

        @Override
        public void release(@NonNull String key, long epoch) throws IOException {
            try {
                withControl(c -> {
                    try (PreparedStatement ps = c.prepareStatement(
                            "delete from jenkins_build_leases where key = ? and owner = ? and epoch = ?")) {
                        ps.setString(1, key);
                        ps.setString(2, origin);
                        ps.setLong(3, epoch);
                        ps.executeUpdate();
                    }
                    return null;
                });
            } catch (SQLException e) {
                throw new IOException("Failed to release lease on " + key, e);
            }
        }
    };

    @Override
    public BuildLeases leases() {
        return leases;
    }

    // ------------------------------------------------------------------ shared build queue (Target 5)

    static final String QUEUE_PREFIX = "queue/";

    private final ClusterQueue queue = new ClusterQueue() {
        private static final String COLUMNS =
                "id, task, dedup_key, actions, due_at, state, origin, pinned_to, claimed_by, build_number";

        private Record record(ResultSet rs) throws SQLException {
            return new Record(
                    rs.getLong(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getLong(5),
                    State.valueOf(rs.getString(6)),
                    rs.getString(7),
                    rs.getString(8),
                    rs.getString(9),
                    rs.getInt(10));
        }

        private Record select(Connection c, String where, Object... args) throws SQLException {
            try (PreparedStatement ps =
                    c.prepareStatement("select " + COLUMNS + " from jenkins_queue where " + where)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? record(rs) : null;
                }
            }
        }

        private int change(Connection c, long id, String sql, Object... args) throws SQLException {
            int n;
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                n = ps.executeUpdate();
            }
            if (n > 0) {
                event(c, ChangeEvent.Kind.WRITTEN, QUEUE_PREFIX + id, null, nextVersion(c));
            }
            return n;
        }

        @Override
        public long nextId() throws IOException {
            try {
                return withTransaction(c -> {
                    try (Statement s = c.createStatement();
                            ResultSet rs = s.executeQuery("select nextval('jenkins_queue_ids')")) {
                        rs.next();
                        return rs.getLong(1);
                    }
                });
            } catch (SQLException e) {
                throw new IOException("Failed to allocate a queue id", e);
            }
        }

        @NonNull
        @Override
        public Record publish(@NonNull Record item) throws IOException {
            try {
                return withTransaction(c -> {
                    int n = change(
                            c,
                            item.id(),
                            "insert into jenkins_queue (" + COLUMNS + ") values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                                    + " on conflict do nothing",
                            item.id(),
                            item.task(),
                            item.dedupKey(),
                            item.actions(),
                            item.dueAt(),
                            item.state().name(),
                            item.origin(),
                            item.pinnedTo(),
                            item.claimedBy(),
                            item.buildNumber());
                    if (n == 1) {
                        return item;
                    }
                    Record same = select(c, "id = ?", item.id());
                    return same != null ? same : select(c, "dedup_key = ? and state = 'WAITING'", item.dedupKey());
                });
            } catch (SQLException e) {
                throw new IOException("Failed to publish queue item " + item.id(), e);
            }
        }

        @Override
        public void update(long id, @NonNull String actions, long dueAt) throws IOException {
            run(
                    "update queue item " + id,
                    c -> change(
                            c,
                            id,
                            "update jenkins_queue set actions = ?, due_at = least(due_at, ?), updated_at = now() where id = ? and state = 'WAITING'"
                                    + " and (actions <> ? or due_at > ?)",
                            actions,
                            dueAt,
                            id,
                            actions,
                            dueAt));
        }

        @Override
        public boolean claim(long id) throws IOException {
            return run("claim queue item " + id, c -> {
                if (change(
                                c,
                                id,
                                "update jenkins_queue set state = 'CLAIMED', claimed_by = ?, updated_at = now() where id = ? and state = 'WAITING'",
                                origin,
                                id)
                        == 1) {
                    return true;
                }
                Record r = select(c, "id = ?", id);
                return r != null && origin.equals(r.claimedBy());
            });
        }

        @Override
        public void started(long id, int buildNumber) throws IOException {
            run(
                    "record the start of queue item " + id,
                    c -> change(
                            c,
                            id,
                            "update jenkins_queue set state = 'STARTED', build_number = ?, updated_at = now() where id = ? and claimed_by = ?",
                            buildNumber,
                            id,
                            origin));
        }

        @Override
        public boolean cancel(long id) throws IOException {
            return run(
                    "cancel queue item " + id,
                    c -> change(
                                    c,
                                    id,
                                    "update jenkins_queue set state = 'CANCELLED', updated_at = now() where id = ? and state = 'WAITING'",
                                    id)
                            == 1);
        }

        @Override
        public Record get(long id) throws IOException {
            return run("read queue item " + id, c -> select(c, "id = ?", id));
        }

        @NonNull
        @Override
        public List<Record> pending() throws IOException {
            return run("list the queue", c -> {
                List<Record> records = new ArrayList<>();
                try (Statement s = c.createStatement();
                        ResultSet rs = s.executeQuery("select " + COLUMNS
                                + " from jenkins_queue where state in ('WAITING', 'CLAIMED') order by id")) {
                    while (rs.next()) {
                        records.add(record(rs));
                    }
                }
                return records;
            });
        }

        @NonNull
        @Override
        public AutoCloseable subscribe(@NonNull Consumer<Record> listener) {
            return PostgresPersistenceStore.this.subscribe(e -> {
                if (!e.key().startsWith(QUEUE_PREFIX)) {
                    return;
                }
                try {
                    Record r = get(Long.parseLong(e.key().substring(QUEUE_PREFIX.length())));
                    if (r != null) {
                        listener.accept(r);
                    }
                } catch (IOException | NumberFormatException x) {
                    LOGGER.log(Level.WARNING, "Failed to read queue change " + e, x);
                }
            });
        }
    };

    private <T> T run(String what, Work<T> work) throws IOException {
        try {
            return withTransaction(work);
        } catch (SQLException e) {
            throw new IOException("Failed to " + what, e);
        }
    }

    /** Whether the build queue is shared through this store; on by default. */
    static final boolean SHARED_QUEUE =
            !"false".equals(System.getProperty(PostgresPersistenceStore.class.getName() + ".sharedQueue"));

    @Override
    public ClusterQueue queue() {
        return SHARED_QUEUE ? queue : null;
    }

    /**
     * Puts items claimed by controllers which are gone back into the queue, so that another controller starts them;
     * and forgets finished items after a day.
     *
     * @return how many items were put back
     */
    int reclaimAndPrune() throws IOException {
        boolean judge = canJudgeLiveness();
        return run("reclaim queue items", c -> {
            List<Long> ids = new ArrayList<>();
            if (judge) try (PreparedStatement ps = c.prepareStatement(
                    "update jenkins_queue set state = 'WAITING', claimed_by = null, updated_at = now()"
                            + " where state = 'CLAIMED' and claimed_by not in (select origin from jenkins_members"
                            + " where heartbeat_at > now() - ? * interval '1 millisecond'" + fast("") + ") returning id")) {
                ps.setLong(1, MEMBER_TIMEOUT_MILLIS);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ids.add(rs.getLong(1));
                    }
                }
            }
            for (long id : ids) {
                event(c, ChangeEvent.Kind.WRITTEN, QUEUE_PREFIX + id, null, nextVersion(c));
            }
            try (Statement s = c.createStatement()) {
                s.execute(
                        "delete from jenkins_queue where state in ('STARTED', 'CANCELLED') and updated_at < now() - interval '1 day'");
            }
            return ids.size();
        });
    }

    /**
     * Starts recording this controller as a live member every {@link #HEARTBEAT_MILLIS}.
     */
    public synchronized void startHeartbeat() throws IOException {
        if (migrationThread == null && MIGRATE) {
            migrationThread = new Thread(() -> {
                while (!closed) {
                    try {
                        Thread.sleep(TimeUnit.SECONDS.toMillis(10));
                        migrations.runPending();
                        Thread.sleep(TimeUnit.SECONDS.toMillis(50));
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }, "PostgresPersistenceStore schema migrations");
            migrationThread.setDaemon(true);
            migrationThread.start();
        }
        if (SESSION_LOCKS && lockThread == null) {
            lockThread = new Thread(this::holdLock, "PostgresPersistenceStore liveness lock");
            lockThread.setDaemon(true);
            lockThread.start();
        }
        heartbeat();
        if (heartbeatThread == null) {
            heartbeatThread = new Thread(
                    () -> {
                        long lastPrune = 0;
                        long nextBeat = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(HEARTBEAT_MILLIS);
                        while (!closed) {
                            try {
                                // wake for the next heartbeat, the next member due to expire, or a member leaving
                                long untilBeat = Math.max(0, TimeUnit.NANOSECONDS.toMillis(nextBeat - System.nanoTime()));
                                long untilExpiry = millisUntilNextExpiry();
                                wake.drainPermits();
                                boolean woken = wake.tryAcquire(Math.min(untilBeat, untilExpiry), TimeUnit.MILLISECONDS);
                                if (woken || System.nanoTime() - nextBeat < 0) {
                                    watchMembers(); // a member left, or one may have just expired
                                    continue;
                                }
                                nextBeat = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(HEARTBEAT_MILLIS);
                                heartbeat();
                                watchMembers();
                                if (System.currentTimeMillis() - lastPrune > 3_600_000) {
                                    pruneClaims();
                                    lastPrune = System.currentTimeMillis();
                                }
                            } catch (InterruptedException e) {
                                return;
                            } catch (IOException | RuntimeException e) {
                                LOGGER.log(Level.WARNING, "Heartbeat failed", e);
                            }
                        }
                    },
                    "PostgresPersistenceStore heartbeat");
            heartbeatThread.setDaemon(true);
            heartbeatThread.start();
        }
    }

    private volatile long lastMemberPrune = System.nanoTime() - TimeUnit.HOURS.toNanos(1);

    /** The other controllers alive at the last heartbeat, to notice one leaving. */
    private Set<String> lastAlive = Set.of();
    /** Called with the origins of controllers which have just left the cluster (died, or stopped). */
    private final List<Consumer<Set<String>>> leaveListeners = new CopyOnWriteArrayList<>();

    /** Calls the listener, on the heartbeat thread, with the controllers that have just left the cluster. */
    void onMembersLeft(Consumer<Set<String>> listener) {
        leaveListeners.add(listener);
    }

    /**
     * Compares the live members with those at the last heartbeat (Target 14): a controller that has just left (its
     * heartbeat older than the timeout, or its row gone on a clean stop) has its builds adopted and its queue items put
     * back right away, instead of at the next periodic sweep.
     */
    private void watchMembers() {
        if (!canJudgeLiveness()) {
            return;
        }
        try {
            Set<String> alive = aliveMembers();
            Set<String> left = new java.util.HashSet<>(lastAlive);
            left.removeAll(alive);
            lastAlive = alive;
            if (!left.isEmpty()) {
                LOGGER.info(() -> left.size() + " controller(s) left the cluster: " + left);
                for (Consumer<Set<String>> l : leaveListeners) {
                    try {
                        l.accept(left);
                    } catch (RuntimeException e) {
                        LOGGER.log(Level.WARNING, "Failed to react to controllers leaving", e);
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Could not compare members", e);
        }
    }

    /** Milliseconds until the next other live member would expire if it stopped heartbeating now (at most a heartbeat). */
    private long millisUntilNextExpiry() {
        if (!canJudgeLiveness()) {
            return HEARTBEAT_MILLIS;
        }
        try {
            return run("next member expiry", c -> {
                try (PreparedStatement ps = c.prepareStatement("select coalesce(min(extract(epoch from (m.heartbeat_at + case when "
                        + (SESSION_LOCKS ? "m.lock_key is not null and not exists (select 1 from pg_locks l where l.locktype = 'advisory'"
                                + " and l.granted and l.classid = " + LOCK_CLASS + " and l.objid = m.lock_key::oid and l.objsubid = 2)" : "false")
                        + " then ? else ? end * interval '1 millisecond' - now())) * 1000), ?) from jenkins_members m"
                        + " where m.origin <> ? and m.heartbeat_at > now() - ? * interval '1 millisecond'" + fast("m."))) {
                    ps.setLong(1, FAST_DEAD_MILLIS);
                    ps.setLong(2, MEMBER_TIMEOUT_MILLIS);
                    ps.setLong(3, HEARTBEAT_MILLIS);
                    ps.setString(4, origin);
                    ps.setLong(5, MEMBER_TIMEOUT_MILLIS);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        // a little after it expires, by the database's clock
                        return Math.max(50, Math.min(HEARTBEAT_MILLIS, (long) rs.getDouble(1) + 50));
                    }
                }
            });
        } catch (IOException | RuntimeException e) {
            return HEARTBEAT_MILLIS;
        }
    }

    void heartbeat() throws IOException {
        try {
            withControl(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "insert into jenkins_members (origin, heartbeat_at, name, url, version, protocol, plugins, info, lock_key)"
                                + " values (?, now(), ?, ?, ?, ?, ?, ?, ?)"
                                + " on conflict (origin) do update set heartbeat_at = now(), name = excluded.name, url = excluded.url,"
                                + " version = excluded.version, protocol = excluded.protocol, plugins = excluded.plugins, info = excluded.info,"
                                + " lock_key = excluded.lock_key")) {
                    ps.setString(1, origin);
                    ps.setString(2, NAME);
                    ps.setString(3, INTERNAL_URL);
                    ps.setString(4, VERSION);
                    ps.setInt(5, PROTOCOL);
                    ps.setString(6, pluginsStatus);
                    ps.setString(7, memberInfo);
                    Integer key = lockHeld ? LOCK_KEY : null;
                    if (key == null) {
                        ps.setNull(8, java.sql.Types.INTEGER);
                    } else {
                        ps.setInt(8, key);
                    }
                    lockNamed = key != null;
                    ps.executeUpdate();
                }
                if (System.nanoTime() - lastMemberPrune > TimeUnit.MINUTES.toNanos(10)) {
                    // every start is a new member: forget incarnations gone for a day (nothing refers to them by then)
                    try (PreparedStatement ps = c.prepareStatement("delete from jenkins_members where heartbeat_at < now() - interval '1 day'")) {
                        ps.executeUpdate();
                    }
                    lastMemberPrune = System.nanoTime();
                }
                return null;
            });
            long now = System.nanoTime();
            if (mayHaveLapsed()) {
                lapses.incrementAndGet(); // others may have taken over our builds meanwhile
            }
            if (now - lastHeartbeatOk > TimeUnit.MILLISECONDS.toNanos(2 * HEARTBEAT_MILLIS)) {
                // a missed heartbeat (the database was unreachable or slow, or this JVM paused): the others may have
                // missed theirs too, so this controller does not judge them until heartbeats have been steady again
                stableSince = now;
            }
            lastHeartbeatOk = now;
            unreachableUntil = 0; // the database answers again
        } catch (SQLException e) {
            throw new IOException("Failed to record heartbeat", e);
        }
    }

    /** Whether completed builds are shared ({@link jenkins.persistence.BuildRecords}); on by default. */
    static final boolean BUILD_RECORDS =
            !"false".equals(System.getProperty(PostgresPersistenceStore.class.getName() + ".buildRecords"));

    @Override
    public boolean storesBuildRecords(@NonNull String jobKey) {
        return BUILD_RECORDS;
    }

    @Override
    public Set<String> liveOrigins() throws IOException {
        synchronized (this) {
            if (heartbeatThread == null) {
                return null; // not taking part in membership, so cannot tell
            }
        }
        if (!canJudgeLiveness()) {
            return null; // cannot tell yet (just started, or the database was unreachable): nobody is taken for gone
        }
        return aliveMembers();
    }

    /** This controller's name, as other controllers see it (and as the prefix of its bound object ids). */
    static final String NAME = env("CNJ_NAME", hostName());
    /** How other controllers reach this one directly, e.g. to forward requests for objects only it holds. */
    static final String INTERNAL_URL = env("CNJ_INTERNAL_URL", "http://" + hostName() + ":8080/");

    /**
     * The version of the cluster protocol this controller speaks: what it stores and expects of its peers.
     * Raised when a change cannot coexist with older peers (which then must be gone before this version starts).
     */
    static final int PROTOCOL = 2; // 2 (Target 15): writes jenkins_store.size (SchemaMigrations.STORE_SIZE)

    /** What this controller runs: core and plugin versions, shown in the cluster view. */
    static final String VERSION = jenkins.model.Jenkins.VERSION + " / " + java.util.Objects.requireNonNullElse(
            PostgresPersistenceStore.class.getPackage().getImplementationVersion(), "dev");

    /** What this controller has applied of the cluster's plugin set ({@link ClusterPlugins}), published with each heartbeat. */
    static volatile String pluginsStatus;

    /** What else this controller reports to the cluster (JSON, {@link MemberStatus}), published with each heartbeat. */
    static volatile String memberInfo;

    /** Peers older than this protocol must not be alive when this controller starts (Target 10). */
    static final int MINIMUM_PEER_PROTOCOL = Integer.getInteger(PostgresPersistenceStore.class.getPackageName() + ".minimumPeerProtocol", 0);

    /**
     * Live controllers other than this one speaking an older protocol than {@code minimum}, as {@code name (protocol)}.
     */
    @NonNull
    List<String> peersBelow(int minimum) throws IOException {
        return run("check peer versions", c -> {
            List<String> result = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("select coalesce(name, origin), coalesce(protocol, 0) from jenkins_members"
                    + " where origin <> ? and coalesce(protocol, 0) < ? and heartbeat_at > now() - ? * interval '1 millisecond'" + fast("") + "")) {
                ps.setString(1, origin);
                ps.setInt(2, minimum);
                ps.setLong(3, MEMBER_TIMEOUT_MILLIS);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.add(rs.getString(1) + " (protocol " + rs.getInt(2) + ")");
                    }
                }
            }
            return result;
        });
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isEmpty() ? fallback : v;
    }

    private static String hostName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.io.IOException e) {
            return "localhost";
        }
    }

    /**
     * The internal URL of a live controller, by name.
     *
     * @return null if no live controller has that name
     */
    @CheckForNull
    String urlOf(@NonNull String name) throws IOException {
        return run("look up controller " + name, c -> {
            try (PreparedStatement ps = c.prepareStatement("select url from jenkins_members where name = ?"
                    + " and heartbeat_at > now() - ? * interval '1 millisecond'" + fast("") + " order by heartbeat_at desc limit 1")) {
                ps.setString(1, name);
                ps.setLong(2, MEMBER_TIMEOUT_MILLIS);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    /** A controller in the membership table (Target 8 cluster view). */
    record Member(String origin, String name, String url, long heartbeatAt, long startedAt, boolean alive, String version, int protocol,
            String plugins, String info) {}

    /** Every controller in the membership table, newest first. */
    @NonNull
    List<Member> members() throws IOException {
        return run("list members", c -> {
            List<Member> result = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("select origin, name, url, heartbeat_at, started_at,"
                    + " heartbeat_at > now() - ? * interval '1 millisecond'" + fast("") + ", version, coalesce(protocol, 0), plugins, info from jenkins_members"
                    + " order by started_at desc")) {
                ps.setLong(1, MEMBER_TIMEOUT_MILLIS);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.add(new Member(rs.getString(1), rs.getString(2), rs.getString(3),
                                rs.getTimestamp(4).getTime(), rs.getTimestamp(5).getTime(), rs.getBoolean(6), rs.getString(7), rs.getInt(8), rs.getString(9), rs.getString(10)));
                    }
                }
            }
            return result;
        });
    }

    /** How many shared-queue items are in each state ({@code WAITING}, {@code CLAIMED}), for the cluster view. */
    @NonNull
    Map<String, Integer> queueCounts() throws IOException {
        return run("count queue items", c -> {
            Map<String, Integer> result = new java.util.TreeMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "select state, count(*) from jenkins_queue where state in ('WAITING', 'CLAIMED') group by state");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString(1), rs.getInt(2));
                }
            }
            return result;
        });
    }

    /** Running-build placeholders, by key, with their content (Target 8 cluster view). */
    @NonNull
    Map<String, byte[]> runningPlaceholders() throws IOException {
        return run("list running builds", c -> {
            Map<String, byte[]> result = new TreeMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "select key, content from jenkins_store where key ~ '^jobs/.+/running/[0-9]+\\.xml$'")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.put(rs.getString(1), rs.getBytes(2));
                    }
                }
            }
            return result;
        });
    }

    /** Agent channels and the name of the controller holding each (Target 8 cluster view). */
    @NonNull
    Map<String, String> channels() throws IOException {
        return run("list channels", c -> {
            Map<String, String> result = new TreeMap<>();
            try (PreparedStatement ps = c.prepareStatement("select ch.agent, coalesce(m.name, ch.origin) from jenkins_channels ch"
                    + " left join jenkins_members m on m.origin = ch.origin"
                    + " where m.heartbeat_at > now() - ? * interval '1 millisecond'" + fast("m.") + "")) {
                ps.setLong(1, MEMBER_TIMEOUT_MILLIS);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.put(rs.getString(1), rs.getString(2));
                    }
                }
            }
            return result;
        });
    }

    /**
     * Atomically adds {@code delta} to this controller's share of each scope if, summed over live controllers, every scope
     * stays within its cap (Target 9: cloud agent caps for the cluster).
     *
     * @param caps scope → cap
     * @return false, changing nothing, if a cap would be exceeded
     */
    boolean addWithinCaps(@NonNull Map<String, Integer> caps, int delta) throws IOException {
        return run("count " + caps.keySet(), c -> {
            try (Statement s = c.createStatement()) {
                s.execute("select pg_advisory_xact_lock(" + COUNTERS_LOCK + ")");
            }
            for (Map.Entry<String, Integer> cap : caps.entrySet()) {
                try (PreparedStatement ps = c.prepareStatement("select coalesce(sum(k.count), 0) from jenkins_counters k"
                        + " join jenkins_members m on m.origin = k.origin"
                        + " where k.scope = ? and (k.origin = ? or m.heartbeat_at > now() - ? * interval '1 millisecond'" + fast("m.") + ")")) {
                    ps.setString(1, cap.getKey());
                    ps.setString(2, origin);
                    ps.setLong(3, MEMBER_TIMEOUT_MILLIS);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        if (rs.getLong(1) + delta > cap.getValue()) {
                            return false;
                        }
                    }
                }
            }
            for (String scope : caps.keySet()) {
                addToCounter(c, scope, delta);
            }
            return true;
        });
    }

    /** Subtracts from this controller's share of each scope, not below zero. */
    void subtractFromCounters(@NonNull Set<String> scopes, int delta) throws IOException {
        run("release " + scopes, c -> {
            for (String scope : scopes) {
                addToCounter(c, scope, -delta);
            }
            return null;
        });
    }

    private void addToCounter(Connection c, String scope, int delta) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("insert into jenkins_counters (scope, origin, count) values (?, ?, greatest(?, 0))"
                + " on conflict (scope, origin) do update set count = greatest(jenkins_counters.count + ?, 0)")) {
            ps.setString(1, scope);
            ps.setString(2, origin);
            ps.setInt(3, delta);
            ps.setInt(4, delta);
            ps.executeUpdate();
        }
    }

    /** Records that this controller now holds the channel of an agent (Target 7). */
    void channelConnected(@NonNull String agent) throws IOException {
        run("record the channel of " + agent, c -> {
            try (PreparedStatement ps = c.prepareStatement("insert into jenkins_channels (agent, origin) values (?, ?)"
                    + " on conflict (agent) do update set origin = excluded.origin, since = now()")) {
                ps.setString(1, agent);
                ps.setString(2, origin);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Records that this controller no longer holds the channel of an agent, unless another one took it meanwhile. */
    void channelDisconnected(@NonNull String agent) throws IOException {
        run("forget the channel of " + agent, c -> {
            try (PreparedStatement ps = c.prepareStatement("delete from jenkins_channels where agent = ? and origin = ?")) {
                ps.setString(1, agent);
                ps.setString(2, origin);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /**
     * The live controller holding an agent's channel, other than this one.
     *
     * @return its name and internal URL, or null
     */
    @CheckForNull
    String[] channelHolder(@NonNull String agent) throws IOException {
        return run("look up the channel of " + agent, c -> {
            try (PreparedStatement ps = c.prepareStatement("select m.name, m.url, m.origin from jenkins_channels ch"
                    + " join jenkins_members m on m.origin = ch.origin"
                    + " where ch.agent = ? and ch.origin <> ? and m.url is not null"
                    + " and m.heartbeat_at > now() - ? * interval '1 millisecond'" + fast("m.") + "")) {
                ps.setString(1, agent);
                ps.setString(2, origin);
                ps.setLong(3, MEMBER_TIMEOUT_MILLIS);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new String[] {rs.getString(1), rs.getString(2), rs.getString(3)} : null;
                }
            }
        });
    }

    /**
     * The internal URL of a live controller, by origin: unlike its name, which a replacement (a restarted pod, a restored
     * cluster) reuses, an origin is one JVM's.
     *
     * @return null if that controller is not alive
     */
    @CheckForNull
    String urlOfOrigin(@NonNull String memberOrigin) throws IOException {
        return run("look up controller " + memberOrigin, c -> {
            try (PreparedStatement ps = c.prepareStatement("select url from jenkins_members where origin = ?"
                    + " and heartbeat_at > now() - ? * interval '1 millisecond'" + fast("") + "")) {
                ps.setString(1, memberOrigin);
                ps.setLong(2, MEMBER_TIMEOUT_MILLIS);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    /**
     * Origins of controllers whose heartbeat is recent, always including this one.
     */
    @NonNull
    public Set<String> aliveMembers() throws IOException {
        try {
            return withControl(c -> {
                Set<String> members = new TreeSet<>();
                members.add(origin);
                try (PreparedStatement ps = c.prepareStatement(
                        "select origin from jenkins_members where heartbeat_at > now() - ? * interval '1 millisecond'" + fast("") + "")) {
                    ps.setLong(1, MEMBER_TIMEOUT_MILLIS);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            members.add(rs.getString(1));
                        }
                    }
                }
                return members;
            });
        } catch (SQLException e) {
            throw new IOException("Failed to list members", e);
        }
    }

    /**
     * Claims one tick of one trigger of one item for this controller.
     *
     * @return true if this controller is the first (and only) one to claim it
     */
    public boolean claim(@NonNull String item, @NonNull String trigger, long tick) throws IOException {
        try {
            return withTransaction(c -> {
                try (PreparedStatement ps =
                        c.prepareStatement("insert into jenkins_trigger_claims (item, trigger, tick, origin)"
                                + " values (?, ?, ?, ?) on conflict do nothing")) {
                    ps.setString(1, item);
                    ps.setString(2, trigger);
                    ps.setLong(3, tick);
                    ps.setString(4, origin);
                    return ps.executeUpdate() == 1;
                }
            });
        } catch (SQLException e) {
            throw new IOException("Failed to claim " + trigger + " of " + item, e);
        }
    }

    private void pruneClaims() throws IOException {
        try {
            withTransaction(c -> {
                try (Statement s = c.createStatement()) {
                    s.executeUpdate("delete from jenkins_trigger_claims where claimed_at < now() - interval '1 day'");
                    s.executeUpdate("delete from jenkins_store_events where at < now() - interval '1 day'");
                }
                return null;
            });
        } catch (SQLException e) {
            throw new IOException("Failed to prune claims", e);
        }
    }

    @NonNull
    @Override
    public synchronized AutoCloseable subscribe(@NonNull Consumer<ChangeEvent> listener) {
        listeners.add(listener);
        if (listenerThread == null) {
            listenerThread = new Thread(this::listen, "PostgresPersistenceStore listener");
            listenerThread.setDaemon(true);
            listenerThread.start();
        }
        return () -> listeners.remove(listener);
    }

    /**
     * Follows {@code jenkins_store_events} from the current end, reconnecting as needed.
     */
    private void listen() {
        long lastSeq = -1;
        while (!closed) {
            try (Connection c = connect()) {
                try (Statement s = c.createStatement()) {
                    s.execute("listen " + CHANNEL);
                    s.execute("listen " + MEMBERS_CHANNEL);
                }
                if (lastSeq < 0) {
                    try (Statement s = c.createStatement();
                            ResultSet rs = s.executeQuery("select coalesce(max(seq), 0) from jenkins_store_events")) {
                        rs.next();
                        lastSeq = rs.getLong(1);
                    }
                }
                PGConnection pg = c.unwrap(PGConnection.class);
                while (!closed) {
                    lastSeq = drain(c, lastSeq);
                    org.postgresql.PGNotification[] notes = pg.getNotifications(1000); // wake up on NOTIFY, or poll every second
                    if (notes != null) {
                        for (org.postgresql.PGNotification n : notes) {
                            if (MEMBERS_CHANNEL.equals(n.getName())) {
                                wake.release(); // a controller left: the heartbeat thread compares members now
                            }
                        }
                    }
                }
            } catch (SQLException | RuntimeException e) {
                if (!closed) {
                    LOGGER.log(Level.WARNING, "Persistence store listener failed; reconnecting", e);
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException x) {
                        return;
                    }
                }
            }
        }
    }

    /**
     * Sequence numbers skipped so far, with when they were first skipped. A number is taken when an event is
     * inserted, not when its transaction commits, so a later event can become visible before an earlier one; the
     * earlier one is then delivered late, once it commits (or forgotten after {@link #HOLE_MILLIS}: its transaction
     * rolled back). Consumers compare versions, so a late event never undoes a newer change (Target 14).
     */
    private final java.util.NavigableMap<Long, Long> holes = new java.util.concurrent.ConcurrentSkipListMap<>();
    static final long HOLE_MILLIS = 60_000;

    private long drain(Connection c, long lastSeq) throws SQLException {
        long now = System.currentTimeMillis();
        holes.values().removeIf(since -> now - since > HOLE_MILLIS);
        StringBuilder sql = new StringBuilder("select seq, kind, key, old_key, version, origin from jenkins_store_events where seq > ?");
        List<Long> waiting = List.copyOf(holes.keySet());
        if (!waiting.isEmpty()) {
            sql.append(" or seq = any (?)");
        }
        sql.append(" order by seq");
        try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
            ps.setLong(1, lastSeq);
            if (!waiting.isEmpty()) {
                ps.setArray(2, c.createArrayOf("bigint", waiting.toArray()));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long seq = rs.getLong(1);
                    if (seq > lastSeq) {
                        for (long missing = lastSeq + 1; missing < seq && lastSeq >= 0; missing++) {
                            if (holes.size() < 10_000) {
                                holes.put(missing, now);
                            }
                        }
                        lastSeq = seq;
                    } else if (holes.remove(seq) == null) {
                        continue; // delivered already
                    }
                    ChangeEvent e = new ChangeEvent(
                            ChangeEvent.Kind.valueOf(rs.getString(2)),
                            rs.getString(3),
                            rs.getString(4),
                            rs.getLong(5),
                            rs.getString(6));
                    for (Consumer<ChangeEvent> l : listeners) {
                        try {
                            l.accept(e);
                        } catch (RuntimeException x) {
                            LOGGER.log(Level.WARNING, "Listener failed on " + e, x);
                        }
                    }
                }
            }
        }
        return lastSeq;
    }

    @Override
    public void close() {
        closed = true;
        try {
            // leave the cluster promptly so others take over our items without waiting for the timeout
            withControl(c -> {
                try (PreparedStatement ps = c.prepareStatement("delete from jenkins_members where origin = ?")) {
                    ps.setString(1, origin);
                    ps.executeUpdate();
                }
                try (Statement st = c.createStatement()) {
                    st.execute("notify " + MEMBERS_CHANNEL); // the others compare members at once
                }
                return null;
            });
        } catch (SQLException | RuntimeException e) {
            LOGGER.log(Level.FINE, "Failed to leave the cluster", e);
        }
        synchronized (this) {
            if (heartbeatThread != null) {
                heartbeatThread.interrupt();
            }
            if (lockThread != null) {
                lockThread.interrupt();
            }
            if (migrationThread != null) {
                migrationThread.interrupt();
            }
        }
        work.close();
        control.close();
    }

    private void event(Connection c, ChangeEvent.Kind kind, String key, String oldKey, long version)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "insert into jenkins_store_events (kind, key, old_key, version, origin) values (?, ?, ?, ?, ?)")) {
            ps.setString(1, kind.name());
            ps.setString(2, key);
            ps.setString(3, oldKey);
            ps.setLong(4, version);
            ps.setString(5, origin);
            ps.executeUpdate();
        }
        notifyChanged(c);
    }

    /** When this JVM last notified listeners of a change, {@link System#nanoTime}. */
    private volatile long lastNotify;
    private final java.util.concurrent.atomic.AtomicBoolean trailingNotify = new java.util.concurrent.atomic.AtomicBoolean();
    /** Notifications are at most this often per controller (Target 15); listeners also poll every second. */
    static final long NOTIFY_MILLIS = Long.getLong(PostgresPersistenceStore.class.getName() + ".notifyMillis", 100);

    /**
     * Wakes the other controllers' listeners. Postgres serialises the commits of transactions that notify, so during
     * write storms (branch indexing) a notification per write throttles every writer: within {@link #NOTIFY_MILLIS}
     * of the last one, a single notification is sent once that time is up instead.
     */
    private void notifyChanged(Connection c) throws SQLException {
        long now = System.nanoTime();
        if (now - lastNotify > TimeUnit.MILLISECONDS.toNanos(NOTIFY_MILLIS)) {
            lastNotify = now;
            try (Statement s = c.createStatement()) {
                s.execute("notify " + CHANNEL);
            }
        } else if (trailingNotify.compareAndSet(false, true)) {
            jenkins.util.Timer.get().schedule(() -> {
                trailingNotify.set(false);
                lastNotify = System.nanoTime();
                try {
                    withControl(cc -> {
                        try (Statement s = cc.createStatement()) {
                            s.execute("notify " + CHANNEL);
                        }
                        return null;
                    });
                } catch (SQLException | RuntimeException e) {
                    LOGGER.log(Level.FINE, "Could not notify listeners", e); // they poll every second anyway
                }
            }, NOTIFY_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    private static long nextVersion(Connection c) throws SQLException {
        try (Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("select nextval('jenkins_store_version')")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    /** Runs work in a transaction on a connection from the {@link #work} pool. */
    private <T> T withTransaction(Work<T> w) throws SQLException {
        long until = unreachableUntil;
        if (until != 0 && System.nanoTime() < until) {
            // fail fast while the database is unreachable, so that pages and the queue do not wait for it call after
            // call (Target 14); the heartbeat keeps probing on its own pool and closes this as soon as it gets through
            throw new java.sql.SQLTransientConnectionException("The persistence store is unreachable; retrying shortly");
        }
        try {
            T result = withTransaction(work, w);
            unreachableUntil = 0;
            return result;
        } catch (SQLException e) {
            if (isConnectionFailure(e)) {
                unreachableUntil = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FAIL_FAST_MILLIS);
            }
            throw e;
        }
    }

    /** {@link System#nanoTime} until which ordinary store calls fail at once (0: they do not). */
    private volatile long unreachableUntil;
    static final long FAIL_FAST_MILLIS = Long.getLong(PostgresPersistenceStore.class.getName() + ".failFastMillis", 5_000);

    /** Whether the database was found unreachable lately (calls fail fast). */
    boolean isUnreachable() {
        long until = unreachableUntil;
        return until != 0 && System.nanoTime() < until;
    }

    /** Runs work in a transaction on a connection from the {@link #control} pool. */
    private <T> T withControl(Work<T> w) throws SQLException {
        return withTransaction(control, w);
    }

    /**
     * Runs work in a transaction on a pooled connection. If the connection broke before the commit was sent (nothing
     * can have been written), it is retried once on another connection; never after, so that work is not done twice
     * when only the commit's acknowledgement was lost.
     */
    private <T> T withTransaction(com.zaxxer.hikari.HikariDataSource pool, Work<T> w) throws SQLException {
        for (int attempt = 0; ; attempt++) {
            boolean committing = false;
            try (Connection c = pool.getConnection()) {
                try {
                    T result = w.run(c);
                    committing = true;
                    c.commit();
                    return result;
                } catch (Conflict e) {
                    c.rollback();
                    throw e;
                } catch (SQLException | RuntimeException e) {
                    try {
                        c.rollback();
                    } catch (SQLException x) {
                        e.addSuppressed(x);
                    }
                    throw e;
                }
            } catch (Conflict e) {
                throw e;
            } catch (SQLException e) {
                if (attempt == 0 && !committing && isConnectionFailure(e) && !(e instanceof java.sql.SQLTransientConnectionException)) {
                    LOGGER.log(Level.FINE, "Retrying on another connection", e);
                    continue;
                }
                throw e;
            }
        }
    }

    /** SQL state class 08 (connection exception), or a timeout reaching the database. */
    static boolean isConnectionFailure(SQLException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException se && se.getSQLState() != null && se.getSQLState().startsWith("08")) {
                return true;
            }
            if (t instanceof java.sql.SQLTransientConnectionException || t instanceof java.net.SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    /** Connection limits, so that an unreachable or stalled database fails calls instead of hanging them. */
    static final int SOCKET_TIMEOUT_SECONDS = Integer.getInteger(PostgresPersistenceStore.class.getName() + ".socketTimeoutSeconds", 30);
    static final int CONNECT_TIMEOUT_SECONDS = Integer.getInteger(PostgresPersistenceStore.class.getName() + ".connectTimeoutSeconds", 5);
    static final int STATEMENT_TIMEOUT_MILLIS = Integer.getInteger(PostgresPersistenceStore.class.getName() + ".statementTimeoutMillis", 25_000);
    static final int LOCK_TIMEOUT_MILLIS = Integer.getInteger(PostgresPersistenceStore.class.getName() + ".lockTimeoutMillis", 10_000);
    static final int WORK_POOL_SIZE = Integer.getInteger(PostgresPersistenceStore.class.getName() + ".poolSize", 8);

    /** Uses the driver directly: {@code DriverManager} does not see drivers loaded by a plugin class loader. */
    private static final class DirectDataSource extends org.postgresql.ds.PGSimpleDataSource {
        @Override
        public Connection getConnection(String user, String password) throws SQLException {
            Properties props = new Properties();
            if (user != null) {
                props.setProperty("user", user);
            }
            if (password != null) {
                props.setProperty("password", password);
            }
            Connection c = new org.postgresql.Driver().connect(getUrl(), props); // getUrl() carries every property set
            if (c == null) {
                throw new SQLException("Not a PostgreSQL JDBC URL: " + getUrl());
            }
            return c;
        }
    }

    private org.postgresql.ds.PGSimpleDataSource dataSource() {
        org.postgresql.ds.PGSimpleDataSource ds = new DirectDataSource();
        ds.setUrl(url);
        ds.setUser(user);
        ds.setPassword(password);
        ds.setSocketTimeout(SOCKET_TIMEOUT_SECONDS);
        ds.setConnectTimeout(CONNECT_TIMEOUT_SECONDS);
        ds.setLoginTimeout(CONNECT_TIMEOUT_SECONDS * 2);
        ds.setTcpKeepAlive(true);
        ds.setOptions("-c statement_timeout=" + STATEMENT_TIMEOUT_MILLIS + " -c lock_timeout=" + LOCK_TIMEOUT_MILLIS
                + " -c idle_in_transaction_session_timeout=60000");
        return ds;
    }

    private com.zaxxer.hikari.HikariDataSource pool(String name, int size) {
        com.zaxxer.hikari.HikariConfig config = new com.zaxxer.hikari.HikariConfig();
        config.setDataSource(dataSource());
        config.setPoolName("cnj-store-" + name);
        config.setAutoCommit(false);
        config.setMaximumPoolSize(size);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(TimeUnit.SECONDS.toMillis(CONNECT_TIMEOUT_SECONDS)); // waiting for a connection
        config.setValidationTimeout(TimeUnit.SECONDS.toMillis(CONNECT_TIMEOUT_SECONDS));
        config.setInitializationFailTimeout(-1); // created even while the database is unreachable; calls fail until it is back
        config.setKeepaliveTime(TimeUnit.MINUTES.toMillis(1));
        return new com.zaxxer.hikari.HikariDataSource(config);
    }

    /** A dedicated connection (the listener's), with the same limits as the pools. */
    private Connection connect() throws SQLException {
        return dataSource().getConnection();
    }

    /** A {@code LIKE} pattern matching keys starting with a prefix ({@code %}, {@code _} and {@code \\} escaped). */
    static String likePrefix(String prefix) {
        return prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private static byte[] sha256(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    /** Carries a {@link ConflictException} out of a transaction so that it is rolled back. */
    private static final class Conflict extends SQLException {
        final ConflictException conflict;

        Conflict(ConflictException conflict) {
            super(conflict.getMessage());
            this.conflict = conflict;
        }
    }
}
