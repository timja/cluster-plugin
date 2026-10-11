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
    /** Advisory lock serializing schema creation between controllers. */
    private static final long SCHEMA_LOCK = 0x6a656e6b696e73L;
    private static final long COUNTERS_LOCK = 0x636e6a636e7472L;

    private final String url;
    private final String user;
    private final String password;
    private final Predicate<String> owns;
    private final String origin = UUID.randomUUID().toString();
    private Connection connection;

    private final List<Consumer<ChangeEvent>> listeners = new CopyOnWriteArrayList<>();
    private Thread listenerThread;
    private volatile boolean closed;

    public PostgresPersistenceStore(String url, String user, String password, Predicate<String> owns)
            throws IOException {
        this.url = url;
        this.user = user;
        this.password = password;
        this.owns = owns;
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
                            c.prepareStatement("insert into jenkins_store (key, content, sha256, version, updated_by)"
                                    + " values (?, ?, ?, ?, ?) on conflict (key) do nothing")) {
                        ps.setString(1, key);
                        ps.setBytes(2, content);
                        ps.setBytes(3, sha);
                        ps.setLong(4, version);
                        ps.setString(5, origin);
                        if (ps.executeUpdate() == 0) {
                            // created concurrently by someone else since our select
                            throw new Conflict(new ConflictException(key, expectedVersion, -1));
                        }
                    }
                } else {
                    try (PreparedStatement ps =
                            c.prepareStatement("update jenkins_store set content = ?, sha256 = ?, version = ?,"
                                    + " updated_at = now(), updated_by = ? where key = ?")) {
                        ps.setBytes(1, content);
                        ps.setBytes(2, sha);
                        ps.setLong(3, version);
                        ps.setString(4, origin);
                        ps.setString(5, key);
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
                try (PreparedStatement ps = c.prepareStatement(
                        "select distinct split_part(substr(key, ?), '/', 1) from jenkins_store where starts_with(key, ?)")) {
                    ps.setInt(1, prefix.length() + 1);
                    ps.setString(2, prefix);
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

    @Override
    public void move(@NonNull String fromPrefix, @NonNull String toPrefix) throws IOException {
        try {
            withTransaction(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "select 1 from jenkins_store where key = ? or starts_with(key, ?) limit 1")) {
                    ps.setString(1, toPrefix);
                    ps.setString(2, toPrefix + "/");
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            throw new Conflict(new ConflictException(toPrefix, ABSENT, -1));
                        }
                    }
                }
                List<String> keys = new ArrayList<>();
                try (PreparedStatement ps = c.prepareStatement(
                        "select key from jenkins_store where key = ? or starts_with(key, ?) order by length(key), key for update")) {
                    ps.setString(1, fromPrefix);
                    ps.setString(2, fromPrefix + "/");
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
            Long.getLong(PostgresPersistenceStore.class.getName() + ".heartbeatMillis", 5_000);
    /** How long after its last heartbeat a controller is considered gone. */
    static final long MEMBER_TIMEOUT_MILLIS =
            Long.getLong(PostgresPersistenceStore.class.getName() + ".memberTimeoutMillis", 20_000);

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
        return System.nanoTime() - lastHeartbeatOk > TimeUnit.MILLISECONDS.toNanos(MEMBER_TIMEOUT_MILLIS);
    }

    private final BuildLeases leases = new BuildLeases() {
        @Override
        public long acquire(@NonNull String key) throws IOException {
            try {
                return withTransaction(c -> {
                    try (PreparedStatement ps = c.prepareStatement(
                            "insert into jenkins_build_leases (key, owner, epoch) values (?, ?, nextval('jenkins_store_version'))"
                                    + " on conflict (key) do update set owner = excluded.owner, epoch = excluded.epoch, acquired_at = now()"
                                    + " where jenkins_build_leases.owner <> excluded.owner and not exists (select 1 from jenkins_members m"
                                    + " where m.origin = jenkins_build_leases.owner and m.heartbeat_at > now() - ? * interval '1 millisecond')"
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
            if (mayHaveLapsed()) {
                return false;
            }
            try {
                return withTransaction(c -> {
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
                withTransaction(c -> {
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
        return run("reclaim queue items", c -> {
            List<Long> ids = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "update jenkins_queue set state = 'WAITING', claimed_by = null, updated_at = now()"
                            + " where state = 'CLAIMED' and claimed_by not in (select origin from jenkins_members"
                            + " where heartbeat_at > now() - ? * interval '1 millisecond') returning id")) {
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
        heartbeat();
        if (heartbeatThread == null) {
            heartbeatThread = new Thread(
                    () -> {
                        long lastPrune = 0;
                        while (!closed) {
                            try {
                                Thread.sleep(HEARTBEAT_MILLIS);
                                heartbeat();
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

    void heartbeat() throws IOException {
        try {
            withTransaction(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "insert into jenkins_members (origin, heartbeat_at, name, url, version, protocol, plugins) values (?, now(), ?, ?, ?, ?, ?)"
                                + " on conflict (origin) do update set heartbeat_at = now(), name = excluded.name, url = excluded.url,"
                                + " version = excluded.version, protocol = excluded.protocol, plugins = excluded.plugins")) {
                    ps.setString(1, origin);
                    ps.setString(2, NAME);
                    ps.setString(3, INTERNAL_URL);
                    ps.setString(4, VERSION);
                    ps.setInt(5, PROTOCOL);
                    ps.setString(6, pluginsStatus);
                    ps.executeUpdate();
                }
                return null;
            });
            long now = System.nanoTime();
            if (now - lastHeartbeatOk > TimeUnit.MILLISECONDS.toNanos(MEMBER_TIMEOUT_MILLIS)) {
                lapses.incrementAndGet(); // others may have taken over our builds meanwhile
            }
            lastHeartbeatOk = now;
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
    static final int PROTOCOL = 1;

    /** What this controller runs: core and plugin versions, shown in the cluster view. */
    static final String VERSION = jenkins.model.Jenkins.VERSION + " / " + java.util.Objects.requireNonNullElse(
            PostgresPersistenceStore.class.getPackage().getImplementationVersion(), "dev");

    /** What this controller has applied of the cluster's plugin set ({@link ClusterPlugins}), published with each heartbeat. */
    static volatile String pluginsStatus;

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
                    + " where origin <> ? and coalesce(protocol, 0) < ? and heartbeat_at > now() - ? * interval '1 millisecond'")) {
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
                    + " and heartbeat_at > now() - ? * interval '1 millisecond' order by heartbeat_at desc limit 1")) {
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
            String plugins) {}

    /** Every controller in the membership table, newest first. */
    @NonNull
    List<Member> members() throws IOException {
        return run("list members", c -> {
            List<Member> result = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("select origin, name, url, heartbeat_at, started_at,"
                    + " heartbeat_at > now() - ? * interval '1 millisecond', version, coalesce(protocol, 0), plugins from jenkins_members"
                    + " order by started_at desc")) {
                ps.setLong(1, MEMBER_TIMEOUT_MILLIS);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.add(new Member(rs.getString(1), rs.getString(2), rs.getString(3),
                                rs.getTimestamp(4).getTime(), rs.getTimestamp(5).getTime(), rs.getBoolean(6), rs.getString(7), rs.getInt(8), rs.getString(9)));
                    }
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
                    + " where m.heartbeat_at > now() - ? * interval '1 millisecond'")) {
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
                        + " where k.scope = ? and (k.origin = ? or m.heartbeat_at > now() - ? * interval '1 millisecond')")) {
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
                    + " and m.heartbeat_at > now() - ? * interval '1 millisecond'")) {
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
                    + " and heartbeat_at > now() - ? * interval '1 millisecond'")) {
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
            return withTransaction(c -> {
                Set<String> members = new TreeSet<>();
                members.add(origin);
                try (PreparedStatement ps = c.prepareStatement(
                        "select origin from jenkins_members where heartbeat_at > now() - ? * interval '1 millisecond'")) {
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
                    pg.getNotifications(1000); // wake up on NOTIFY, or poll every second
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

    private long drain(Connection c, long lastSeq) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "select seq, kind, key, old_key, version, origin from jenkins_store_events where seq > ? order by seq")) {
            ps.setLong(1, lastSeq);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lastSeq = rs.getLong(1);
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
            withTransaction(c -> {
                try (PreparedStatement ps = c.prepareStatement("delete from jenkins_members where origin = ?")) {
                    ps.setString(1, origin);
                    ps.executeUpdate();
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
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    LOGGER.log(Level.FINE, null, e);
                }
                connection = null;
            }
        }
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
        try (Statement s = c.createStatement()) {
            s.execute("notify " + CHANNEL);
        }
    }

    private static long nextVersion(Connection c) throws SQLException {
        try (Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("select nextval('jenkins_store_version')")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    /**
     * Runs work in a transaction on the shared connection, reconnecting once if the connection was lost.
     */
    private synchronized <T> T withTransaction(Work<T> work) throws SQLException {
        for (int attempt = 0; ; attempt++) {
            Connection c = connection();
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (Conflict e) {
                c.rollback();
                throw e;
            } catch (SQLException e) {
                try {
                    c.rollback();
                } catch (SQLException x) {
                    e.addSuppressed(x);
                }
                if (attempt == 0 && !c.isValid(2)) {
                    LOGGER.log(Level.INFO, "Reconnecting to persistence store", e);
                    connection = null;
                    continue;
                }
                throw e;
            }
        }
    }

    private Connection connection() throws SQLException {
        if (connection == null) {
            connection = connect();
            connection.setAutoCommit(false);
        }
        return connection;
    }

    /** Uses the driver directly: {@code DriverManager} does not see drivers loaded by a plugin class loader. */
    private Connection connect() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", user);
        props.setProperty("password", password);
        Connection c = new org.postgresql.Driver().connect(url, props);
        if (c == null) {
            throw new SQLException("Not a PostgreSQL JDBC URL: " + url);
        }
        return c;
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
