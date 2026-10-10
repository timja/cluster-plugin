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
                try (PreparedStatement ps =
                        c.prepareStatement("insert into jenkins_members (origin, heartbeat_at) values (?, now())"
                                + " on conflict (origin) do update set heartbeat_at = now()")) {
                    ps.setString(1, origin);
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
