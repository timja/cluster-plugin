package io.jenkins.plugins.cluster.locks;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.util.DaemonThreadFactory;
import hudson.util.NamingThreadFactory;
import io.jenkins.plugins.cluster.spi.ClusterStore;
import io.jenkins.plugins.cluster.spi.Keys;
import io.jenkins.plugins.cluster.spi.Resync;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.persistence.ConflictException;
import jenkins.persistence.PersistenceStore;
import jenkins.persistence.RemoteChangeHandler;
import jenkins.util.Timer;
import org.jenkins.plugins.lockableresources.LockableResource;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.jenkins.plugins.lockableresources.ResourceState;
import org.jenkins.plugins.lockableresources.ResourceStateStore;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Keeps who holds each lockable resource in the cluster's store, as {@link Keys#lock}: one holder for the whole
 * cluster. A build is named by its externalizable id, so a build resumed on another controller (after a restart, or
 * adopted after its controller died) still holds what it held, with nothing to take over.
 *
 * <p>Every controller keeps its own resources (as from configuration as code) and its own queue of waiting builds;
 * only the holders are shared. This controller's view of them is a cache, kept up to date from the store's events and
 * read without I/O. A change is a write at the version this controller knows (creating the key if it knows none, deleting
 * it once nobody holds the resource): if another controller changed the key meanwhile, the write fails, the key is read
 * again and the change is worked out again from what is there now.
 */
@Extension(optional = true)
@Restricted(NoExternalUse.class)
public final class ClusterResourceStates extends ResourceStateStore implements RemoteChangeHandler {

    private static final Logger LOGGER = Logger.getLogger(ClusterResourceStates.class.getName());

    static final String LOCKS = Keys.lock("x").substring(0, Keys.lock("x").lastIndexOf('/'));

    /** How often an update worked out again after a conflict is tried, before giving up. */
    private static final int ATTEMPTS = 5;

    /** A held resource's state and the version of its key, by resource name. Free resources have none. */
    private final Map<String, Known> known = new ConcurrentHashMap<>();

    /** Reads keys changed by other controllers off the store's event thread, in order. */
    private final ExecutorService events = Executors.newSingleThreadExecutor(
            new NamingThreadFactory(new DaemonThreadFactory(), "ClusterResourceStates"));

    record Known(@NonNull ResourceState state, long version) {}

    static ClusterResourceStates instance() {
        return ExtensionList.lookupSingleton(ClusterResourceStates.class);
    }

    @CheckForNull
    static ClusterStore store() {
        return ClusterStore.get();
    }

    static String key(String resource) {
        return Keys.lock(URLEncoder.encode(resource, StandardCharsets.UTF_8));
    }

    static String name(String key) {
        return URLDecoder.decode(key.substring(LOCKS.length() + 1), StandardCharsets.UTF_8);
    }

    @Override
    public boolean isEnabled() {
        return store() != null;
    }

    @Override
    public boolean isShared() {
        return true;
    }

    @NonNull
    @Override
    public ResourceState get(@NonNull LockableResource resource) {
        Known k = known.get(resource.getName());
        return k == null ? ResourceState.FREE : k.state();
    }

    @Override
    public boolean update(@NonNull List<LockableResource> resources, @NonNull UnaryOperator<ResourceState> change) {
        ClusterStore store = store();
        if (store == null) {
            return false;
        }
        // in the order of their names, as on every controller: of two taking the same resources, the one taking the
        // first wins, rather than each taking some
        List<String> names = new ArrayList<>();
        for (LockableResource r : resources) {
            names.add(r.getName());
        }
        names.sort(Comparator.naturalOrder());
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            List<Written> written = new ArrayList<>();
            try {
                boolean conflict = false;
                for (String name : names) {
                    Known before = known.get(name);
                    ResourceState current = before == null ? ResourceState.FREE : before.state();
                    ResourceState next = change.apply(current);
                    if (next == null) {
                        rollback(store, written);
                        return false;
                    }
                    if (next.equals(current)) {
                        continue;
                    }
                    try {
                        written.add(new Written(name, before, write(store, name, before, next)));
                    } catch (ConflictException e) {
                        refresh(store, name);
                        conflict = true;
                        break;
                    }
                }
                if (!conflict) {
                    return true;
                }
                rollback(store, written);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Cannot change " + names + " across controllers; trying again shortly", e);
                rollback(store, written);
                Timer.get().schedule(() -> LockableResourcesManager.get().refreshQueue(), 10, TimeUnit.SECONDS);
                return false;
            }
        }
        LOGGER.warning(() -> "Gave up changing " + names + ": changed on other controllers each time");
        return false;
    }

    /** A key written by an update, and what this controller knew of it before. */
    private record Written(String name, @CheckForNull Known before, @CheckForNull Known after) {}

    /**
     * Writes a resource's next state at the version this controller knows: creating the key, replacing it, or deleting
     * it if nobody holds the resource any more.
     *
     * @return what this controller knows of it now, or null if it is free
     */
    @CheckForNull
    private Known write(ClusterStore store, String name, @CheckForNull Known before, ResourceState next)
            throws IOException {
        long expected = before == null ? PersistenceStore.ABSENT : before.version();
        Known after;
        if (next.isFree()) {
            if (before != null) {
                store.delete(key(name), expected);
            }
            after = null;
        } else {
            after = new Known(next, store.write(key(name), toBytes(next, store.getOrigin()), expected));
        }
        remember(name, after);
        return after;
    }

    /** Puts back what an update wrote before it failed, unless another controller changed it meanwhile. */
    private void rollback(ClusterStore store, List<Written> written) {
        for (Written w : written) {
            Known before = w.before();
            Known after = w.after();
            try {
                long version = after == null ? PersistenceStore.ABSENT : after.version();
                if (before == null) {
                    if (after != null) {
                        store.delete(key(w.name()), version);
                    }
                    remember(w.name(), null);
                } else {
                    remember(
                            w.name(),
                            new Known(
                                    before.state(),
                                    store.write(key(w.name()), toBytes(before.state(), store.getOrigin()), version)));
                }
            } catch (IOException e) { // a conflict too: theirs now
                LOGGER.log(Level.FINE, "Cannot put " + w.name() + " back; reading it again", e);
                events.submit(() -> refreshAndNotify(w.name()));
            }
        }
    }

    private void remember(String name, @CheckForNull Known k) {
        if (k == null) {
            known.remove(name);
        } else {
            known.put(name, k);
        }
    }

    /** Reads a resource's key as it is now. */
    private void refresh(ClusterStore store, String name) throws IOException {
        PersistenceStore.Entry e = store.read(key(name));
        ResourceState s = e == null ? null : parse(e.content());
        synchronized (LockableResourcesManager.syncResources) {
            remember(name, s == null ? null : new Known(s, e.version()));
        }
    }

    /** Reads a key changed elsewhere, and lets builds waiting here look again. */
    void refreshAndNotify(String name) {
        ClusterStore store = store();
        if (store == null) {
            return;
        }
        try {
            refresh(store, name);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Cannot read who holds " + name + "; trying again shortly", e);
            Timer.get().schedule(() -> events.submit(() -> refreshAndNotify(name)), 10, TimeUnit.SECONDS);
            return;
        }
        LockableResourcesManager.get().refreshQueue();
    }

    // ------------------------------------------------------------------ the content of a key

    static byte[] toBytes(ResourceState s, String origin) {
        Properties p = new Properties();
        set(p, "build", s.getBuild());
        set(p, "remoteLock", s.getRemoteLock());
        set(p, "reservedBy", s.getReservedBy());
        set(p, "reason", s.getReason());
        p.setProperty("stolen", String.valueOf(s.isStolen()));
        p.setProperty("since", String.valueOf(s.getSince()));
        p.setProperty("origin", origin); // the controller that wrote it: a remote lock lives in its memory
        StringWriter w = new StringWriter();
        try {
            p.store(w, null);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return w.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void set(Properties p, String name, @CheckForNull String value) {
        if (value != null) {
            p.setProperty(name, value);
        }
    }

    @CheckForNull
    static Properties properties(byte[] content) {
        Properties p = new Properties();
        try {
            p.load(new StringReader(new String(content, StandardCharsets.UTF_8)));
            return p;
        } catch (IOException | IllegalArgumentException e) {
            LOGGER.log(Level.WARNING, "Unreadable lock", e);
            return null;
        }
    }

    @CheckForNull
    static ResourceState parse(byte[] content) {
        Properties p = properties(content);
        if (p == null) {
            return null;
        }
        long since;
        try {
            since = Long.parseLong(p.getProperty("since", "0"));
        } catch (NumberFormatException e) {
            since = 0;
        }
        return new ResourceState(
                p.getProperty("build"),
                p.getProperty("remoteLock"),
                p.getProperty("reservedBy"),
                p.getProperty("reason"),
                Boolean.parseBoolean(p.getProperty("stolen")),
                since);
    }

    // ------------------------------------------------------------------ changes made elsewhere

    @Override
    public boolean handles(@NonNull String key) {
        return key.startsWith(LOCKS + "/");
    }

    @Override
    public void changed(@NonNull String key, boolean deleted) {
        String name = name(key);
        events.submit(() -> refreshAndNotify(name)); // read as it is now: an event may arrive after a newer change
    }

    /** Waits until the changes handed over so far are read (tests). */
    void flush() throws Exception {
        events.submit(() -> {}).get(60, TimeUnit.SECONDS);
    }

    /** Reads every lock again, as if their events had just arrived: an event may have been lost. */
    void catchUp() throws IOException {
        ClusterStore store = store();
        if (store == null) {
            return;
        }
        Set<String> names = new TreeSet<>(known.keySet());
        for (String segment : store.children(LOCKS)) {
            names.add(name(LOCKS + "/" + segment));
        }
        for (String name : names) {
            events.submit(() -> refreshAndNotify(name));
        }
    }

    @Extension(optional = true)
    @Restricted(NoExternalUse.class)
    public static final class CatchUp extends Resync {
        @Override
        public void catchUp() throws IOException {
            if (LockableResourcesPlugin.isInstalled()) {
                instance().catchUp();
            }
        }
    }

    /** Reads every lock before builds resume or wait. */
    @Initializer(after = InitMilestone.JOB_LOADED)
    public static void start() throws IOException {
        ClusterStore store = store();
        if (store == null) {
            return;
        }
        ClusterResourceStates states = instance();
        for (String segment : store.children(LOCKS)) {
            states.refresh(store, name(LOCKS + "/" + segment));
        }
    }
}
