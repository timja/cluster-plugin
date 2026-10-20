package io.jenkins.plugins.cluster.builds;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.Util;
import hudson.model.Job;
import hudson.model.Run;
import io.jenkins.plugins.cluster.PerItemCache;
import io.jenkins.plugins.cluster.spi.ClusterStore;
import io.jenkins.plugins.cluster.spi.Keys;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.PeepholePermalink;
import jenkins.model.lazy.LazyBuildMixIn;
import jenkins.persistence.ConflictException;
import jenkins.persistence.PersistenceStore;
import jenkins.util.Timer;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * The permalinks cache of jobs whose builds are shared ({@code lastFailedBuild 123}, as in the {@code permalinks} file of
 * a build directory), kept in the store at {@link Keys#permalinks}.
 *
 * <p>With a cache per controller, a controller with a fresh home knew no permalink, and resolving one (a job page shows
 * several) went back through every build until it found a match, loading each. Now the controller completing a build
 * updates the shared cache (as {@link PeepholePermalink}'s listener does on one controller), and the others read it.
 * Jobs whose builds are not shared use the next cache, as without this one.
 *
 * <p>A target is only moved back (to an older build, or to none) if the build it named is gone from the store: a
 * controller which has not seen a newer build yet would otherwise replace the right target with an older one.
 */
@Restricted(NoExternalUse.class)
@Extension(ordinal = 100)
public final class StoredPermalinks implements PeepholePermalink.Cache {

    private static final Logger LOGGER = Logger.getLogger(StoredPermalinks.class.getName());

    /** Item prefix → the stored cache, as last read or written here. */
    private static final PerItemCache<Cached> caches = PerItemCache.create();

    /** Of a cache changed elsewhere: newer than anything read before, so that it is read again. */
    private static final long CHANGED = Long.MAX_VALUE;

    /**
     * A stored cache and the version of it, or (without a map) a mark that it changed elsewhere, so that it is read again.
     * Of two caches read or written here at once, the newer is kept, whichever is installed last.
     */
    private record Cached(long version, @CheckForNull Map<String, Known> map) {}

    private static void install(String prefix, @CheckForNull PersistenceStore.Entry e, Map<String, Known> map) {
        install(prefix, e == null ? PersistenceStore.ABSENT : e.version(), map);
    }

    private static void install(String prefix, long version, @CheckForNull Map<String, Known> map) {
        caches.merge(prefix, new Cached(version, map), (old, now) -> now.version() >= old.version() ? now : old);
    }

    @NonNull
    @Override
    public PermalinkTarget get(@NonNull Job<?, ?> job, @NonNull String id) {
        String prefix = BuildRecords.prefix(job);
        if (prefix == null) {
            return next().get(job, id);
        }
        Known known = cacheOf(prefix).get(id);
        return known != null ? known : UNKNOWN;
    }

    @Override
    public void put(@NonNull Job<?, ?> job, @NonNull String id, @NonNull Known target) {
        put(job, id, target, 0);
    }

    private void put(@NonNull Job<?, ?> job, @NonNull String id, @NonNull Known target, int retry) {
        String prefix = BuildRecords.prefix(job);
        if (prefix == null) {
            next().put(job, id, target);
            return;
        }
        ClusterStore store = ClusterStore.get();
        String key = Keys.permalinks(prefix);
        try {
            for (int attempt = 0; attempt < 5; attempt++) {
                PersistenceStore.Entry e = store.read(key);
                Map<String, Known> stored = parse(e);
                Known current = stored.get(id);
                if (target.equals(current)) {
                    install(prefix, e, stored);
                    return;
                }
                if (current instanceof Some(int n)
                        && (target instanceof None || target instanceof Some(int m) && m < n)
                        && exists(store, prefix, n)
                        && !beingDeleted(job, n)) {
                    // this controller has not seen #n yet (or a deletion moved the target back without us): keep it
                    LOGGER.fine(() -> "Keeping " + id + " of " + job + " at #" + n + " (not " + target + ")");
                    install(prefix, e, stored);
                    return;
                }
                stored.put(id, target);
                if (BuildRecords.gone(job)) {
                    // deleted since it was resolved: writing would bring back an entry of what was removed
                    caches.remove(prefix);
                    return;
                }
                try {
                    install(
                            prefix,
                            store.write(key, format(stored), e == null ? PersistenceStore.ABSENT : e.version()),
                            stored);
                    return;
                } catch (ConflictException x) {
                    // another controller updated it meanwhile: merge again
                }
            }
        } catch (IOException | RuntimeException x) {
            LOGGER.log(Level.FINE, "Could not store " + id + " of " + job + "; kept here meanwhile", x);
        }
        if (BuildRecords.gone(job)) {
            caches.remove(prefix);
            return;
        }
        cacheOf(prefix).put(id, target);
        // other controllers take the shared value for granted: try again, or they would keep the old target until the
        // next build; a newer target stored since is kept (above)
        if (retry < MAX_RETRIES) {
            Timer.get().schedule(() -> put(job, id, target, retry + 1), 1L << Math.min(retry + 1, 6), TimeUnit.SECONDS);
        } else {
            LOGGER.warning(() -> "Could not store " + id + " of " + job
                    + "; other controllers may show an older build until the next");
        }
    }

    private static final int MAX_RETRIES = 10;

    /**
     * Whether a build is stored: its record, or until that is published its running placeholder. The controller
     * completing a build sets permalinks to it before it publishes the record (which takes a while: the log is closed,
     * the snapshot uploaded), and the placeholder goes only once the record is there.
     */
    private static boolean exists(@NonNull PersistenceStore store, @NonNull String prefix, int n) throws IOException {
        return store.read(Keys.buildRecord(prefix, n)) != null || store.read(Keys.placeholder(prefix, n)) != null;
    }

    /**
     * Whether a build is being deleted here: its record is still stored while the deletion's listeners run, of which this
     * one (moving permalinks back) may run before the one removing the record.
     */
    private static boolean beingDeleted(@NonNull Job<?, ?> job, int n) {
        Run<?, ?> run = job instanceof LazyBuildMixIn.LazyLoadingJob<?, ?> lazy
                ? lazy.getLazyBuildMixIn()._getRuns().getLoadedBuilds().get(n) // not loading it to ask
                : job.getBuildByNumber(n);
        return run != null && run.isPendingDelete();
    }

    /** The cache of a job changed elsewhere: read it again when next needed. */
    static void changedElsewhere(@NonNull String prefix) {
        install(prefix, CHANGED, null);
    }

    /** {@link #changedElsewhere(String)} by the job. */
    static void changedElsewhere(@NonNull Job<?, ?> job) {
        String prefix = BuildRecords.prefix(job);
        if (prefix != null) {
            changedElsewhere(prefix);
        }
    }

    /**
     * Updates the permalinks a completed build matches, as {@link PeepholePermalink}'s listener does when a build
     * completes here; for a build whose record this controller published for another one, gone before publishing it.
     */
    static void completed(@NonNull Run<?, ?> run) {
        Job<?, ?> job = run.getParent();
        PeepholePermalink.Cache cache = ExtensionList.lookupFirst(PeepholePermalink.Cache.class);
        for (PeepholePermalink pp : Util.filter(job.getPermalinks(), PeepholePermalink.class)) {
            if (pp.apply(run)) {
                Run<?, ?> current = pp.resolve(job);
                if (current == null || current.getNumber() < run.getNumber()) {
                    LOGGER.fine(() -> "Updating " + pp.getId() + " permalink to completed " + run);
                    cache.put(job, pp.getId(), new Some(run.getNumber()));
                }
            }
        }
    }

    private static Map<String, Known> cacheOf(String prefix) {
        Map<String, Known> m = caches.compute(prefix, (k, c) -> {
                    if (c != null && c.map() != null) {
                        return c;
                    }
                    ClusterStore store = ClusterStore.get();
                    if (store == null) {
                        return c != null ? c : new Cached(CHANGED, null); // read again next time
                    }
                    try {
                        PersistenceStore.Entry e = store.read(Keys.permalinks(k));
                        return new Cached(e == null ? PersistenceStore.ABSENT : e.version(), parse(e));
                    } catch (IOException | RuntimeException x) {
                        LOGGER.log(Level.FINE, "Could not read the permalinks of " + k, x);
                        // read again next time (not cached as empty)
                        return c != null ? c : new Cached(CHANGED, null);
                    }
                })
                .map();
        return m != null ? m : new ConcurrentHashMap<>();
    }

    static Map<String, Known> parse(@CheckForNull PersistenceStore.Entry e) {
        Map<String, Known> cache = new ConcurrentHashMap<>();
        if (e == null) {
            return cache;
        }
        for (String line : new String(e.content(), StandardCharsets.UTF_8).split("\n")) {
            int idx = line.indexOf(' ');
            if (idx <= 0) {
                continue;
            }
            try {
                int number = Integer.parseInt(line.substring(idx + 1).trim());
                cache.put(line.substring(0, idx), number == -1 ? NONE : new Some(number));
            } catch (NumberFormatException x) {
                LOGGER.fine(() -> "Ignoring " + line);
            }
        }
        return cache;
    }

    static byte[] format(Map<String, Known> cache) {
        StringBuilder sb = new StringBuilder();
        new TreeMap<>(cache)
                .forEach((id, k) -> sb.append(id)
                        .append(' ')
                        .append(k instanceof Some(int n) ? n : -1)
                        .append('\n'));
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private PeepholePermalink.Cache next() {
        for (PeepholePermalink.Cache c : ExtensionList.lookup(PeepholePermalink.Cache.class)) {
            if (c != this) {
                return c;
            }
        }
        throw new IllegalStateException("no other permalinks cache");
    }
}
