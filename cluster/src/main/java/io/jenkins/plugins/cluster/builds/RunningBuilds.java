package io.jenkins.plugins.cluster.builds;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.ExtensionPoint;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Executor;
import hudson.model.Job;
import hudson.model.Queue;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.cluster.spi.BuildLeases;
import io.jenkins.plugins.cluster.spi.ClusterMember;
import io.jenkins.plugins.cluster.spi.ClusterStore;
import io.jenkins.plugins.cluster.spi.Keys;
import io.jenkins.plugins.cluster.spi.LeaseLostException;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.CauseOfInterruption;
import jenkins.model.lazy.LazyBuildMixIn;
import jenkins.persistence.ConflictException;
import jenkins.persistence.PersistenceStore;
import jenkins.persistence.PersistenceStores;
import jenkins.util.SystemProperties;
import jenkins.util.Timer;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

/**
 * Makes builds running on one controller visible on the others, as placeholders, until their record is
 * {@linkplain BuildRecords published}; and keeps each running build owned by exactly one controller.
 *
 * <p>When a build starts, its controller acquires the build's lease ({@link Keys#buildLease}) and writes its placeholder
 * ({@link Keys#placeholder}: origin, controller name, start time, display name, lease epoch), and deletes both once the
 * build is published. Other controllers show placeholders as builds elsewhere ({@link ClusterExternalBuilds}).
 * Placeholders left behind by a controller which died are adopted ({@link BuildAdopter}) or closed by whichever
 * controller notices first.
 *
 * <p>Builds owned here are tracked by their {@link Run} object; keys are made from the job's identity, which renames and
 * moves do not change.
 */
@Restricted(NoExternalUse.class)
public final class RunningBuilds {

    private static final Logger LOGGER = Logger.getLogger(RunningBuilds.class.getName());

    private static final String SUFFIX = ".xml";
    private static final String STOP_SUFFIX = ".stop";

    /** How often stale placeholders are looked for, in seconds. */
    @SuppressFBWarnings(value = "MS_SHOULD_BE_FINAL", justification = "for script console")
    public static /* non-final for script console */ int CLEANUP_PERIOD =
            SystemProperties.getInteger(RunningBuilds.class.getName() + ".cleanupPeriod", 30);

    /** Placeholder property: when its owner {@linkplain #handOver handed it over}, in milliseconds since the epoch. */
    static final String HANDED_OVER = "handedOver";

    /**
     * How long a build handed over waits for its taker: past that, nobody took it (the taker went away, say), and any
     * controller's sweep adopts it, its owner's included, as one of a controller gone.
     */
    @SuppressFBWarnings(value = "MS_SHOULD_BE_FINAL", justification = "for script console")
    public static /* non-final for script console */ long HANDOVER_GRACE_MILLIS =
            SystemProperties.getLong(RunningBuilds.class.getName() + ".handoverGraceMillis", 120_000L);

    /** The placeholder property recording a completed build's result until its record is published. */
    static final String RESULT = "result";

    private RunningBuilds() {}

    /** The origins of the live controllers, or null if that cannot be told yet (nobody is taken for gone then). */
    @CheckForNull
    public static Set<String> liveOrigins() throws IOException {
        ClusterStore store = ClusterStore.get();
        return store == null || !store.canJudgeLiveness() ? null : store.aliveMembers();
    }

    /** Whether a build was handed over long enough ago that its taker is not coming. */
    private static boolean handoverLapsed(Placeholder p) {
        String at = p.properties.get(HANDED_OVER);
        if (at == null) {
            return false;
        }
        try {
            return System.currentTimeMillis() - Long.parseLong(at) >= HANDOVER_GRACE_MILLIS;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    /** Properties of a placeholder as its new owner writes them: no longer handed over. */
    private static Map<String, String> owning(Map<String, String> properties) {
        if (!properties.containsKey(HANDED_OVER)) {
            return properties;
        }
        Map<String, String> mine = new TreeMap<>(properties);
        mine.remove(HANDED_OVER);
        return mine;
    }

    /** Placeholders per item prefix, kept up to date by change events; loaded from the store on first use. */
    private static final Map<String, List<Placeholder>> cache = new ConcurrentHashMap<>();

    /**
     * A build running on some controller, as its placeholder says.
     */
    @ExportedBean(defaultVisibility = 2)
    public static final class Placeholder {
        private final String prefix;
        private final int number;
        private final String origin;
        private final String controller;
        private final String displayName;
        private final long startTime;
        private final long epoch;
        private final Map<String, String> properties;
        private final long version;

        Placeholder(
                String prefix,
                int number,
                String origin,
                String controller,
                String displayName,
                long startTime,
                long epoch,
                Map<String, String> properties,
                long version) {
            this.prefix = prefix;
            this.number = number;
            this.origin = origin;
            this.controller = controller;
            this.displayName = displayName;
            this.startTime = startTime;
            this.epoch = epoch;
            this.properties = Map.copyOf(properties);
            this.version = version;
        }

        Placeholder withOwner(
                String origin, String controller, long epoch, Map<String, String> properties, long version) {
            return new Placeholder(
                    prefix, number, origin, controller, displayName, startTime, epoch, properties, version);
        }

        /** The item prefix of the job ({@link Keys#item}). */
        @NonNull
        public String getPrefix() {
            return prefix;
        }

        /** Its key in the store. */
        @NonNull
        public String getKey() {
            return Keys.placeholder(prefix, number);
        }

        /** The key of the build's lease. */
        @NonNull
        public String getLeaseKey() {
            return Keys.buildLease(prefix, number);
        }

        /** The lease epoch of the owner when it wrote this placeholder. */
        public long getEpoch() {
            return epoch;
        }

        /** Properties recorded by the owner through {@link RunningBuilds#update}, such as where its save points are. */
        @NonNull
        public Map<String, String> getProperties() {
            return properties;
        }

        @Exported
        public int getNumber() {
            return number;
        }

        /** The {@link PersistenceStore#getOrigin} of the controller running the build. */
        @Exported
        public String getOrigin() {
            return origin;
        }

        /** The name of the controller running the build. */
        @Exported
        public String getController() {
            return controller;
        }

        @Exported
        public String getDisplayName() {
            return displayName;
        }

        /** When the build started, in milliseconds since the epoch. */
        @Exported
        public long getStartTime() {
            return startTime;
        }

        /** {@link #getStartTime} as a date, for views. */
        public Date getStartDate() {
            return new Date(startTime);
        }

        byte[] toBytes() throws IOException {
            Properties p = new Properties();
            p.setProperty("origin", origin);
            p.setProperty("controller", controller);
            p.setProperty("displayName", displayName);
            p.setProperty("startTime", Long.toString(startTime));
            p.setProperty("epoch", Long.toString(epoch));
            properties.forEach((k, v) -> p.setProperty("x." + k, v));
            StringWriter w = new StringWriter();
            p.store(w, null);
            return w.toString().getBytes(StandardCharsets.UTF_8);
        }

        @CheckForNull
        static Placeholder fromBytes(String prefix, int number, byte[] content, long version) {
            Properties p = new Properties();
            try {
                p.load(new StringReader(new String(content, StandardCharsets.UTF_8)));
                Map<String, String> extra = new TreeMap<>();
                for (String name : p.stringPropertyNames()) {
                    if (name.startsWith("x.")) {
                        extra.put(name.substring(2), p.getProperty(name));
                    }
                }
                return new Placeholder(
                        prefix,
                        number,
                        p.getProperty("origin", ""),
                        p.getProperty("controller", "?"),
                        p.getProperty("displayName", "#" + number),
                        Long.parseLong(p.getProperty("startTime", "0")),
                        Long.parseLong(p.getProperty("epoch", "0")),
                        extra,
                        version);
            } catch (IOException | NumberFormatException e) {
                LOGGER.log(Level.WARNING, "Malformed placeholder " + Keys.placeholder(prefix, number), e);
                return null;
            }
        }

        @Override
        public String toString() {
            return getKey() + " (" + controller + ", epoch " + epoch + ")";
        }
    }

    /** The build number of a placeholder's name ({@code 5.xml}), or -1. */
    static int placeholderNumber(String name) {
        if (!name.endsWith(SUFFIX)) {
            return -1;
        }
        try {
            return Integer.parseInt(name.substring(0, name.length() - SUFFIX.length()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Forgets the cached placeholders of a job, by its item prefix. */
    static void invalidate(@NonNull String prefix) {
        cache.remove(prefix);
    }

    @NonNull
    private static List<Placeholder> load(@NonNull ClusterStore store, @NonNull String prefix) {
        List<Placeholder> placeholders = new ArrayList<>();
        try {
            for (String name : store.children(Keys.running(prefix))) {
                int number = placeholderNumber(name);
                if (number < 0) {
                    continue;
                }
                PersistenceStore.Entry entry = store.read(Keys.placeholder(prefix, number));
                Placeholder p =
                        entry == null ? null : Placeholder.fromBytes(prefix, number, entry.content(), entry.version());
                if (p != null) {
                    placeholders.add(p);
                }
            }
        } catch (IOException e) {
            // not cached: the next call asks the store again
            throw new java.io.UncheckedIOException(e);
        }
        placeholders.sort(Comparator.comparingInt(Placeholder::getNumber).reversed());
        return List.copyOf(placeholders);
    }

    /** All placeholders of a job, from the cache, else the store (I/O). */
    @NonNull
    private static List<Placeholder> all(@NonNull Job<?, ?> job) {
        ClusterStore store = ClusterStore.get();
        String prefix = BuildRecords.prefix(job);
        if (store == null || prefix == null) {
            return List.of();
        }
        try {
            return cache.computeIfAbsent(prefix, k -> load(store, k));
        } catch (java.io.UncheckedIOException e) {
            LOGGER.log(Level.FINE, "Could not list running builds of " + job.getFullName(), e.getCause());
            return List.of();
        }
    }

    /**
     * Builds of a job running on other controllers, newest first, excluding any whose completed record this controller
     * already knows. May ask the store: not for callers under locks or rendering pages ({@link #elsewhereCached}).
     */
    @NonNull
    public static List<Placeholder> elsewhere(@NonNull Job<?, ?> job) {
        return elsewhere(job, all(job));
    }

    /** Jobs whose placeholders are being loaded for {@link #elsewhereCached}, by item prefix. */
    private static final Set<String> loading = ConcurrentHashMap.newKeySet();

    /**
     * {@link #elsewhere}, without asking the store: from what is cached, or null if nothing is. The job's placeholders
     * are then loaded in the background, and the queue looked at again.
     */
    @CheckForNull
    public static List<Placeholder> elsewhereCached(@NonNull Job<?, ?> job) {
        String prefix = BuildRecords.prefix(job);
        if (prefix == null) {
            return List.of();
        }
        List<Placeholder> cached = cache.get(prefix);
        if (cached != null) {
            return elsewhere(job, cached);
        }
        if (loading.add(prefix)) {
            Timer.get().submit(() -> {
                try {
                    all(job);
                } finally {
                    loading.remove(prefix);
                }
                if (cache.containsKey(
                        prefix)) { // else not loaded (the store is unreachable): asked again at the next maintenance
                    Queue.getInstance().scheduleMaintenance();
                }
            });
        }
        return null;
    }

    @NonNull
    private static List<Placeholder> elsewhere(@NonNull Job<?, ?> job, @NonNull List<Placeholder> all) {
        ClusterStore store = ClusterStore.get();
        List<Placeholder> result = new ArrayList<>();
        for (Placeholder p : all) {
            if (store != null && p.origin.equals(store.getOrigin())) {
                continue; // running here, so a real build
            }
            if (job instanceof LazyBuildMixIn.LazyLoadingJob<?, ?> lazy
                    && lazy.getLazyBuildMixIn()._getRuns().knows(p.number)) {
                continue; // completed meanwhile
            }
            result.add(p);
        }
        return result;
    }

    /** Builds owned by this controller: the placeholder as last written here. Guarded by itself. */
    private static final Map<Run<?, ?>, Placeholder> owned = new WeakHashMap<>();
    /** Builds this controller has lost to another one. */
    private static final Set<Run<?, ?>> lost =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    /**
     * Copies of builds lost to another controller and discarded here: one still referenced (by a plugin, or a Pipeline's
     * execution) may be saved after it is no longer {@link #lost}, and must never be published.
     */
    private static final Set<Run<?, ?>> discarded =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    /**
     * One lock per owned build, so that its placeholder is updated by one thread at a time: two updates at once (a
     * Pipeline's last save point, and its result as it completes) would each compare-and-set the same version, and the
     * one failing would take the build for lost.
     */
    private static final Map<Run<?, ?>, Object> updating = Collections.synchronizedMap(new WeakHashMap<>());

    @CheckForNull
    private static Placeholder owned(Run<?, ?> run) {
        synchronized (owned) {
            return owned.get(run);
        }
    }

    private static void own(Run<?, ?> run, Placeholder p) {
        synchronized (owned) {
            owned.put(run, p);
        }
    }

    private static void disown(Run<?, ?> run) {
        synchronized (owned) {
            owned.remove(run);
        }
        updating.remove(run);
    }

    /** The build owned here with this placeholder key, if any. */
    @CheckForNull
    private static Run<?, ?> ownedRun(String prefix, int number) {
        synchronized (owned) {
            for (Map.Entry<Run<?, ?>, Placeholder> e : owned.entrySet()) {
                if (e.getValue().prefix.equals(prefix) && e.getValue().number == number) {
                    return e.getKey();
                }
            }
        }
        return null;
    }

    /** The placeholder this controller holds for a build of a job, by number, even if the build is not loaded. */
    @CheckForNull
    private static Placeholder ownedPlaceholder(String prefix, int number) {
        synchronized (owned) {
            for (Placeholder p : owned.values()) {
                if (p.prefix.equals(prefix) && p.number == number) {
                    return p;
                }
            }
        }
        return null;
    }

    static void started(@NonNull Run<?, ?> run) throws IOException {
        String prefix = BuildRecords.prefix(run.getParent());
        ClusterStore store = ClusterStore.get();
        if (prefix == null || store == null) {
            return;
        }
        String leaseKey = Keys.buildLease(prefix, run.getNumber());
        long epoch = store.leases().acquire(leaseKey);
        if (epoch == 0) {
            // another live controller owns this very build: it must not run here too
            LOGGER.warning(() -> "Another controller holds the lease on " + leaseKey + "; stopping " + run + " here");
            lost(run);
            return;
        }
        Placeholder p = new Placeholder(
                prefix,
                run.getNumber(),
                store.getOrigin(),
                ClusterMember.NAME,
                run.getDisplayName(),
                run.getStartTimeInMillis(),
                epoch,
                Map.of(),
                PersistenceStore.ABSENT);
        long version = store.leases().writeIfHeld(leaseKey, epoch, p.getKey(), p.toBytes(), PersistenceStore.ANY);
        own(run, p.withOwner(p.origin, p.controller, epoch, Map.of(), version));
        invalidate(prefix);
        stopRequestedMeanwhile(run, prefix);
    }

    /** Stops a build asked to stop between its placeholder's write and its registration: that request was not taken as ours. */
    private static void stopRequestedMeanwhile(@NonNull Run<?, ?> run, String prefix) throws IOException {
        ClusterStore store = ClusterStore.get();
        if (store != null && store.read(Keys.stopRequest(prefix, run.getNumber())) != null) {
            stopRequested(prefix, run.getNumber());
        }
    }

    /** Whether a build's saved record says it completed (a record of a running build has no result yet). */
    private static boolean completedLocally(File buildXml) {
        try {
            String xml = java.nio.file.Files.readString(buildXml.toPath(), StandardCharsets.UTF_8);
            return xml.contains("<result>") && !xml.contains("<completed>false</completed>");
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Builds of a job whose lease this controller took over from its previous incarnation, until they are loaded. */
    private static final Map<String, Placeholder> reclaimed = new ConcurrentHashMap<>();

    /**
     * Takes over the lease of a build this controller's previous incarnation (the same name, another origin) was running,
     * before it is loaded from this home.
     *
     * @return false if another controller holds it, so the local copy must not be loaded (nor resumed)
     */
    static boolean reclaim(@NonNull Job<?, ?> job, int number) throws IOException {
        ClusterStore store = ClusterStore.get();
        String prefix = BuildRecords.prefix(job);
        if (prefix == null) {
            return true;
        }
        if (ownedPlaceholder(prefix, number) != null) {
            return true;
        }
        // first what costs nothing: a fresh home has no copy of most builds (asked of each stored number at startup:
        // no store calls for them)
        File local = new File(new File(job.getBuildDir(), String.valueOf(number)), BuildRecords.RECORD);
        if (!local.isFile()) {
            return true; // nothing of a previous run here
        }
        Placeholder cached = null;
        for (Placeholder p : all(job)) {
            if (p.number == number) {
                cached = p;
            }
        }
        if (cached != null && store.getOrigin().equals(cached.origin)) {
            return true; // this very process runs it
        }
        if (cached == null && completedLocally(local)) {
            return true; // not running anywhere (the placeholders listed may be stale: a running build is checked
            // below)
        }
        // a build this home has, running somewhere: this controller's previous run had it, and another controller may
        // have adopted it meanwhile (a restart takes longer than seeing a controller gone): ask the store, not the
        // cache
        String key = Keys.placeholder(prefix, number);
        PersistenceStore.Entry entry = store.read(key);
        Placeholder p = entry == null ? null : Placeholder.fromBytes(prefix, number, entry.content(), entry.version());
        if (p == null || !ClusterMember.NAME.equals(p.controller)) {
            if (p != null) {
                LOGGER.info(() -> job.getFullName() + " #" + number + " was adopted by " + p.controller
                        + " while this controller restarted; not resuming it here");
                return false;
            }
            if (completedLocally(local)) {
                return true; // completed here before the restart
            }
            // no placeholder, and the copy here says running: it completed elsewhere (adopted while this controller was
            // down): load its record, not this copy, which would resume it a second time
            if (BuildRecords.isStored(job, number)) {
                java.nio.file.Files.delete(local.toPath());
                if (BuildRecords.materialize(job, local.getParentFile())) {
                    LOGGER.info(() -> job.getFullName() + " #" + number
                            + " completed elsewhere while this controller restarted; loading its record");
                    return true;
                }
            }
            LOGGER.info(() -> job.getFullName() + " #" + number
                    + " is neither running nor recorded anywhere; not resuming it here");
            return false;
        }
        if (store.getOrigin().equals(p.origin)) {
            return true;
        }
        // as an adoption does: while this controller restarted, its exclusive lease on the job (one build at a time)
        // may have lapsed, and another controller started a newer build; this one then waits for it
        if (!ExclusiveLeases.adopt(job, number)) {
            throw new NotYet(
                    "another build of " + job.getFullName() + " runs; #" + number + " is resumed here once it is done");
        }
        boolean done = false;
        try {
            BuildLeases leases = store.leases();
            String leaseKey = Keys.buildLease(prefix, number);
            long epoch = leases.acquire(leaseKey);
            if (epoch == 0) {
                LOGGER.info(() -> "Another controller holds the lease on " + job.getFullName() + " #" + number
                        + "; not resuming it here");
                return false;
            }
            Placeholder mine =
                    p.withOwner(store.getOrigin(), ClusterMember.NAME, epoch, owning(p.properties), p.version);
            long version;
            try {
                version = leases.writeIfHeld(
                        leaseKey, epoch, key, mine.toBytes(), p.version); // fenced, as an adoption is
            } catch (LeaseLostException e) {
                LOGGER.info(() -> "Another controller took " + job.getFullName() + " #" + number
                        + " over while it was reclaimed after a restart; not resuming it here");
                return false;
            } catch (ConflictException e) {
                LOGGER.info(() -> job.getFullName() + " #" + number
                        + " changed while being reclaimed after a restart; not resuming it here");
                leases.release(leaseKey, epoch);
                return false;
            }
            reclaimed.put(key, mine.withOwner(mine.origin, mine.controller, epoch, mine.properties, version));
            invalidate(prefix);
            LOGGER.info(() -> "Reclaimed " + job.getFullName() + " #" + number
                    + " from this controller's previous run (" + p.origin + ") with epoch " + epoch);
            done = true;
            return true;
        } finally {
            if (!done) {
                ExclusiveLeases.abandon(job, number); // not resumed here: the job is not blocked for it
            }
        }
    }

    /** A build was loaded ({@link ClusterBuildRecordSource#retrieve}): one reclaimed or adopted here is owned by its object. */
    static void loaded(@NonNull Run<?, ?> run) {
        BuildRecords.followNumber(run.getParent(), run.getNumber()); // one numbered elsewhere, adopted or not
        if (!reclaimed.isEmpty()) {
            loadedReclaimed(run);
        }
    }

    /** A build reclaimed after a restart was loaded: it is owned here from now on, by its object. */
    private static void loadedReclaimed(@NonNull Run<?, ?> run) {
        String prefix = BuildRecords.prefix(run.getParent());
        Placeholder p = prefix == null ? null : reclaimed.remove(Keys.placeholder(prefix, run.getNumber()));
        if (p == null) {
            return;
        }
        own(run, p);
        ExclusiveLeases.resumed(run.getParent(), run.getNumber()); // loaded: the queue here sees it
        stopIfRequested(prefix, run.getNumber(), 0);
    }

    /** A build that cannot be reclaimed yet (another build of its job runs), to be looked at again later. */
    static final class NotYet extends IOException {
        NotYet(String message) {
            super(message);
        }
    }

    /**
     * Whether this controller may still work on and publish a build it started or adopted.
     * False once another controller has taken it over (for example after this one was partitioned).
     */
    public static boolean owns(@NonNull Run<?, ?> run) {
        if (lost.contains(run) || discarded.contains(run)) {
            return false; // a discarded copy is no longer lost, nor owned: it is another controller's build
        }
        Placeholder p = owned(run);
        ClusterStore store = ClusterStore.get();
        if (p == null || store == null) {
            return true; // not tracked (not shared, or not registered yet: then the write of its record is the fence)
        }
        try {
            return store.leases().holds(p.getLeaseKey(), p.epoch);
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Cannot check the lease on " + p.getLeaseKey() + "; taking it as still held", e);
            return true;
        }
    }

    /**
     * Records properties of a running build in its placeholder, for example where its latest save point is; those
     * recorded before are kept unless given again. Fenced by the build's lease.
     *
     * @throws ConflictException if the build is no longer owned here; the caller should stop working on it
     *     (see {@link #lost})
     * @throws IllegalStateException if the build is shared but not owned here (never registered, or completed)
     */
    public static void update(@NonNull Run<?, ?> run, @NonNull Map<String, String> properties) throws IOException {
        if (lost.contains(run)) {
            throw new ConflictException(
                    String.valueOf(run), PersistenceStore.ANY, PersistenceStore.ANY); // no longer owned here
        }
        if (!BuildRecords.isShared(run.getParent())) {
            return;
        }
        Object lock;
        synchronized (updating) {
            lock = updating.computeIfAbsent(run, k -> new Object());
        }
        synchronized (lock) {
            Placeholder p = owned(run);
            if (p == null) {
                throw new IllegalStateException(run + " is not owned here");
            }
            if (lost.contains(run) || !owns(run)) {
                throw new ConflictException(p.getKey(), p.version, PersistenceStore.ANY);
            }
            Map<String, String> merged = new HashMap<>(p.properties);
            merged.putAll(properties); // e.g. the latest save point and, once completed, the result
            Placeholder next = p.withOwner(p.origin, p.controller, p.epoch, merged, p.version);
            ClusterStore store = ClusterStore.get();
            if (store == null) {
                return; // no cluster store (any more): nothing shared to update
            }
            long version;
            try {
                version = store.leases().writeIfHeld(p.getLeaseKey(), p.epoch, p.getKey(), next.toBytes(), p.version);
            } catch (LeaseLostException e) {
                throw new ConflictException(
                        p.getKey(), p.version, PersistenceStore.ANY); // taken over since it was checked
            }
            own(run, p.withOwner(p.origin, p.controller, p.epoch, merged, version));
        }
    }

    /**
     * Stops this controller working on a build which another controller has taken over: interrupts it (a Pipeline
     * through its execution, as it has no executor of its own), and makes sure it is neither published nor its
     * placeholder removed from here. Its local copy is discarded once it has stopped.
     *
     * <p>The copy stops as not built, not aborted: the build carries on elsewhere, so what reacts to the result of the
     * copy here (a {@code post} section, a notifier) does not take it for aborted.
     */
    public static void lost(@NonNull Run<?, ?> run) {
        if (!lost.add(run)) {
            return;
        }
        disown(run);
        LOGGER.warning(() -> "Ownership of " + run + " was lost to another controller; stopping it here");
        if (run.isLogUpdated()) { // not once completed: only fenced then
            interrupt(run, Result.NOT_BUILT, new OwnershipLost());
        }
    }

    /** Cause of interruption of a build taken over by another controller. */
    public static final class OwnershipLost extends CauseOfInterruption {
        @Override
        public String getShortDescription() {
            return "Another controller took over this build";
        }
    }

    /**
     * Removes the placeholder of a build which completed here; called once its record is published.
     */
    static void finished(@NonNull Run<?, ?> run) throws IOException {
        Placeholder p = owned(run);
        if (p != null) {
            finished(p);
            disown(run); // only once all is done: should a delete fail, the retry still knows the epoch to release the
            // lease with
        } else {
            String prefix = BuildRecords.prefix(run.getParent());
            ClusterStore store = ClusterStore.get();
            if (prefix != null && store != null) {
                // not owned here (registered without a lease, say): its placeholder if it names this controller
                PersistenceStore.Entry e = store.read(Keys.placeholder(prefix, run.getNumber()));
                Placeholder stored =
                        e == null ? null : Placeholder.fromBytes(prefix, run.getNumber(), e.content(), e.version());
                if (stored != null && stored.origin.equals(store.getOrigin())) {
                    finished(stored);
                }
            }
        }
    }

    /** {@link #finished(Run)} by its placeholder, for a build which may be gone (deleted) since. */
    static void finished(@NonNull Placeholder p) throws IOException {
        ClusterStore store = ClusterStore.get();
        if (store == null) {
            return;
        }
        store.delete(p.getKey(), p.version);
        store.delete(Keys.stopRequest(p.prefix, p.number), PersistenceStore.ANY);
        store.leases().release(p.getLeaseKey(), p.epoch);
        invalidate(p.prefix);
    }

    /**
     * Whether a build was lost to another controller, so it must not be published from here.
     */
    static boolean wasLost(@NonNull Run<?, ?> run) {
        return lost.contains(run);
    }

    /**
     * Discards the local copy of a build lost to another controller, once it has stopped here: at once if it has (it
     * is finalized), so that it is not shown here as the stopped copy but as running elsewhere, or with the record its
     * new owner published. The other controller publishes it when it completes, and this controller then picks up that
     * record.
     */
    static void discard(@NonNull Run<?, ?> run) {
        discard(run, true, 0);
    }

    /**
     * Whether this is a copy of a build discarded here, lost to another controller: a save of it is not published, and
     * the local copy it wrote is discarded again ({@link #discardAgain}).
     */
    static boolean wasDiscarded(@NonNull Run<?, ?> run) {
        return discarded.contains(run);
    }

    /**
     * Discards again the local copy of a build which a {@linkplain #wasDiscarded discarded} copy saved: its
     * {@code build.xml} would otherwise stand for the new owner's record here.
     */
    static void discardAgain(@NonNull Run<?, ?> run) {
        LOGGER.fine(() -> "A discarded copy of " + run + " was saved; discarding the local copy again");
        discard(run, false, 0);
    }

    private static void discard(Run<?, ?> run, boolean wasLost, int attempt) {
        Job<?, ?> job = run.getParent();
        int number = run.getNumber();
        Timer.get()
                .schedule(
                        () -> {
                            try {
                                PersistenceStores.applyingRemoteChange(() -> {
                                    // whatever is stored now: the record there is its new owner's, not this copy
                                    BuildRecords.forgetLostCopy(job, number);
                                    if (BuildRecords.isStored(job, number)) {
                                        // its new owner published it already, and this controller applied that to the
                                        // copy just discarded
                                        BuildRecords.writtenElsewhere(job, number);
                                    }
                                    return null;
                                });
                                discarded.add(
                                        run); // before it is no longer lost: a save of this copy is never published
                                if (wasLost) {
                                    lost.remove(run);
                                }
                            } catch (IOException | RuntimeException e) {
                                // kept fenced (lost) until discarded: a later save of the local copy must not be
                                // published
                                LOGGER.log(
                                        Level.WARNING,
                                        "Failed to discard the local copy of " + job.getFullName() + " #" + number
                                                + "; retrying",
                                        e);
                                if (attempt < MAX_DISCARD_ATTEMPTS) {
                                    discard(run, wasLost, attempt + 1);
                                }
                            }
                        },
                        attempt == 0 && !run.isLogUpdated() ? 0 : DISCARD_DELAY_SECONDS * (1L << Math.min(attempt, 6)),
                        TimeUnit.SECONDS);
    }

    private static final int MAX_DISCARD_ATTEMPTS = 10;

    /** Lets a build still stopping finish writing locally before its copy is discarded (its finalization discards it too). */
    private static final int DISCARD_DELAY_SECONDS = 5;

    /** Builds started here whose placeholder and lease could not be recorded (the store was unreachable): retried. */
    private static final Set<Run<?, ?>> unregistered =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    /** Records builds as running whose registration failed when they started. */
    static void registerPending() {
        for (Run<?, ?> run : List.copyOf(unregistered)) {
            if (!run.isLogUpdated()) {
                unregistered.remove(run); // completed meanwhile: its completion published it, or queued a retry
                continue;
            }
            try {
                String prefix = BuildRecords.prefix(run.getParent());
                if (prefix == null) {
                    unregistered.remove(run);
                    continue;
                }
                if (owned(run) != null) {
                    // recorded already, and only the look-up that followed failed: not written again, which would drop
                    // what it recorded since (a save point)
                    stopRequestedMeanwhile(run, prefix);
                } else {
                    started(run);
                }
                unregistered.remove(run);
                if (!run.isLogUpdated()) {
                    // it completed while being recorded, and may be being published under the lease just taken:
                    // completed
                    // again a little later (published, then its placeholder removed, in that order), not cleaned up now
                    BuildRecords.completeLater(run);
                    continue;
                }
                LOGGER.info(() -> "Recorded " + run + " as running, after the store was back");
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.FINE, "Still cannot record " + run + " as running", e);
            }
        }
    }

    /**
     * Checks that this controller still holds the leases of the builds it runs, and stops those it does not.
     */
    static void checkOwnership() {
        List<Run<?, ?>> runs;
        synchronized (owned) {
            runs = new ArrayList<>(owned.keySet());
        }
        for (Run<?, ?> run : runs) {
            if (run.isLogUpdated() && !owns(run)) {
                lost(run);
            }
        }
    }

    private static final AtomicBoolean sweeping = new AtomicBoolean();

    /**
     * Adopts or closes the builds of controllers which are gone, now rather than at the next periodic sweep: for a store
     * noticing that a controller has left the cluster.
     */
    public static void sweepSoon() {
        Timer.get().submit(() -> {
            try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
                int n = removeStale();
                if (n > 0) {
                    LOGGER.info(() -> "Took care of " + n + " build(s) of controllers which left the cluster");
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.WARNING, "Could not sweep the builds of controllers which left", e);
            }
        });
    }

    /**
     * Adopts, or closes, the builds of controllers which are no longer alive.
     *
     * @return the number taken care of
     */
    static int removeStale() throws IOException {
        // one sweep at a time, whichever way it was started (the periodic one, or sweepSoon): leases are reentrant for
        // this controller, so two at once could both adopt, and resume, the same build
        if (!sweeping.compareAndSet(false, true)) {
            return 0; // another sweep is under way; the periodic one follows anyway
        }
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            return sweep();
        } finally {
            sweeping.set(false);
        }
    }

    private static int sweep() throws IOException {
        ClusterStore store = ClusterStore.get();
        Set<String> live = liveOrigins();
        if (store == null || live == null) {
            return 0;
        }
        int removed = 0;
        BuildLeases leases = store.leases();
        // every placeholder of the cluster, at once (not job by job)
        Map<String, List<Placeholder>> byPrefix = new HashMap<>();
        for (Map.Entry<String, byte[]> e : store.runningPlaceholders().entrySet()) {
            Keys.ItemKey k = Keys.parse(e.getKey());
            int number = k == null
                    ? -1
                    : placeholderNumber(k.rest().substring(k.rest().indexOf('/') + 1));
            if (number < 0) {
                continue;
            }
            Placeholder p =
                    Placeholder.fromBytes(Keys.itemByUuid(k.uuid()), number, e.getValue(), PersistenceStore.ANY);
            if (p != null) {
                byPrefix.computeIfAbsent(k.uuid(), x -> new ArrayList<>()).add(p);
            }
        }
        Set<String> gone = new HashSet<>();
        for (Map.Entry<String, List<Placeholder>> e : byPrefix.entrySet()) {
            Job<?, ?> job = ItemEvents.job(e.getKey());
            if (job == null) {
                continue; // not loaded here (yet), or deleted: its controller's deletion removes the placeholders
            }
            int removedHere = 0;
            for (Placeholder listed : e.getValue()) {
                // never this controller's own: its heartbeat can be late under load, and it would adopt (and resume a
                // second time) a build it is still running; a membership that really lapsed is fenced (checkOwnership).
                // Except an adoption here that may have been written although its write failed: nothing runs it here;
                // and a build handed over and not taken (its taker went away, say), adopted as one of a controller gone
                if (!(unsure.contains(listed.getKey())
                        || !live.contains(listed.origin)
                                && !listed.origin.equals(store.getOrigin())
                                && stillGone(store, listed.origin, gone)
                        || handoverLapsed(listed))) {
                    continue;
                }
                long epoch = leases.acquire(listed.getLeaseKey());
                if (epoch == 0) {
                    continue; // another controller is adopting it
                }
                Adoption adopted;
                try {
                    adopted = adopt(store, job, listed.getPrefix(), listed.number, epoch);
                } catch (IOException | RuntimeException x) {
                    // its placeholder is kept, for the next sweep to try again; the other builds are still looked at
                    LOGGER.log(
                            Level.WARNING,
                            "Could not adopt " + job.getFullName() + " #" + listed.number + "; will retry",
                            x);
                    leases.release(listed.getLeaseKey(), epoch);
                    continue;
                }
                if (adopted != Adoption.NONE) {
                    removedHere++;
                    continue;
                }
                if (close(store, job, listed, epoch)) {
                    removedHere++;
                }
                leases.release(listed.getLeaseKey(), epoch);
            }
            removed += removedHere;
            if (removedHere > 0) {
                invalidate(Keys.itemByUuid(e.getKey()));
            }
        }
        return removed;
    }

    /**
     * Closes a build nobody can adopt, with its lease held: publishes it as it ended (its result, if its owner recorded
     * one before dying) or as aborted, then removes its placeholder.
     *
     * @return whether its placeholder was removed
     */
    private static boolean close(ClusterStore store, Job<?, ?> job, Placeholder listed, long epoch) throws IOException {
        // as stored now, and only while this controller still holds its lease: one that lapsed while the adoption was
        // prepared (a long pause) may have let another controller adopt the build, writing it
        PersistenceStore.Entry now = store.read(listed.getKey());
        if (now == null || !store.leases().holds(listed.getLeaseKey(), epoch)) {
            LOGGER.fine(() -> listed.getKey() + " completed, or was taken over, meanwhile; left alone");
            return false;
        }
        LOGGER.info(() -> "Removing placeholder of " + job.getFullName() + " #" + listed.number + " left by "
                + listed.controller + " (" + listed.origin + "), which is gone");
        try {
            // its result as stored now: the placeholder listed may be older than its owner's last write
            Placeholder fresh = Placeholder.fromBytes(listed.prefix, listed.number, now.content(), now.version());
            String completed = (fresh != null ? fresh : listed).properties.get(RESULT);
            if (completed != null) {
                // it completed, but its owner was gone before publishing its record: publish it with its result
                BuildRecords.publishLost(
                        job,
                        listed.number,
                        listed.startTime,
                        "This build completed on " + listed.controller
                                + ", which was gone before publishing its record; it is closed with its result.",
                        Result.fromString(completed));
            } else {
                // nothing can resume it: close it as aborted rather than let it vanish
                BuildRecords.publishLost(
                        job,
                        listed.number,
                        listed.startTime,
                        "This build was running on " + listed.controller
                                + ", which is gone, and could not be resumed elsewhere; it is closed as aborted.");
            }
        } catch (IOException | RuntimeException e) {
            // keep the placeholder (the caller releases the lease), so that the next sweep tries again
            LOGGER.log(
                    Level.WARNING,
                    "Could not close " + job.getFullName() + " #" + listed.number + " as aborted; will retry",
                    e);
            return false;
        }
        try {
            // only as read: several controllers noticing at once is harmless, and a write since is kept
            store.delete(listed.getKey(), now.version());
            return true;
        } catch (ConflictException e) {
            LOGGER.fine(() -> listed.getKey() + " changed meanwhile; left alone");
            return false;
        }
    }

    /**
     * Whether the origin of a placeholder missing from the sweep's first answer is still gone when asked after the
     * placeholder was listed: its controller may have joined since (a restart, during a rolling one) and written it.
     */
    private static boolean stillGone(ClusterStore store, String origin, Set<String> gone) throws IOException {
        if (gone.contains(origin)) {
            return true;
        }
        Set<String> live = liveOrigins();
        if (live == null || live.contains(origin)) {
            return false;
        }
        gone.add(origin);
        return true;
    }

    /**
     * The log of a build running on another controller, readable here: e.g. from where its owner ships it.
     */
    public interface RemoteLog extends ExtensionPoint {
        /**
         * Copies a build's log from {@code offset}.
         *
         * @return the offset after what was copied, or -1 if this extension cannot read that build's log
         */
        long copy(@NonNull Job<?, ?> job, int number, long offset, @NonNull java.io.OutputStream out)
                throws IOException;
    }

    /** Whether a build of a job is running on another controller (from the cache). */
    public static boolean isRunningElsewhere(@NonNull Job<?, ?> job, int number) {
        List<Placeholder> elsewhere = elsewhereCached(job);
        return elsewhere != null && elsewhere.stream().anyMatch(p -> p.getNumber() == number);
    }

    /**
     * Asks whichever controller runs a build to stop it.
     * If it runs here, it is stopped right away; otherwise its owner (the holder of its lease, including one that adopts it later)
     * stops it when it sees the request.
     *
     * @param by who asked, for the build log
     */
    public static void requestStop(@NonNull Job<?, ?> job, int number, @NonNull String by) throws IOException {
        String prefix = BuildRecords.prefix(job);
        if (prefix == null) {
            return;
        }
        Run<?, ?> run = ownedRun(prefix, number);
        if (run != null && run.isLogUpdated() && interrupt(run, new StoppedElsewhere(ClusterMember.NAME, by))) {
            return;
        }
        ClusterStore store = ClusterStore.get();
        if (store == null || store.read(Keys.placeholder(prefix, number)) == null) {
            return; // no such build runs: a request left behind would stop a later build given that number
        }
        Properties request = new Properties();
        request.setProperty("controller", ClusterMember.NAME);
        request.setProperty("by", by);
        StringWriter w = new StringWriter();
        request.store(w, null); // escaped, as it is read (a user name may hold a backslash)
        store.write(
                Keys.stopRequest(prefix, number), w.toString().getBytes(StandardCharsets.UTF_8), PersistenceStore.ANY);
        if (ownedPlaceholder(prefix, number) != null) {
            // owned here, but not loaded or given an executor yet (being resumed): stopped once it has one (the event
            // of
            // this controller's own write is not applied here)
            stopRequested(prefix, number);
        }
    }

    /** How many times a stop request is retried while the build it is for has no executor yet (being resumed). */
    private static final int STOP_ATTEMPTS = 8;

    /**
     * Stops a build owned here for which another controller requested a stop.
     */
    static void stopRequested(@NonNull String prefix, int number) throws IOException {
        stopRequested(prefix, number, 0);
    }

    private static void stopRequested(String prefix, int number, int attempt) throws IOException {
        if (ownedPlaceholder(prefix, number) == null && !reclaimed.containsKey(Keys.placeholder(prefix, number))) {
            return; // not ours: its owner handles it
        }
        String stopKey = Keys.stopRequest(prefix, number);
        ClusterStore store = ClusterStore.get();
        if (store == null) {
            return;
        }
        PersistenceStore.Entry request = store.read(stopKey);
        if (request == null) {
            return;
        }
        Properties p = new Properties();
        p.load(new StringReader(new String(request.content(), StandardCharsets.UTF_8)));
        Run<?, ?> run = ownedRun(prefix, number);
        if (run == null
                || run.isLogUpdated()
                        && !interrupt(
                                run,
                                new StoppedElsewhere(p.getProperty("controller", "?"), p.getProperty("by", "?")))) {
            // adopted, but not loaded or given an executor yet: the request is kept (its build's completion removes it)
            if (attempt < STOP_ATTEMPTS) {
                stopLater(prefix, number, attempt);
            } else {
                LOGGER.warning(() -> "Could not stop " + prefix + " #" + number + " as requested: it has no executor");
            }
            return;
        }
        store.delete(stopKey, PersistenceStore.ANY);
    }

    /** Tries {@link #stopRequested} again later; again after that if the store fails then (nothing else would). */
    private static void stopLater(String prefix, int number, int attempt) {
        Timer.get()
                .schedule(
                        () -> {
                            try {
                                stopRequested(prefix, number, attempt + 1);
                            } catch (IOException | RuntimeException e) {
                                if (attempt + 1 < STOP_ATTEMPTS) {
                                    LOGGER.log(
                                            Level.INFO,
                                            "Failed to stop " + prefix + " #" + number + " as requested; retrying",
                                            e);
                                    stopLater(prefix, number, attempt + 1);
                                } else {
                                    LOGGER.log(
                                            Level.WARNING,
                                            "Failed to stop " + prefix + " #" + number + " as requested",
                                            e);
                                }
                            }
                        },
                        Math.min(30, 1L << attempt),
                        TimeUnit.SECONDS);
    }

    /** Stops a build adopted here if that was requested, looking again later if the store cannot tell now. */
    private static void stopIfRequested(String prefix, int number, int attempt) {
        try {
            stopRequested(prefix, number);
        } catch (IOException | RuntimeException e) {
            if (attempt < STOP_ATTEMPTS) {
                LOGGER.log(
                        Level.WARNING,
                        "Could not tell whether " + prefix + " #" + number + " was asked to stop; looking again later",
                        e);
                Timer.get()
                        .schedule(
                                () -> stopIfRequested(prefix, number, attempt + 1),
                                Math.min(30, 1L << attempt),
                                TimeUnit.SECONDS);
            } else {
                LOGGER.log(Level.WARNING, "Could not tell whether " + prefix + " #" + number + " was asked to stop", e);
            }
        }
    }

    /**
     * Interrupts a running build: through its executor, or for a Pipeline (which runs on none once started) through its
     * flow execution.
     *
     * @return false if it has neither (yet) to interrupt
     */
    static boolean interrupt(Run<?, ?> run, CauseOfInterruption cause) {
        return interrupt(run, Result.ABORTED, cause);
    }

    private static boolean interrupt(Run<?, ?> run, Result result, CauseOfInterruption cause) {
        Executor executor = run.getExecutor();
        if (executor != null) {
            LOGGER.info(() -> "Stopping " + run + ": " + cause.getShortDescription());
            executor.interrupt(result, cause);
            return true;
        }
        if (run instanceof FlowExecutionOwner.Executable pipeline) {
            try {
                FlowExecutionOwner owner = pipeline.asFlowExecutionOwner();
                FlowExecution execution = owner == null ? null : owner.getOrNull();
                if (execution != null) {
                    LOGGER.info(() -> "Stopping " + run + ": " + cause.getShortDescription());
                    execution.interrupt(result, cause);
                    return true;
                }
            } catch (IOException | InterruptedException | RuntimeException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                LOGGER.log(Level.WARNING, "Could not stop " + run, e);
            }
        }
        return false;
    }

    /** Cause of interruption of a build stopped from another controller. */
    public static final class StoppedElsewhere extends CauseOfInterruption {
        private final String controller;
        private final String by;

        StoppedElsewhere(String controller, String by) {
            this.controller = controller;
            this.by = by;
        }

        @Override
        public String getShortDescription() {
            return "Stopped from " + controller + " by " + by;
        }
    }

    /**
     * Placeholders whose adoption here failed as it was written, so that it may have been written all the same: they
     * name this controller, but nothing resumed them. Adopted again by the next sweep (or take-over).
     */
    private static final Set<String> unsure = ConcurrentHashMap.newKeySet();

    /** How many times adoption is retried when the placeholder changes under it. */
    private static final int ADOPTION_ATTEMPTS = 5;

    /** What became of a build offered for adoption. */
    enum Adoption {
        /** Adopted: resumed here, its lease kept. */
        ADOPTED,
        /** Completed (or removed) meanwhile: nothing to adopt; the lease is released. */
        GONE,
        /** Left for a later attempt (its placeholder is kept); the lease is released. */
        LATER,
        /** Nothing can adopt it; the lease is still held, for the caller to close it. */
        NONE
    }

    /**
     * Offers a build whose owner is gone to the {@link BuildAdopter}s, with its lease already held.
     *
     * @return what became of it
     */
    private static Adoption adopt(ClusterStore store, Job<?, ?> job, String prefix, int number, long epoch)
            throws IOException {
        Adoption result = null;
        try {
            result = offer(store, job, prefix, number, epoch);
            return result;
        } finally {
            if (result != Adoption.ADOPTED) {
                // not resumed here (gone, left for later, nobody could, or failing): the job's exclusive lease is not
                // kept for it, if it was taken
                ExclusiveLeases.abandon(job, number);
            }
        }
    }

    private static Adoption offer(ClusterStore store, Job<?, ?> job, String prefix, int number, long epoch)
            throws IOException {
        String key = Keys.placeholder(prefix, number);
        String leaseKey = Keys.buildLease(prefix, number);
        for (int attempt = 0; attempt < ADOPTION_ATTEMPTS; attempt++) {
            PersistenceStore.Entry entry = store.read(key);
            if (entry == null) {
                unsure.remove(key);
                // completed meanwhile: nothing to adopt, nor to keep the lease for
                store.leases().release(leaseKey, epoch);
                return Adoption.GONE;
            }
            if (BuildRecords.isStored(job, number)) {
                // completed: its record is published only once it has, and its owner was gone before removing this
                return Adoption.NONE;
            }
            Placeholder p = Placeholder.fromBytes(prefix, number, entry.content(), entry.version());
            if (p != null
                    && store.getOrigin().equals(p.origin)
                    && !unsure.contains(key)
                    && !p.properties.containsKey(HANDED_OVER)) {
                return Adoption
                        .ADOPTED; // this controller has it already (a sweep listed it as it was): its lease stays
            } // else written by an adoption here whose write seemed to fail, or handed over: not resumed here, so it
            // carries on
            Run<?, ?> local = BuildRecords.loadedCopy(job, number);
            if (local != null && local.isLogUpdated() && !local.hasntStartedYet()) {
                // a copy still running here (lost to the owner now gone, and not stopped yet): not adopted over it,
                // which
                // would leave two; the next sweep tries again
                throw new IOException(
                        job.getFullName() + " #" + number + " still runs here; adopting it once it has stopped");
            } else if (local != null && !local.hasntStartedYet()) {
                BuildRecords.forgetLostCopy(
                        job, number); // a stale copy (lost here before): the adopted build replaces it
            }
            if (p != null && p.properties.containsKey(RESULT)) {
                return Adoption
                        .NONE; // it completed; its owner was gone before publishing its record: nothing to resume
            }
            BuildAdopter.Adoption adoption = p == null ? null : prepare(job, p);
            if (adoption == null) {
                return Adoption.NONE;
            }
            boolean exclusive;
            try {
                exclusive = ExclusiveLeases.adopt(job, number);
            } catch (IOException | RuntimeException e) {
                adoption.abandon(); // retried by the next sweep, which prepares it again
                throw e;
            }
            if (!exclusive) {
                // a newer build of this job (which allows one at a time) runs elsewhere, or here: resume this one later
                adoption.abandon();
                store.leases().release(leaseKey, epoch);
                LOGGER.info(
                        () -> "Not adopting " + job.getFullName() + " #" + number + " yet: another build of it runs");
                return Adoption.LATER;
            }
            Placeholder mine =
                    p.withOwner(store.getOrigin(), ClusterMember.NAME, epoch, owning(p.properties), p.version);
            long version;
            try {
                // fenced by the lease: it may have lapsed while the adoption was prepared (a save point fetched), and
                // another controller taken the build over meanwhile
                version = store.leases().writeIfHeld(leaseKey, epoch, key, mine.toBytes(), p.version);
            } catch (LeaseLostException e) {
                adoption.abandon();
                throw e;
            } catch (ConflictException e) {
                // the previous owner recorded a newer save point while it was being fetched
                adoption.abandon();
                LOGGER.fine(() -> key + " changed while preparing its adoption; retrying");
                continue;
            } catch (IOException | RuntimeException e) {
                // perhaps written all the same (the answer lost): it then names this live controller, which a sweep
                // would leave alone, while nothing runs it here; so the next one adopts it again
                unsure.add(key);
                adoption.abandon();
                throw e;
            }
            unsure.remove(key);
            Placeholder adopted = mine.withOwner(mine.origin, mine.controller, epoch, mine.properties, version);
            reclaimed.put(key, adopted); // owned by the build's object once it is loaded (loaded)
            invalidate(prefix);
            LOGGER.info(() ->
                    "Adopting " + job.getFullName() + " #" + number + " from " + p.controller + " (" + p.origin + ")");
            try {
                adoption.resume(epoch);
            } catch (IOException | RuntimeException e) {
                resumeFailed(store, job, p, version, e);
            }
            Run<?, ?> run = BuildRecords.loadedCopy(job, number);
            if (run != null) {
                loadedReclaimed(run); // if the adopter loaded it without the listener seeing it
            }
            ExclusiveLeases.resumed(job, number); // loaded now (or not resumed, and let go of): the queue sees it
            // asked to stop while its owner was gone; it runs here now whatever this finds, so nothing below throws: a
            // caller takes an exception for "not resumed", and releases the lease
            stopIfRequested(prefix, number, 0);
            return Adoption.ADOPTED;
        }
        // its owner kept changing it: not as gone as it seemed; a later sweep looks again
        store.leases().release(leaseKey, epoch);
        return Adoption.LATER;
    }

    /**
     * Undoes the adoption of a build whose resumption failed before anything of it was loaded here: its placeholder
     * names its previous owner again, which is gone, so that a later sweep tries again (or closes it), rather than
     * leaving it to this live controller, which does not run it. The caller releases the lease.
     */
    private static void resumeFailed(ClusterStore store, Job<?, ?> job, Placeholder previous, long version, Exception e)
            throws IOException {
        Run<?, ?> loaded = BuildRecords.loadedCopy(job, previous.number);
        if (loaded != null && loaded.isLogUpdated()) {
            // partly resumed: it is here now, so this controller keeps it (and fences or completes it as any other)
            LOGGER.log(
                    Level.WARNING, "Resuming " + previous.getKey() + " failed after it was loaded; keeping it here", e);
            return;
        }
        reclaimed.remove(previous.getKey());
        try {
            store.write(previous.getKey(), previous.toBytes(), version);
        } catch (ConflictException x) {
            e.addSuppressed(x); // changed since: by its next owner
        }
        invalidate(previous.prefix);
        throw new IOException("Could not resume " + previous.getKey(), e);
    }

    /**
     * @return an adoption, or null if no adopter can take the build
     * @throws IOException if none could and one failed (perhaps for now only, as a save point not fetched): the build
     *     is then not taken for one nobody can adopt
     */
    @CheckForNull
    private static BuildAdopter.Adoption prepare(Job<?, ?> job, Placeholder p) throws IOException {
        Exception failure = null;
        for (BuildAdopter adopter : ExtensionList.lookup(BuildAdopter.class)) {
            try {
                BuildAdopter.Adoption adoption = adopter.prepare(job, p);
                if (adoption != null) {
                    return adoption;
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.WARNING, adopter + " failed to prepare adoption of " + p.getKey(), e);
                if (failure == null) {
                    failure = e;
                }
            }
        }
        if (failure != null) {
            throw failure instanceof IOException io ? io : new IOException(failure);
        }
        return null;
    }

    /**
     * Gives up a build this controller runs so that another controller can take it over ({@link #takeOver}), for example
     * a Pipeline waiting for an agent only another controller has. The build is then stopped here without being
     * published, as when ownership is lost. The caller should have a taker lined up: one that does not take it within
     * {@link #HANDOVER_GRACE_MILLIS} leaves it to any controller's sweep, which adopts it as if its owner were gone.
     */
    public static void handOver(@NonNull Run<?, ?> run) throws IOException {
        Placeholder p = owned(run);
        ClusterStore store = ClusterStore.get();
        if (p == null || store == null) {
            throw new IOException(run + " is not owned here");
        }
        // marked as handed over first (fenced: still this controller's), so that if nobody takes it, a sweep adopts it
        // after a while rather than it showing as running here for good
        Map<String, String> properties = new TreeMap<>(p.properties);
        properties.put(HANDED_OVER, String.valueOf(System.currentTimeMillis()));
        Placeholder released = p.withOwner(p.origin, p.controller, p.epoch, properties, p.version);
        try {
            store.leases().writeIfHeld(p.getLeaseKey(), p.epoch, p.getKey(), released.toBytes(), p.version);
        } catch (LeaseLostException e) {
            lost(run);
            throw e;
        }
        store.leases().release(p.getLeaseKey(), p.epoch);
        LOGGER.info(() -> "Handed over " + run);
        lost(run);
    }

    /**
     * Takes over a build another controller {@linkplain #handOver handed over}: acquires its lease and adopts it.
     *
     * @return whether it was adopted
     */
    public static boolean takeOver(@NonNull Job<?, ?> job, int number) throws IOException {
        ClusterStore store = ClusterStore.get();
        String prefix = BuildRecords.prefix(job);
        if (store == null || prefix == null) {
            return false;
        }
        String leaseKey = Keys.buildLease(prefix, number);
        long epoch = store.leases().acquire(leaseKey);
        if (epoch == 0) {
            return false; // not released (yet), or taken by another controller
        }
        Adoption adopted;
        try {
            adopted = adopt(store, job, prefix, number, epoch);
        } catch (IOException | RuntimeException e) {
            store.leases().release(leaseKey, epoch); // left as it was, for another attempt
            throw e;
        }
        // only an adoption is a takeover: a build that completed meanwhile was not taken over
        return switch (adopted) {
            case ADOPTED -> true;
            case GONE, LATER -> false; // its lease released already
            case NONE -> {
                store.leases().release(leaseKey, epoch);
                yield false;
            }
        };
    }

    /**
     * The placeholder of a build as this controller, its owner, last wrote it, or null if it is not owned here.
     */
    @CheckForNull
    public static Placeholder ownedPlaceholder(@NonNull Run<?, ?> run) {
        return owned(run);
    }

    /**
     * Applies a change another controller made below a job's {@code running/}: a placeholder (the cache is refreshed), or
     * a stop request.
     *
     * @param name the name below {@code running/}
     */
    static void changedElsewhere(@NonNull String uuid, @NonNull String prefix, @NonNull String name, boolean deleted) {
        if (placeholderNumber(name) >= 0) {
            invalidate(prefix); // no I/O: read again when next needed
            return;
        }
        if (deleted) {
            return;
        }
        if (name.endsWith(STOP_SUFFIX)) {
            try {
                int number = Integer.parseInt(name.substring(0, name.length() - STOP_SUFFIX.length()));
                ItemEvents.submit(uuid, prefix + " stop", () -> stopRequested(prefix, number));
            } catch (NumberFormatException e) {
                // not a stop request
            }
            return;
        }
    }

    /**
     * Records builds as running, before other listeners hear of their start (a higher ordinal): a queue item recorded
     * started by a controller gone since is put back into the queue when its build has neither placeholder nor record,
     * so a controller pausing after recording the start but before this would wake to run the build beside the one
     * built again elsewhere.
     */
    @Extension(ordinal = 1)
    public static final class Listener extends RunListener<Run<?, ?>> {

        /**
         * A build this controller was running before it restarted with its home (e.g. a safe restart to apply plugin
         * changes) is loaded, and a Pipeline then resumed, from that home: first take its lease over from the previous
         * incarnation, as an adopter would, so that no other controller adopts it too. If another controller holds it
         * already (it adopted the build meanwhile), the local copy is not loaded: that controller's is the build.
         */
        @Override
        public boolean allowLoad(@NonNull Job<?, ?> job, int buildNumber) {
            if (PersistenceStores.isApplyingRemoteChange() || !BuildRecords.isShared(job)) {
                return true;
            }
            try {
                return reclaim(job, buildNumber);
            } catch (NotYet e) {
                LOGGER.info(() -> "Not resuming a build after a restart yet: " + e.getMessage());
                reclaimLater(job, buildNumber);
                return false;
            } catch (IOException | RuntimeException e) {
                // not loaded (nor resumed) without its lease: another controller may run it; looked at again later
                LOGGER.log(
                        Level.WARNING,
                        "Could not reclaim " + job.getFullName() + " #" + buildNumber
                                + " after a restart; trying again later",
                        e);
                reclaimLater(job, buildNumber);
                return false;
            }
        }

        /**
         * Tries to load a build not loaded because it could not be reclaimed yet, so that a Pipeline is resumed without
         * waiting for someone to open it. Only that build: the others of the job, perhaps running, keep their instances.
         */
        private static void reclaimLater(Job<?, ?> job, int number) {
            Timer.get()
                    .schedule(
                            () -> {
                                if (job instanceof LazyBuildMixIn.LazyLoadingJob<?, ?> lazy) {
                                    try (ACLContext ctx = ACL.as2(ACL.SYSTEM2)) {
                                        lazy.getLazyBuildMixIn()
                                                ._getRuns()
                                                .recognizeNumber(number); // declined when listed: not indexed
                                        job.getBuildByNumber(number); // a load failing again schedules another
                                    }
                                }
                            },
                            RECLAIM_RETRY_SECONDS,
                            TimeUnit.SECONDS);
        }

        /** How long a build not reclaimed after a restart waits before it is tried again. */
        static /* non-final for tests */ long RECLAIM_RETRY_SECONDS = 30;

        /**
         * Records the result in the placeholder as soon as a build completes, before its record is published: should
         * this controller die in between, the build is then closed with that result instead of resumed elsewhere.
         */
        @Override
        public void onCompleted(Run<?, ?> run, @NonNull TaskListener listener) {
            Result result = run.getResult();
            if (result == null || PersistenceStores.isApplyingRemoteChange() || owned(run) == null) {
                return;
            }
            try {
                update(run, Map.of(RESULT, result.toString()));
            } catch (ConflictException e) {
                LOGGER.fine(() -> run + " completed after another controller took it over");
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.WARNING, "Could not record the result of " + run + " in its placeholder", e);
            }
        }

        @Override
        public void onStarted(Run<?, ?> run, TaskListener listener) {
            if (PersistenceStores.isApplyingRemoteChange()
                    || !BuildRecords.isShared(run.getParent())
                    || owned(run) != null) {
                return; // not shared, or adopted or reclaimed here (owned already)
            }
            try {
                started(run);
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.WARNING, "Failed to record " + run + " as running; will retry", e);
                unregistered.add(run);
            }
        }
    }

    @Extension
    public static final class StaleCleanup extends AsyncPeriodicWork {

        public StaleCleanup() {
            super("Stale running build placeholders");
        }

        @Override
        public long getRecurrencePeriod() {
            return TimeUnit.SECONDS.toMillis(CLEANUP_PERIOD);
        }

        @Override
        protected void execute(TaskListener listener) throws IOException {
            if (ClusterStore.get() != null) {
                registerPending();
                ExclusiveLeases.releasePending();
                checkOwnership();
                removeStale();
            }
        }

        @Override
        protected Level getNormalLoggingLevel() {
            return Level.FINE;
        }
    }

    /** The number of the newest build of a job running on another controller, if newer than this controller's newest. */
    public static int newerElsewhere(@NonNull Job<?, ?> job) {
        List<Placeholder> remote = elsewhereCached(job);
        Run<?, ?> local = job.getLastBuild();
        return remote != null && !remote.isEmpty() && (local == null || remote.get(0).number > local.getNumber())
                ? remote.get(0).number
                : 0;
    }
}
