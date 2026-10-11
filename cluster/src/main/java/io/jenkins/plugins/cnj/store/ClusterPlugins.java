package io.jenkins.plugins.cnj.store;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.PluginManager;
import hudson.PluginWrapper;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.lifecycle.RestartNotSupportedException;
import hudson.model.AdministrativeMonitor;
import hudson.util.VersionNumber;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.persistence.BlobStore;
import jenkins.persistence.ConflictException;
import jenkins.persistence.PersistenceStore;
import jenkins.persistence.PersistenceStores;
import jenkins.util.Timer;
import net.sf.json.JSONObject;

/**
 * The cluster's plugin set (Target 13): a plugin installed, upgraded, uninstalled, disabled or enabled on one controller
 * is applied on every controller, and again whenever a controller starts with an empty home.
 *
 * <p>The changes made at runtime are an overlay on the image's plugins, kept in the store as {@link #KEY}: per plugin its
 * state and, for installs, the version and the archive (the exact bytes the installing controller verified, in blob
 * storage, so no other controller needs the update center). Every controller reconciles its own plugins with it: new
 * plugins are loaded live where they support it; anything else (upgrades, removals, plugins that cannot load live)
 * is written to {@code plugins/} and applied by a restart. Restarts are rolling: one controller at a time holds the
 * restart lock {@link #RESTART_KEY}, quiets down until idle, restarts, and releases the lock once it is back and has
 * reconciled, so the cluster keeps serving.
 *
 * <p>Changes are captured from the update center (every install and upgrade, including dependencies and uploads,
 * {@link ClusterUpdateCenterConfiguration}) and from the plugin manager's uninstall, disable and enable actions
 * ({@link ClusterPluginSet}). CLI installs from a file or URL bypass the update center and stay local.
 */
public final class ClusterPlugins {

    private static final Logger LOGGER = Logger.getLogger(ClusterPlugins.class.getName());

    static final String KEY = "cluster/plugins.json";
    static final String RESTART_KEY = "cluster/plugins.restart";
    static final String BLOBS = "cluster-plugins/";
    /** The store plugin itself is part of the image and never changed through the cluster. */
    static final String PROTECTED = "persistence-store-postgresql";
    /** Files in {@code plugins/} written by the cluster: the image entrypoint leaves those alone. */
    static final String MARKER = ".cluster";
    /** In {@code plugins/}: the generation this home last restarted for, so that a restart is never repeated in a loop. */
    static final String RESTARTED_FOR = ".cluster-restarted-for";
    static final long LOCK_TIMEOUT_MILLIS = Long.getLong(ClusterPlugins.class.getName() + ".lockTimeoutMinutes", 10) * 60_000;
    static final long PERIOD_SECONDS = Long.getLong(ClusterPlugins.class.getName() + ".periodSeconds", 60);

    enum State { installed, uninstalled, disabled }

    /** One plugin's desired state. {@code version}, {@code sha256} and {@code blob} are null when no archive is shared (e.g. enabling an image plugin). */
    record Entry(State state, @CheckForNull String version, @CheckForNull String sha256, @CheckForNull String blob, String by, long at) {
        JSONObject toJson() {
            JSONObject o = new JSONObject();
            o.put("state", state.name());
            if (version != null) {
                o.put("version", version);
                o.put("sha256", sha256);
                o.put("blob", blob);
            }
            o.put("by", by);
            o.put("at", at);
            return o;
        }

        static Entry fromJson(JSONObject o) {
            return new Entry(State.valueOf(o.getString("state")), o.optString("version", null), o.optString("sha256", null),
                    o.optString("blob", null), o.optString("by", ""), o.optLong("at"));
        }
    }

    /** The overlay: plugins in the order they were first changed (dependencies before their dependents). */
    record Overlay(long generation, Map<String, Entry> plugins) {
        static final Overlay EMPTY = new Overlay(0, Map.of());

        static Overlay parse(@CheckForNull byte[] content) {
            if (content == null) {
                return EMPTY;
            }
            JSONObject o = JSONObject.fromObject(new String(content, StandardCharsets.UTF_8));
            Map<String, Entry> plugins = new LinkedHashMap<>();
            JSONObject p = o.optJSONObject("plugins");
            if (p != null) {
                for (Object name : p.keySet()) {
                    plugins.put((String) name, Entry.fromJson(p.getJSONObject((String) name)));
                }
            }
            return new Overlay(o.optLong("generation"), plugins);
        }

        byte[] toBytes() {
            JSONObject p = new JSONObject();
            plugins.forEach((name, e) -> p.put(name, e.toJson()));
            JSONObject o = new JSONObject();
            o.put("generation", generation);
            o.put("plugins", p);
            return o.toString(2).getBytes(StandardCharsets.UTF_8);
        }

        /** This overlay with one plugin's entry set, as the next generation. */
        Overlay with(String name, Entry entry) {
            Map<String, Entry> plugins = new LinkedHashMap<>(this.plugins);
            plugins.put(name, entry);
            return new Overlay(generation + 1, plugins);
        }
    }

    /** What this controller has to do to its files for one plugin. */
    enum Action { NONE, LOAD, REPLACE, DELETE, DISABLE, ENABLE }

    /**
     * The file change one plugin needs here.
     *
     * @param localVersion the version in {@code plugins/} here, or null if there is no archive
     * @param localEnabled whether the archive here is enabled (no {@code .disabled} marker)
     */
    static Action decide(@NonNull Entry desired, @CheckForNull String localVersion, boolean localEnabled) {
        switch (desired.state()) {
            case uninstalled:
                return localVersion == null ? Action.NONE : Action.DELETE;
            case disabled:
                return localVersion != null && localEnabled ? Action.DISABLE : Action.NONE;
            default:
                if (localVersion == null) {
                    return desired.blob() == null ? Action.NONE : Action.LOAD;
                }
                if (desired.blob() != null && new VersionNumber(localVersion).isOlderThan(new VersionNumber(desired.version()))) {
                    return Action.REPLACE; // never downgrades: a newer image wins
                }
                return localEnabled ? Action.NONE : Action.ENABLE;
        }
    }

    /**
     * Whether what is loaded here differs from the files in {@code plugins/} for this plugin, so that only a restart
     * applies it.
     *
     * @param loaded whether the plugin is loaded and active in this JVM
     * @param loadedVersion its version as loaded, or null
     * @param localVersion its version on disk, or null if there is no archive
     */
    static boolean needsRestart(@NonNull Entry desired, boolean loaded, @CheckForNull String loadedVersion,
            @CheckForNull String localVersion, boolean localEnabled) {
        switch (desired.state()) {
            case uninstalled:
                return loaded && localVersion == null;
            case disabled:
                return loaded && !localEnabled;
            default:
                if (localVersion == null) {
                    return false;
                }
                if (!loaded) {
                    return localEnabled; // on disk (e.g. it could not load live) but not running
                }
                return loadedVersion != null && !loadedVersion.equals(localVersion);
        }
    }

    /** Whether a restart lock taken at {@code since} by {@code origin} may be broken. */
    static boolean lockExpired(long since, long now, boolean holderAlive) {
        return now - since > LOCK_TIMEOUT_MILLIS && !holderAlive;
    }

    // ------------------------------------------------------------------ state of this controller

    /** Plugins whose archive here was changed by the cluster since startup: name → version written (or the state). */
    private static final Map<String, String> staged = new ConcurrentHashMap<>();
    private static volatile long appliedGeneration;
    private static volatile boolean restartPending;
    private static volatile boolean restartRefused;
    private static volatile boolean restarting;

    @CheckForNull
    private static PostgresPersistenceStore store() {
        return PersistenceStores.get() instanceof PostgresPersistenceStore s ? s : null;
    }

    static boolean active() {
        return ClusterPluginSet.MODE == ClusterPluginSet.Mode.cluster && store() != null && PersistenceStores.getBlobStore() != null;
    }

    @Initializer(after = InitMilestone.JOB_LOADED)
    public static void start() {
        PostgresPersistenceStore store = store();
        if (store == null || !active()) {
            return;
        }
        store.subscribe(e -> {
            if (e.kind() == PersistenceStore.ChangeEvent.Kind.WRITTEN && !store.getOrigin().equals(e.origin())
                    && (KEY.equals(e.key()) || RESTART_KEY.equals(e.key()))) {
                Timer.get().submit(ClusterPlugins::reconcile);
            } else if (e.kind() == PersistenceStore.ChangeEvent.Kind.DELETED && RESTART_KEY.equals(e.key())) {
                Timer.get().submit(ClusterPlugins::reconcile); // the lock is free: maybe it is our turn
            }
        });
        Timer.get().scheduleWithFixedDelay(ClusterPlugins::reconcile, PERIOD_SECONDS, PERIOD_SECONDS, TimeUnit.SECONDS);
    }

    /** Once up: a controller starting with an empty home (or after missing events) catches up with the cluster's set. */
    @Initializer(after = InitMilestone.COMPLETED)
    public static void catchUp() {
        if (active()) {
            Timer.get().submit(ClusterPlugins::reconcile);
        }
    }

    // ------------------------------------------------------------------ capture (where the admin acts)

    /** Records a plugin archive just installed here by the update center (or an upload), as the cluster's version of it. */
    static void installed(@NonNull File archive) {
        if (!active()) {
            return;
        }
        try {
            Manifest m;
            try (JarFile jar = new JarFile(archive)) {
                m = jar.getManifest();
            }
            String name = m.getMainAttributes().getValue("Short-Name");
            String version = m.getMainAttributes().getValue("Plugin-Version");
            if (name == null || version == null || PROTECTED.equals(name)) {
                return;
            }
            String sha256 = sha256(archive);
            String blob = BLOBS + name + "/" + sha256 + ".jpi";
            BlobStore blobs = PersistenceStores.getBlobStore();
            try (InputStream in = Files.newInputStream(archive.toPath())) {
                blobs.put(blob, in, Files.size(archive.toPath()));
            }
            marker(name, true);
            staged.put(name, version);
            record(name, new Entry(State.installed, version, sha256, blob, PostgresPersistenceStore.NAME, System.currentTimeMillis()));
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not share the installation of " + archive + " with the cluster", e);
        }
    }

    /** Records an uninstall, disable or enable done here through the plugin manager. */
    static void changed(@NonNull String name, @NonNull State state) {
        if (!active() || PROTECTED.equals(name)) {
            return;
        }
        try {
            Entry previous = read().plugins().get(name);
            long now = System.currentTimeMillis();
            Entry entry = state == State.installed && previous != null && previous.blob() != null
                    // enabling a plugin the cluster installed: keep its archive
                    ? new Entry(State.installed, previous.version(), previous.sha256(), previous.blob(), PostgresPersistenceStore.NAME, now)
                    : new Entry(state, null, null, null, PostgresPersistenceStore.NAME, now);
            staged.put(name, state.name());
            record(name, entry);
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not share the change of " + name + " with the cluster", e);
        }
    }

    private static void record(String name, Entry entry) throws IOException {
        PostgresPersistenceStore store = store();
        for (int attempt = 0; ; attempt++) {
            PersistenceStore.Entry current = store.read(KEY);
            Overlay next = Overlay.parse(current == null ? null : current.content()).with(name, entry);
            try {
                store.write(KEY, next.toBytes(), current == null ? PersistenceStore.ABSENT : current.version());
                LOGGER.info(() -> "Cluster plugin set generation " + next.generation() + ": " + name + " " + entry.state()
                        + (entry.version() == null ? "" : " " + entry.version()));
                // e.g. a restart is now needed here too, once the update center is done with it
                Timer.get().schedule(ClusterPlugins::reconcile, 15, TimeUnit.SECONDS);
                return;
            } catch (ConflictException e) {
                if (attempt >= 20) {
                    throw e;
                }
            }
        }
    }

    static Overlay read() throws IOException {
        PersistenceStore.Entry e = store().read(KEY);
        return Overlay.parse(e == null ? null : e.content());
    }

    // ------------------------------------------------------------------ apply (every controller)

    static synchronized void reconcile() {
        PostgresPersistenceStore store = store();
        if (store == null || !active() || restarting) {
            return;
        }
        try {
            Overlay overlay = read();
            PluginManager pm = Jenkins.get().getPluginManager();
            boolean restart = false;
            if (Jenkins.get().getInitLevel() != InitMilestone.COMPLETED) {
                return; // started again once up
            }
            for (Map.Entry<String, Entry> e : overlay.plugins().entrySet()) {
                String name = e.getKey();
                if (PROTECTED.equals(name)) {
                    continue;
                }
                if (installing(name)) {
                    restart |= restartPending; // decided once the update center is done with it
                    continue;
                }
                try {
                    restart |= apply(pm, name, e.getValue());
                } catch (IOException | RuntimeException x) {
                    // e.g. a missing or corrupt archive: skip this plugin, apply the others
                    LOGGER.log(Level.WARNING, "Could not apply the cluster's plugin set for " + name, x);
                }
            }
            appliedGeneration = overlay.generation();
            restartPending = restart;
            PostgresPersistenceStore.pluginsStatus = "generation " + appliedGeneration + (restart ? ", restart pending" : "")
                    + (restartRefused ? " (restart refused: already restarted for it)" : "");
            if (restart) {
                requestRestart(store, overlay.generation());
            } else {
                releaseLock(store);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not reconcile the cluster's plugin set", e);
        }
    }

    /** Whether the update center here is still installing this plugin (its archive may be on disk, not yet loaded). */
    private static boolean installing(String name) {
        for (hudson.model.UpdateCenter.UpdateCenterJob job : Jenkins.get().getUpdateCenter().getJobs()) {
            if (job instanceof hudson.model.UpdateCenter.DownloadJob dj && name.equals(dj.getName())
                    && (dj.status instanceof hudson.model.UpdateCenter.DownloadJob.Pending
                            || dj.status instanceof hudson.model.UpdateCenter.DownloadJob.Installing)) {
                return true;
            }
        }
        return false;
    }

    /** @return whether a restart is needed for this plugin */
    private static boolean apply(PluginManager pm, String name, Entry desired) throws IOException {
        PluginWrapper loaded = pm.getPlugin(name);
        File archive = archive(pm, name);
        String localVersion = archive == null ? null : versionOf(archive);
        boolean localEnabled = archive != null && !new File(archive.getPath() + ".disabled").exists();
        Action action = decide(desired, localVersion, localEnabled);
        String stagedAs = staged.get(name);
        if (action != Action.NONE && !(stagedAs != null && stagedAs.equals(desired.version() != null ? desired.version() : desired.state().name()))) {
            LOGGER.info(() -> "Applying the cluster's plugin set: " + name + " " + action + (desired.version() == null ? "" : " " + desired.version()));
            switch (action) {
                case LOAD, REPLACE -> {
                    File target = new File(pm.rootDir, name + ".jpi");
                    fetch(desired, target);
                    Files.deleteIfExists(new File(pm.rootDir, name + ".hpi").toPath());
                    marker(name, true);
                    staged.put(name, desired.version());
                    if (action == Action.LOAD) {
                        try {
                            pm.dynamicLoad(target);
                            LOGGER.info(() -> "Loaded " + name + " " + desired.version() + " live");
                        } catch (jenkins.RestartRequiredException ignored) {
                            // not dynamically loadable, or a dependency is missing until a restart: needsRestart says so below
                        } catch (InterruptedException x) {
                            Thread.currentThread().interrupt();
                        } catch (RuntimeException | IOException x) {
                            LOGGER.log(Level.WARNING, "Could not load " + name + " live; it is applied by a restart", x);
                        }
                    }
                }
                case DELETE -> {
                    Files.deleteIfExists(archive.toPath());
                    Files.deleteIfExists(new File(archive.getPath() + ".disabled").toPath());
                    marker(name, false);
                    staged.put(name, desired.state().name());
                }
                case DISABLE -> {
                    Files.writeString(new File(archive.getPath() + ".disabled").toPath(), "");
                    staged.put(name, desired.state().name());
                }
                case ENABLE -> {
                    Files.deleteIfExists(new File(archive.getPath() + ".disabled").toPath());
                    staged.put(name, desired.version() != null ? desired.version() : desired.state().name());
                }
                default -> {}
            }
            loaded = pm.getPlugin(name);
            archive = archive(pm, name);
            localVersion = archive == null ? null : versionOf(archive);
            localEnabled = archive != null && !new File(archive.getPath() + ".disabled").exists();
        }
        boolean isLoaded = loaded != null && loaded.isActive();
        return needsRestart(desired, isLoaded, loaded == null ? null : loaded.getVersion(), localVersion, localEnabled);
    }

    @CheckForNull
    private static File archive(PluginManager pm, String name) {
        for (String ext : new String[] {".jpi", ".hpi"}) {
            File f = new File(pm.rootDir, name + ext);
            if (f.isFile()) {
                return f;
            }
        }
        return null;
    }

    @CheckForNull
    private static String versionOf(File archive) {
        try (JarFile jar = new JarFile(archive)) {
            Manifest m = jar.getManifest();
            return m == null ? null : m.getMainAttributes().getValue("Plugin-Version");
        } catch (IOException e) {
            return null;
        }
    }

    private static void fetch(Entry desired, File target) throws IOException {
        File tmp = new File(target.getPath() + ".cluster-tmp");
        try (InputStream in = PersistenceStores.getBlobStore().get(desired.blob())) {
            if (in == null) {
                throw new IOException("The cluster's archive " + desired.blob() + " is missing");
            }
            Files.copy(in, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        String sha = sha256(tmp);
        if (!sha.equals(desired.sha256())) {
            Files.deleteIfExists(tmp.toPath());
            throw new IOException("The cluster's archive " + desired.blob() + " has checksum " + sha + ", expected " + desired.sha256());
        }
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void marker(String name, boolean present) throws IOException {
        File rootDir = Jenkins.get().getPluginManager().rootDir;
        File marker = new File(rootDir, name + ".jpi" + MARKER);
        if (present) {
            Files.writeString(marker.toPath(), "written by the cluster's plugin set; the image entrypoint leaves this plugin alone\n");
        } else {
            Files.deleteIfExists(marker.toPath());
        }
    }

    static String sha256(File f) throws IOException {
        try (InputStream in = Files.newInputStream(f.toPath())) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) >= 0) {
                md.update(buf, 0, n);
            }
            return hudson.Util.toHexString(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    // ------------------------------------------------------------------ rolling restarts

    private static void requestRestart(PostgresPersistenceStore store, long generation) throws IOException {
        File guard = new File(Jenkins.get().getPluginManager().rootDir, RESTARTED_FOR);
        if (guard.isFile() && Files.readString(guard.toPath()).trim().equals(String.valueOf(generation))) {
            if (!restartRefused) {
                LOGGER.warning(() -> "The cluster's plugin set (generation " + generation + ") still differs after a restart for it;"
                        + " not restarting again (see the plugin manager)");
            }
            restartRefused = true;
            releaseLock(store);
            return;
        }
        restartRefused = false;
        PersistenceStore.Entry lock = store.read(RESTART_KEY);
        if (lock != null) {
            JSONObject holder = JSONObject.fromObject(new String(lock.content(), StandardCharsets.UTF_8));
            if (PostgresPersistenceStore.NAME.equals(holder.optString("name"))) {
                if (!store.getOrigin().equals(holder.optString("origin"))) {
                    store.delete(RESTART_KEY, lock.version()); // ours from before a restart: take it again below
                    lock = null;
                } else {
                    return; // already restarting
                }
            } else {
                Set<String> live = store.liveOrigins();
                boolean holderAlive = live != null && live.contains(holder.optString("origin"));
                if (lockExpired(holder.optLong("since"), System.currentTimeMillis(), holderAlive)) {
                    LOGGER.warning(() -> "Breaking the plugin restart lock of " + holder.optString("name") + ", held since "
                            + new java.util.Date(holder.optLong("since")));
                    store.delete(RESTART_KEY, lock.version());
                }
                return; // someone else's turn
            }
        }
        JSONObject me = new JSONObject();
        me.put("name", PostgresPersistenceStore.NAME);
        me.put("origin", store.getOrigin());
        me.put("since", System.currentTimeMillis());
        me.put("generation", generation);
        try {
            store.write(RESTART_KEY, me.toString().getBytes(StandardCharsets.UTF_8), PersistenceStore.ABSENT);
        } catch (ConflictException e) {
            return; // another controller took it first
        }
        Files.writeString(guard.toPath(), String.valueOf(generation));
        restarting = true;
        LOGGER.info(() -> "Restarting to apply the cluster's plugin set (generation " + generation + ") once idle");
        try {
            Jenkins.get().safeRestart("Restarting to apply the cluster's plugin changes");
        } catch (RestartNotSupportedException e) {
            // e.g. no in-place restart: quiet down, then exit and let the container restart (the home survives that)
            Thread t = new Thread(() -> {
                try {
                    Jenkins.get().doQuietDown(true, 0, "Restarting to apply the cluster's plugin changes", true);
                    LOGGER.info("Exiting to apply the cluster's plugin set");
                    System.exit(0);
                } catch (Exception x) {
                    LOGGER.log(Level.WARNING, "Could not restart to apply the cluster's plugin set", x);
                }
            }, "cluster plugins restart");
            t.setDaemon(true);
            t.start();
        }
    }

    private static void releaseLock(PostgresPersistenceStore store) throws IOException {
        PersistenceStore.Entry lock = store.read(RESTART_KEY);
        if (lock != null) {
            JSONObject holder = JSONObject.fromObject(new String(lock.content(), StandardCharsets.UTF_8));
            if (PostgresPersistenceStore.NAME.equals(holder.optString("name"))) {
                try {
                    store.delete(RESTART_KEY, lock.version());
                    LOGGER.info("Released the plugin restart lock: this controller is up to date with the cluster's plugin set");
                } catch (ConflictException ignored) {
                    // changed meanwhile
                }
            }
        }
    }

    // ------------------------------------------------------------------ UI

    /** Tells admins when this controller cannot apply the cluster's plugin set (e.g. it already restarted for it). */
    @Extension
    public static final class Monitor extends AdministrativeMonitor {
        @Override
        public boolean isActivated() {
            return restartRefused || restartPending;
        }

        @Override
        public String getDisplayName() {
            return "Cluster plugin set";
        }

        public boolean isRefused() {
            return restartRefused;
        }

        public long getGeneration() {
            return appliedGeneration;
        }
    }
}
