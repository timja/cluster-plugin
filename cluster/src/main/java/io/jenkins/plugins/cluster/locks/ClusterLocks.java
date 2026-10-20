package io.jenkins.plugins.cluster.locks;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.Run;
import hudson.util.DaemonThreadFactory;
import hudson.util.NamingThreadFactory;
import io.jenkins.plugins.cluster.builds.RunningBuilds;
import io.jenkins.plugins.cluster.spi.ClusterMember;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.persistence.ConflictException;
import jenkins.persistence.PersistenceStore;
import jenkins.persistence.RemoteChangeHandler;
import jenkins.util.Timer;
import org.jenkins.plugins.lockableresources.LockCoordinator;
import org.jenkins.plugins.lockableresources.LockableResource;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Makes lockable resources exclusive across the controllers of the cluster.
 *
 * <p>Every controller keeps its own {@link LockableResourcesManager} (with the same resources, as from configuration
 * as code) and its own queue of waiting builds. Taking a resource, for a build or a user, also creates {@link
 * Keys#lock} in the store: a create-only write, so of several controllers taking a resource at once exactly one
 * succeeds and the others keep waiting, showing it as held elsewhere. Releasing deletes the key at the version read,
 * so a steal or reassignment written meanwhile by another controller is kept. Another controller's change is applied
 * by reading the key again, never from the event alone: events may arrive late, after a newer change.
 *
 * <p>Resources created, edited or noted on the resources page are shared as {@link Keys#lockMeta}. Resources defined
 * in the global configuration (or by configuration as code) are not: each controller defines those itself.
 */
@Extension(optional = true)
@Restricted(NoExternalUse.class)
public final class ClusterLocks implements LockCoordinator, RemoteChangeHandler {

    private static final Logger LOGGER = Logger.getLogger(ClusterLocks.class.getName());

    static final String LOCKS = parent(Keys.lock("x"));
    static final String META = parent(Keys.lockMeta("x"));

    /** The version of each lock key this controller wrote or last applied, by resource name. */
    private final Map<String, Long> known = new ConcurrentHashMap<>();

    /** Applies other controllers' changes off the store's event thread, one at a time, in order. */
    private final ExecutorService events =
            Executors.newSingleThreadExecutor(new NamingThreadFactory(new DaemonThreadFactory(), "ClusterLocks"));

    private static String parent(String key) {
        return key.substring(0, key.lastIndexOf('/'));
    }

    @CheckForNull
    static ClusterStore store() {
        return ClusterStore.get();
    }

    /** A resource of this controller, by name. */
    @CheckForNull
    static LockableResource resource(LockableResourcesManager lrm, String name) {
        for (LockableResource r : lrm.getResources()) {
            if (r.getName().equals(name)) {
                return r;
            }
        }
        return null;
    }

    static ClusterLocks get() {
        return ExtensionList.lookupSingleton(ClusterLocks.class);
    }

    /** Resource names may hold any character; a key segment may not hold {@code /}. */
    static String encode(String name) {
        return URLEncoder.encode(name, StandardCharsets.UTF_8);
    }

    static String decode(String segment) {
        return URLDecoder.decode(segment, StandardCharsets.UTF_8);
    }

    static String key(String resource) {
        return Keys.lock(encode(resource));
    }

    /**
     * How a build is named in a lock: by its job's identity, which survives renames, and its number.
     *
     * @return {@code <item uuid>#<number>}
     */
    static String buildId(Run<?, ?> build) {
        return build.getParent().getItemUuid() + "#" + build.getNumber(); // every job has an identity
    }

    /** The content of a lock key. */
    record Holder(
            @NonNull String kind,
            @CheckForNull String build,
            @CheckForNull String display,
            @CheckForNull String user,
            @CheckForNull String reason,
            boolean stolen,
            @CheckForNull String origin,
            @CheckForNull String controller) {

        static final String LOCK = "lock";
        static final String RESERVATION = "reservation";
        /** Held for a client of lockable-resources' remote lock API; {@code build} is the remote lock's id. */
        static final String REMOTE = "remote";

        static Holder lock(Run<?, ?> build, String origin) {
            return new Holder(
                    LOCK, buildId(build), build.getExternalizableId(), null, null, false, origin, ClusterMember.NAME);
        }

        static Holder reservation(String user, @CheckForNull String reason, boolean stolen, String origin) {
            return new Holder(RESERVATION, null, null, user, reason, stolen, origin, ClusterMember.NAME);
        }

        static Holder remote(String lockId, String origin) {
            return new Holder(REMOTE, lockId, "remote lock " + lockId, null, null, false, origin, ClusterMember.NAME);
        }

        boolean isLock() {
            return LOCK.equals(kind);
        }

        boolean isReservation() {
            return RESERVATION.equals(kind);
        }

        boolean isRemote() {
            return REMOTE.equals(kind);
        }

        boolean isRemoteLockOf(@CheckForNull String lockId) {
            return isRemote() && lockId != null && lockId.equals(build);
        }

        boolean isLockOf(@CheckForNull Run<?, ?> run) {
            return isLock() && run != null && buildId(run).equals(build);
        }

        byte[] toBytes() {
            Properties p = new Properties();
            p.setProperty("kind", kind);
            set(p, "build", build);
            set(p, "display", display);
            set(p, "user", user);
            set(p, "reason", reason);
            p.setProperty("stolen", String.valueOf(stolen));
            set(p, "origin", origin);
            set(p, "controller", controller);
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
        static Holder parse(@CheckForNull PersistenceStore.Entry entry) {
            if (entry == null) {
                return null;
            }
            Properties p = new Properties();
            try {
                p.load(new StringReader(new String(entry.content(), StandardCharsets.UTF_8)));
            } catch (IOException | IllegalArgumentException e) {
                LOGGER.log(Level.WARNING, "Unreadable lock", e);
                return null;
            }
            return new Holder(
                    p.getProperty("kind", LOCK),
                    p.getProperty("build"),
                    p.getProperty("display"),
                    p.getProperty("user"),
                    p.getProperty("reason"),
                    Boolean.parseBoolean(p.getProperty("stolen")),
                    p.getProperty("origin"),
                    p.getProperty("controller"));
        }

        String describe() {
            return (display != null ? display : build) + " on " + controller;
        }
    }

    // ------------------------------------------------------------------ outbound (LockCoordinator)

    @Override
    public boolean tryLock(@NonNull List<LockableResource> resources, @NonNull Run<?, ?> build) {
        ClusterStore store = store();
        if (store == null) {
            return true;
        }
        return take(store, resources, Holder.lock(build, store.getOrigin()), build);
    }

    @Override
    public boolean tryReserve(
            @NonNull List<LockableResource> resources, @NonNull String user, @CheckForNull String reason) {
        ClusterStore store = store();
        if (store == null) {
            return true;
        }
        return take(store, resources, Holder.reservation(user, reason, false, store.getOrigin()), null);
    }

    /**
     * Creates the keys of all resources, or none. In the order of their names, as every controller does: of two
     * controllers locking the same resources, the one taking the first wins and the other gives back nothing, rather than
     * each taking some, giving them back and trying again at once.
     */
    private boolean take(
            ClusterStore store, List<LockableResource> resources, Holder holder, @CheckForNull Run<?, ?> build) {
        Map<String, Long> taken = new LinkedHashMap<>();
        try {
            for (LockableResource r : inOrder(resources)) {
                String key = key(r.getName());
                try {
                    taken.put(r.getName(), store.write(key, holder.toBytes(), PersistenceStore.ABSENT));
                } catch (ConflictException e) {
                    PersistenceStore.Entry entry = store.read(key);
                    Holder current = Holder.parse(entry);
                    if (entry != null && current != null && current.isLockOf(build)) {
                        try { // already this build's (it was adopted): it holds it from here now
                            taken.put(r.getName(), store.write(key, holder.toBytes(), entry.version()));
                            continue;
                        } catch (ConflictException again) {
                            entry = store.read(key);
                            current = Holder.parse(entry);
                        }
                    }
                    LOGGER.fine(() -> r.getName() + " is held elsewhere");
                    if (entry != null && current != null) {
                        show(r, current, entry.version());
                    }
                    rollback(store, taken);
                    return false;
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Cannot take " + resources + " across controllers; trying again shortly", e);
            rollback(store, taken);
            Timer.get().schedule(() -> LockableResourcesManager.get().refreshQueue(), 10, TimeUnit.SECONDS);
            return false;
        }
        for (LockableResource r : resources) {
            r.setHeldElsewhereBy(null);
        }
        known.putAll(taken);
        return true;
    }

    static List<LockableResource> inOrder(List<LockableResource> resources) {
        List<LockableResource> sorted = new ArrayList<>(resources);
        sorted.sort(Comparator.comparing(LockableResource::getName));
        return sorted;
    }

    private static void rollback(ClusterStore store, Map<String, Long> taken) {
        taken.forEach((name, version) -> {
            try {
                store.delete(key(name), version);
            } catch (ConflictException e) {
                // changed meanwhile by another controller: theirs now
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to give " + name + " back after a failed lock", e);
            }
        });
    }

    @Override
    public boolean tryLockForRemote(@NonNull List<LockableResource> resources, @NonNull String lockId) {
        ClusterStore store = store();
        if (store == null) {
            return true;
        }
        return take(store, resources, Holder.remote(lockId, store.getOrigin()), null);
    }

    @Override
    public void releasedForRemote(@NonNull LockableResource resource, @NonNull String lockId) {
        ClusterStore store = store();
        if (store == null) {
            return;
        }
        known.remove(resource.getName());
        deleteIf(store, resource.getName(), h -> h.isRemoteLockOf(lockId));
    }

    /** Names the controller holding the lock, so that its client's later calls reach it through any controller. */
    @NonNull
    @Override
    public String newRemoteLockId() {
        return io.jenkins.plugins.cluster.RemoteLockForwarder.newLockId();
    }

    @Override
    public void released(@NonNull LockableResource resource, @NonNull Run<?, ?> build) {
        ClusterStore store = store();
        if (store == null) {
            return;
        }
        known.remove(resource.getName());
        if (!RunningBuilds.owns(build)) {
            // a copy stopped here once another controller took the build over: the lock is the new owner's
            LOGGER.fine(() -> build + " is no longer run here; leaving its lock on " + resource.getName());
            events.submit(() -> apply(resource.getName())); // shown as held there
            return;
        }
        // and only as this controller holds it: the new owner of a build taken over rewrites it as its own
        String origin = store.getOrigin();
        if (!deleteIf(
                store,
                resource.getName(),
                h -> h.isLockOf(build) && (h.origin() == null || origin.equals(h.origin())))) {
            events.submit(() -> apply(resource.getName()));
        }
    }

    @Override
    public void unreserved(@NonNull LockableResource resource) {
        ClusterStore store = store();
        if (store == null) {
            return;
        }
        known.remove(resource.getName());
        deleteIf(store, resource.getName(), Holder::isReservation);
    }

    /**
     * Deletes a resource's key at the version read, if its holder is the expected one.
     *
     * @return false if it is held by another holder (or was changed meanwhile)
     */
    private static boolean deleteIf(ClusterStore store, String name, Predicate<Holder> expected) {
        String key = key(name);
        try {
            PersistenceStore.Entry entry = store.read(key);
            Holder h = Holder.parse(entry);
            if (entry != null && h != null) {
                if (!expected.test(h)) {
                    return false;
                }
                store.delete(key, entry.version());
            }
        } catch (ConflictException e) {
            LOGGER.fine(() -> name + " was changed by another controller meanwhile: theirs now");
            return false;
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to release " + name + " across controllers", e);
        }
        return true;
    }

    @Override
    public void reservedForcibly(
            @NonNull LockableResource resource, @NonNull String user, @CheckForNull String reason, boolean stolen) {
        ClusterStore store = store();
        if (store == null) {
            return;
        }
        try {
            known.put(
                    resource.getName(),
                    store.write(
                            key(resource.getName()),
                            Holder.reservation(user, reason, stolen, store.getOrigin())
                                    .toBytes(),
                            PersistenceStore.ANY));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Cannot reserve " + resource.getName() + " across controllers", e);
        }
        resource.setHeldElsewhereBy(null);
    }

    @Override
    public void freed(@NonNull LockableResource resource) {
        ClusterStore store = store();
        if (store == null) {
            return;
        }
        String name = resource.getName();
        String key = key(name);
        try {
            Long version = known.remove(name);
            if (version == null) {
                PersistenceStore.Entry entry = store.read(key);
                if (entry == null) {
                    return;
                }
                version = entry.version();
            }
            store.delete(key, version); // what this controller showed, not whatever is there now
        } catch (ConflictException e) {
            LOGGER.info(
                    () -> name + " changed on another controller before it was freed here; it is left as it is now");
            events.submit(() -> apply(name));
            return;
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Cannot free " + name + " across controllers", e);
        }
        resource.setHeldElsewhereBy(null);
    }

    @Override
    public boolean isHeldElsewhere(@NonNull LockableResource resource) {
        return resource.getHeldElsewhereBy() != null;
    }

    /** Shows how a resource is held by another controller. */
    private void show(LockableResource r, Holder h, long version) {
        known.put(r.getName(), version);
        if (h.isLock() || h.isRemote()) {
            r.setHeldElsewhereBy(h.describe());
        } else {
            r.setHeldElsewhereBy(null);
            String user = h.user();
            if (user != null && !user.equals(r.getReservedBy())) {
                r.reserve(user, h.reason());
            }
            if (h.stolen()) {
                r.setStolen();
            }
        }
    }

    // ------------------------------------------------------------------ definitions

    @Override
    public void definitionChanged(@NonNull LockableResource r) {
        ClusterStore store = store();
        if (store == null) {
            return;
        }
        LockableResource def = new LockableResource(r.getName());
        def.setDescription(r.getDescription());
        def.setLabels(r.getLabels());
        def.setNote(r.getNote());
        def.setProperties(r.getProperties());
        try {
            store.write(
                    Keys.lockMeta(encode(r.getName())),
                    Jenkins.XSTREAM2.toXML(def).getBytes(StandardCharsets.UTF_8),
                    PersistenceStore.ANY);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Cannot share the definition of " + r.getName(), e);
        }
    }

    @Override
    public void definitionDeleted(@NonNull String name) {
        ClusterStore store = store();
        if (store == null) {
            return;
        }
        try {
            store.delete(Keys.lockMeta(encode(name)), PersistenceStore.ANY);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Cannot stop sharing " + name, e);
        }
    }

    /** Applies the shared definition of a resource as it is now (created, edited or deleted elsewhere). */
    void applyDefinition(String name) {
        ClusterStore store = store();
        if (store == null) {
            return;
        }
        LockableResourcesManager lrm = LockableResourcesManager.get();
        try {
            PersistenceStore.Entry e = store.read(Keys.lockMeta(encode(name)));
            synchronized (LockableResourcesManager.syncResources) {
                LockableResource r = resource(lrm, name);
                if (e == null) {
                    if (r != null && r.isFree()) {
                        lrm.removeResources(new ArrayList<>(List.of(r)));
                        lrm.save();
                    }
                    return;
                }
                if (!(Jenkins.XSTREAM2.fromXML(new String(e.content(), StandardCharsets.UTF_8))
                                instanceof LockableResource def)
                        || !name.equals(def.getName())) {
                    LOGGER.warning(() -> "Ignoring an unexpected shared definition of " + name);
                    return;
                }
                if (r == null) {
                    lrm.addResource(def, true);
                } else {
                    r.setDescription(def.getDescription());
                    r.setLabels(def.getLabels());
                    r.setNote(def.getNote());
                    r.setProperties(def.getProperties());
                    lrm.save();
                }
            }
        } catch (IOException | RuntimeException x) {
            LOGGER.log(Level.WARNING, "Cannot apply the shared definition of " + name, x);
        }
    }

    // ------------------------------------------------------------------ inbound

    @Override
    public boolean handles(@NonNull String key) {
        return key.startsWith(LOCKS + "/") || key.startsWith(META + "/");
    }

    @Override
    public void changed(@NonNull String key, boolean deleted) {
        if (key.startsWith(META + "/")) {
            String name = decode(key.substring(META.length() + 1));
            events.submit(() -> applyDefinition(name));
        } else if (key.startsWith(LOCKS + "/")) {
            String name = decode(key.substring(LOCKS.length() + 1));
            events.submit(() -> apply(name));
        }
    }

    /** Waits until the changes handed over so far are applied (tests). */
    void flush() throws Exception {
        events.submit(() -> {}).get(60, TimeUnit.SECONDS);
    }

    /**
     * Brings a resource up to date with its key as it is now. Whether the change was a write or a deletion does not
     * matter: a late event is then harmless.
     */
    void apply(String name) {
        ClusterStore store = store();
        if (store == null) {
            return;
        }
        LockableResourcesManager lrm = LockableResourcesManager.get();
        boolean freed = false;
        String lostRemoteLock = null;
        try {
            synchronized (LockableResourcesManager.syncResources) {
                LockableResource r = resource(lrm, name);
                if (r == null) {
                    return;
                }
                PersistenceStore.Entry entry = store.read(key(name));
                Holder h = Holder.parse(entry);
                Run<?, ?> local = r.getBuild();
                String remote = r.getRemoteLockedBy();
                if (entry == null || h == null) {
                    // freed elsewhere: unlocked or reset on the resources page there, or released by its holder
                    Long had = known.remove(name);
                    if (remote != null) {
                        LOGGER.info(() -> name + " was freed on another controller while remote lock " + remote
                                + " held it here");
                        lostRemoteLock = remote;
                    } else if (local != null) {
                        LOGGER.info(() -> name + " was freed on another controller while " + local + " held it here");
                        lrm.unlockResources(new ArrayList<>(List.of(r)), local);
                    } else if (had != null && r.isReserved()) {
                        r.unReserve();
                    }
                    r.setHeldElsewhereBy(null);
                    freed = true;
                } else if (h.isLockOf(local) || h.isRemoteLockOf(remote)) {
                    known.put(name, entry.version()); // this controller's own lock
                } else {
                    if (remote != null) {
                        LOGGER.info(() -> name + " was taken from remote lock " + remote + " on another controller");
                        lostRemoteLock = remote;
                    } else if (local != null) {
                        // stolen from the build here, or reassigned, on another controller: freed here as a steal does
                        LOGGER.info(() -> name + " was taken from " + local + " on another controller");
                        lrm.unlockResources(new ArrayList<>(List.of(r)), local);
                    }
                    show(r, h, entry.version());
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Cannot read the lock of " + name + "; trying again shortly", e);
            Timer.get().schedule(() -> events.submit(() -> apply(name)), 10, TimeUnit.SECONDS);
            return;
        }
        if (lostRemoteLock != null) {
            // outside the manager's lock, as a release from the resources page; its client finds the lock gone
            lrm.releaseRemoteLock(lostRemoteLock);
        }
        if (freed) {
            // a waiter here may take it now: Pipeline steps wait in the manager's queue, freestyle builds in Jenkins'
            lrm.refreshQueue();
        }
    }

    /**
     * Brings the resources this controller knows to be locked, here or elsewhere, up to date with their keys, as if
     * their events had just arrived: a lost deletion would otherwise leave a resource held elsewhere here for good, and
     * the builds waiting for it here waiting.
     */
    void catchUp() throws IOException {
        ClusterStore store = store();
        if (store == null) {
            return;
        }
        java.util.Set<String> names = new java.util.TreeSet<>();
        for (String name : store.children(LOCKS)) {
            names.add(decode(name));
        }
        names.addAll(known.keySet());
        synchronized (LockableResourcesManager.syncResources) {
            for (LockableResource r : LockableResourcesManager.get().getResources()) {
                if (r.getHeldElsewhereBy() != null || r.getBuild() != null || r.getRemoteLockedBy() != null) {
                    names.add(r.getName());
                }
            }
        }
        for (String name : names) {
            events.submit(() -> apply(name));
        }
    }

    /** Catches the locks up with the store: events may have been lost. */
    @Extension(optional = true)
    @Restricted(NoExternalUse.class)
    public static final class CatchUp extends Resync {
        @Override
        public void catchUp() throws IOException {
            get().catchUp();
        }
    }

    /** Brings this controller up to date with every shared definition and lock. */
    @Initializer(after = InitMilestone.JOB_LOADED)
    public static void start() throws IOException {
        ClusterStore store = store();
        if (store == null) {
            return;
        }
        ClusterLocks locks = get();
        for (String name : store.children(META)) {
            locks.applyDefinition(decode(name)); // first: a lock may be on a resource only defined there
        }
        for (String name : store.children(LOCKS)) {
            locks.apply(decode(name));
        }
    }
}
