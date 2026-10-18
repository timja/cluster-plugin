package io.jenkins.plugins.cluster;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.XmlFile;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.AsyncPeriodicWork;
import hudson.model.PeriodicWork;
import hudson.model.Saveable;
import hudson.model.TaskListener;
import hudson.model.User;
import hudson.model.listeners.SaveableListener;
import hudson.util.DaemonThreadFactory;
import hudson.util.NamingThreadFactory;
import hudson.util.PluginServletFilter;
import io.jenkins.plugins.cluster.spi.ClusterStore;
import io.jenkins.plugins.cluster.spi.Keys;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import jenkins.persistence.ConflictException;
import jenkins.persistence.PersistenceStore;
import jenkins.persistence.RemoteChangeHandler;
import jenkins.security.LoginRevocation;
import jenkins.security.seed.UserSeedProperty;
import jenkins.util.ClusterSingleton;
import jenkins.util.SystemProperties;
import jenkins.util.Timer;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

/**
 * Signing out ends that login on every controller of the cluster, and deleting a user ends their sessions on every
 * controller.
 *
 * <p>Each controller makes its own HTTP session from a login's remember-me cookie. Signing out on one records the
 * login's id in the store ({@link Keys#revokedLogin}); every controller keeps the ids it learnt in memory, rejects the
 * cookie and drops the authentication of its sessions of that login (core does both, asking {@link #isRevoked}). The
 * user's other logins, with other cookies, stay signed in.
 *
 * <p>A revocation is kept until the login's cookie expires (the controller's own clock), plus at least a day and at
 * least the session timeout: a session made from the cookie before the revocation arrived can outlive the cookie,
 * and is signed out on its next request. The store's entries are then deleted once per cluster. A deletion seen
 * from elsewhere never brings a login back: each controller forgets a revocation on its own clock only.
 *
 * <p>A user deleted on one controller is recorded for a day, and at least the session timeout ({@link
 * Keys#deletedUser}): a session of theirs on any controller is signed out on its next request while the user does not
 * exist there, or only as a new user of that name. (Their cookie no longer signs in: it is signed with their seed.)
 *
 * <p>Without a cluster store nothing is recorded, and signing out is local, as without this plugin.
 */
@Extension(optional = true)
@Restricted(NoExternalUse.class)
public final class ClusterLoginRevocation implements LoginRevocation, RemoteChangeHandler {

    private static final Logger LOGGER = Logger.getLogger(ClusterLoginRevocation.class.getName());

    static final String REVOKED = parent(Keys.revokedLogin("x"));
    static final String DELETED_USERS = parent(Keys.deletedUser("x"));

    /** A login id as core makes them: a Base64url SHA-256, unpadded. Anything else is no key of ours. */
    private static final Pattern LOGIN_ID = Pattern.compile("[A-Za-z0-9_-]{43}");

    /** The session attributes core records a login in ({@code TokenBasedRememberMeServices2}). */
    static final String SESSION_LOGIN_ID = LoginRevocation.class.getName() + ".loginId";

    static final String SESSION_LOGIN_EXPIRES = LoginRevocation.class.getName() + ".expiresAt";

    /** How long, at least, a revocation is kept after the login's cookie expires (and a deleted user is recorded). */
    @SuppressFBWarnings(value = "MS_SHOULD_BE_FINAL", justification = "for script console")
    public static /* Script Console modifiable */ long GRACE_MINUTES = SystemProperties.getLong(
            ClusterLoginRevocation.class.getName() + ".graceMinutes", TimeUnit.DAYS.toMinutes(1));

    /** The longest a remember-me cookie is valid (core caps its validity to a year). */
    static final Duration MAX_LOGIN = Duration.ofDays(365);

    /** Revoked logins, by id, and until when this controller keeps each one. */
    private final Map<String, Long> revoked = new ConcurrentHashMap<>();

    /** Recently deleted users, by the key of their id (encoded), and until when. */
    private final Map<String, Long> deletedUsers = new ConcurrentHashMap<>();

    /** Reads other controllers' changes off the store's event thread, in order. */
    private final ExecutorService events = Executors.newSingleThreadExecutor(
            new NamingThreadFactory(new DaemonThreadFactory(), "ClusterLoginRevocation"));

    private static String parent(String key) {
        return key.substring(0, key.lastIndexOf('/'));
    }

    static ClusterLoginRevocation get() {
        return ExtensionList.lookupSingleton(ClusterLoginRevocation.class);
    }

    /** User ids may hold any character; a key segment may not hold {@code /}. */
    static String userSegment(String id) {
        return URLEncoder.encode(User.idStrategy().keyFor(id), StandardCharsets.UTF_8);
    }

    /** At least {@link #GRACE_MINUTES}, and at least the session timeout. */
    static long grace() {
        long minutes = GRACE_MINUTES;
        Jenkins j = Jenkins.getInstanceOrNull();
        ServletContext context = j == null ? null : j.getServletContext();
        if (context != null) {
            try {
                minutes = Math.max(minutes, context.getSessionTimeout());
            } catch (RuntimeException e) {
                LOGGER.log(Level.FINE, "No session timeout", e);
            }
        }
        return TimeUnit.MINUTES.toMillis(Math.max(minutes, 0));
    }

    /** The longest anything is kept, however far away a stored expiry is: no entry stays forever. */
    private static long latest(long now) {
        return now + MAX_LOGIN.toMillis() + grace();
    }

    /** Until when to keep a revocation of a login whose cookie expires at {@code expiresAt}. */
    static long keepUntil(long expiresAt, long now) {
        return Math.min(Math.max(expiresAt, now) + grace(), latest(now));
    }

    // ------------------------------------------------------------------ LoginRevocation

    @Override
    public void revoke(@NonNull String loginId, @NonNull String username, long expiresAt) throws IOException {
        ClusterStore store = ClusterStore.get();
        if (store == null) {
            return; // signing out is local, as without a cluster
        }
        if (!LOGIN_ID.matcher(loginId).matches()) {
            throw new IllegalArgumentException("Not a login id");
        }
        long until = keepUntil(expiresAt, System.currentTimeMillis());
        revoked.merge(loginId, until, Math::max); // here at once, whatever the store does
        publish(store, Keys.revokedLogin(loginId), entry(username, until), until, 0);
    }

    @Override
    public boolean isRevoked(@NonNull String loginId) {
        return revoked.containsKey(loginId);
    }

    /** Writes an entry, trying again in the background (until it would expire) if the store cannot be written now. */
    private void publish(ClusterStore store, String key, byte[] content, long until, int attempt) throws IOException {
        try {
            store.write(key, content, PersistenceStore.ANY);
        } catch (IOException e) {
            if (System.currentTimeMillis() < until) {
                long delay =
                        Math.min(TimeUnit.SECONDS.toMillis(10) << Math.min(attempt, 5), TimeUnit.MINUTES.toMillis(5));
                Timer.get()
                        .schedule(
                                () -> {
                                    try {
                                        publish(store, key, content, until, attempt + 1);
                                    } catch (IOException again) {
                                        LOGGER.log(Level.FINE, "Still cannot write " + key + "; trying again", again);
                                    }
                                },
                                delay,
                                TimeUnit.MILLISECONDS);
            }
            throw e;
        }
    }

    private static byte[] entry(String user, long until) {
        Properties p = new Properties();
        p.setProperty("user", user);
        p.setProperty("until", Long.toString(until));
        StringWriter w = new StringWriter();
        try {
            p.store(w, null);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return w.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Until when an entry is to be kept, bounded however far away it says; {@code -1} if unreadable. */
    static long until(@CheckForNull PersistenceStore.Entry entry, long now) {
        if (entry == null) {
            return -1;
        }
        Properties p = new Properties();
        try {
            p.load(new StringReader(new String(entry.content(), StandardCharsets.UTF_8)));
            return Math.min(Long.parseLong(p.getProperty("until", "-1")), latest(now));
        } catch (IOException | IllegalArgumentException e) {
            LOGGER.log(Level.WARNING, "Unreadable sign-out record", e);
            return -1;
        }
    }

    // ------------------------------------------------------------------ deleted users

    /** Records a user deleted here, so that their sessions on every controller end. */
    @Extension(optional = true)
    @Restricted(NoExternalUse.class)
    public static final class DeletedUsers extends SaveableListener {
        @Override
        public void onDeleted(Saveable o, XmlFile file) {
            ClusterStore store = ClusterStore.get();
            if (!(o instanceof User user) || store == null) {
                return;
            }
            String segment = userSegment(user.getId());
            long until = System.currentTimeMillis() + grace();
            get().deletedUsers.merge(segment, until, Math::max);
            try {
                get().publish(store, Keys.deletedUser(segment), entry(user.getId(), until), until, 0);
            } catch (IOException | RuntimeException e) { // the deletion itself stands
                LOGGER.log(
                        Level.WARNING,
                        "Could not tell the other controllers that " + user.getId() + " was deleted; trying again",
                        e);
            }
        }
    }

    /**
     * Signs out a session of a user deleted on any controller, while the user is gone here (see {@link #isGone}): core
     * keeps a session of a user it does not know. Runs after authentication, before the request is handled.
     */
    static final class SignOutDeletedUsers implements Filter {
        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            ClusterLoginRevocation revocation = get();
            if (!revocation.deletedUsers.isEmpty() && request instanceof HttpServletRequest req) {
                Authentication a = SecurityContextHolder.getContext().getAuthentication();
                HttpSession session = req.getSession(false);
                if (a != null
                        && !(a instanceof AnonymousAuthenticationToken)
                        && session != null
                        && session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)
                                != null
                        && revocation.deletedUsers.containsKey(userSegment(a.getName()))
                        && isGone(a.getName(), session)) {
                    LOGGER.fine(() -> "Signing out a session of " + a.getName() + ", who was deleted");
                    if (session.getAttribute(SESSION_LOGIN_ID) instanceof String loginId) {
                        try {
                            revocation.revoke(
                                    loginId,
                                    a.getName(),
                                    session.getAttribute(SESSION_LOGIN_EXPIRES) instanceof Long l ? l : 0);
                        } catch (IOException | RuntimeException e) {
                            LOGGER.log(Level.FINE, "Could not record the login of a deleted user as signed out", e);
                        }
                    }
                    session.removeAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
                    SecurityContextHolder.clearContext(); // this request is anonymous, as the next ones
                }
            }
            chain.doFilter(request, response);
        }
    }

    /**
     * Whether the user a session signed in as is gone: not here, or here again only as a new user of that name (made
     * since, here or by a later sign-in elsewhere), whose seed is not the session's.
     */
    static boolean isGone(String name, HttpSession session) {
        User user = User.getById(name, false);
        if (user == null) {
            return true;
        }
        UserSeedProperty seed = user.getProperty(UserSeedProperty.class);
        return seed != null
                && session.getAttribute(UserSeedProperty.USER_SESSION_SEED) instanceof String sessionSeed
                && !sessionSeed.equals(seed.getSeed());
    }

    // ------------------------------------------------------------------ inbound

    @Override
    public boolean handles(@NonNull String key) {
        return key.startsWith(REVOKED + "/") || key.startsWith(DELETED_USERS + "/");
    }

    @Override
    public void changed(@NonNull String key, boolean deleted) {
        if (deleted) {
            return; // forgotten on this controller's own clock: a deletion never brings a login back
        }
        events.submit(() -> apply(key));
    }

    /** Waits until the changes handed over so far are applied (tests). */
    void flush() throws Exception {
        events.submit(() -> {}).get(60, TimeUnit.SECONDS);
    }

    /** Learns an entry as it is in the store now. */
    void apply(String key) {
        ClusterStore store = ClusterStore.get();
        if (store == null) {
            return;
        }
        Map<String, Long> into;
        String segment;
        if (key.startsWith(REVOKED + "/")) {
            into = revoked;
            segment = key.substring(REVOKED.length() + 1);
            if (!LOGIN_ID.matcher(segment).matches()) {
                LOGGER.warning(() -> "Ignoring " + key + ": not a login id");
                return;
            }
        } else if (key.startsWith(DELETED_USERS + "/")) {
            into = deletedUsers;
            segment = key.substring(DELETED_USERS.length() + 1);
            if (segment.isEmpty() || segment.contains("/")) {
                LOGGER.warning(() -> "Ignoring " + key + ": not a user");
                return;
            }
        } else {
            return;
        }
        long now = System.currentTimeMillis();
        try {
            long until = until(store.read(key), now);
            if (until > now) {
                into.merge(segment, until, Math::max);
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Cannot read " + key + "; trying again shortly", e);
            Timer.get().schedule(() -> events.submit(() -> apply(key)), 10, TimeUnit.SECONDS);
        }
    }

    /** Learns every entry of the store this controller does not know yet, and forgets the expired ones. */
    void refresh() throws IOException {
        long now = System.currentTimeMillis();
        revoked.values().removeIf(until -> until <= now);
        deletedUsers.values().removeIf(until -> until <= now);
        ClusterStore store = ClusterStore.get();
        if (store == null) {
            return;
        }
        for (String id : store.children(REVOKED)) {
            if (!revoked.containsKey(id)) {
                apply(REVOKED + "/" + id);
            }
        }
        for (String user : store.children(DELETED_USERS)) {
            if (!deletedUsers.containsKey(user)) {
                apply(DELETED_USERS + "/" + user);
            }
        }
    }

    /** Before the security realm is loaded, so before a cookie can sign in. */
    @Initializer(after = InitMilestone.EXTENSIONS_AUGMENTED, before = InitMilestone.SYSTEM_CONFIG_LOADED)
    public static void start() throws IOException, ServletException {
        if (ClusterStore.get() == null) {
            return;
        }
        get().refresh();
        PluginServletFilter.addFilter(new SignOutDeletedUsers());
    }

    /** Picks up any entry an event was missed for, and forgets the expired ones, on every controller. */
    @Extension(optional = true)
    @Restricted(NoExternalUse.class)
    public static final class Refresh extends PeriodicWork {
        @Override
        public long getRecurrencePeriod() {
            return TimeUnit.MINUTES.toMillis(10);
        }

        @Override
        protected void doRun() throws Exception {
            get().refresh();
        }
    }

    /** Deletes the expired entries from the store, once per cluster. */
    @ClusterSingleton
    @Extension(optional = true)
    @Restricted(NoExternalUse.class)
    public static final class Cleanup extends AsyncPeriodicWork {
        public Cleanup() {
            super("Expired sign-out records cleanup");
        }

        @Override
        public long getRecurrencePeriod() {
            return HOUR;
        }

        @Override
        protected void execute(TaskListener listener) throws IOException {
            ClusterStore store = ClusterStore.get();
            if (store != null) {
                listener.getLogger().println("Deleted " + deleteExpired(store) + " expired sign-out records");
            }
        }
    }

    /** Deletes the entries expired by this controller's clock, at the version read. */
    static int deleteExpired(ClusterStore store) throws IOException {
        int deleted = 0;
        long now = System.currentTimeMillis();
        for (String parent : new String[] {REVOKED, DELETED_USERS}) {
            for (String child : store.children(parent)) {
                String key = parent + "/" + child;
                PersistenceStore.Entry entry = store.read(key);
                if (entry != null && until(entry, now) <= now) {
                    try {
                        store.delete(key, entry.version());
                        deleted++;
                    } catch (ConflictException e) {
                        // written again meanwhile (signed out again): kept
                    }
                }
            }
        }
        return deleted;
    }
}
