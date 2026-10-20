package io.jenkins.plugins.cluster;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Item;
import hudson.model.listeners.ItemListener;
import io.jenkins.plugins.cluster.spi.Keys;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import jenkins.persistence.PersistenceStores;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * What this controller keeps in memory about items, by store key: an item's own prefix ({@code _items/<uuid>}), or a key
 * below it or below the item's directory ({@code …/builds/3/build.xml}, {@code jobs/f/jobs/p/config.xml}).
 *
 * <p>Every such cache is forgotten for an item deleted here, or by another controller, and for the old directory of an
 * item moved or renamed ({@link Forgetting}), so that nothing is kept for items that no longer exist; and all of them
 * for a test acting as another controller ({@link #forgetAllForTests}).
 *
 * <p>A value loaded from the store is guarded by a generation per key ({@link #load}): a change told
 * ({@link #changed}) while it was loading, which the value may or may not include, has it loaded again rather than
 * cached.
 */
@Restricted(NoExternalUse.class)
public final class PerItemCache<V> {

    private static final List<PerItemCache<?>> ALL = new CopyOnWriteArrayList<>();

    private final Map<String, V> values = new ConcurrentHashMap<>();
    private final Map<String, Long> generations = new ConcurrentHashMap<>();

    private PerItemCache() {}

    /** A new cache, forgotten with the items it caches. */
    @NonNull
    public static <V> PerItemCache<V> create() {
        PerItemCache<V> cache = new PerItemCache<>();
        ALL.add(cache);
        return cache;
    }

    /** How a value is loaded. */
    @FunctionalInterface
    public interface Loader<V> {
        @NonNull
        V load(@NonNull String key) throws IOException;
    }

    @CheckForNull
    public V get(@NonNull String key) {
        return values.get(key);
    }

    public boolean contains(@NonNull String key) {
        return values.containsKey(key);
    }

    public void put(@NonNull String key, @NonNull V value) {
        values.put(key, value);
    }

    @CheckForNull
    public V remove(@NonNull String key) {
        return values.remove(key);
    }

    public boolean remove(@NonNull String key, @NonNull V value) {
        return values.remove(key, value);
    }

    public boolean replace(@NonNull String key, @NonNull V old, @NonNull V value) {
        return values.replace(key, old, value);
    }

    @CheckForNull
    public V compute(@NonNull String key, @NonNull BiFunction<String, V, V> remapping) {
        return values.compute(key, remapping);
    }

    @CheckForNull
    public V computeIfPresent(@NonNull String key, @NonNull BiFunction<String, V, V> remapping) {
        return values.computeIfPresent(key, remapping);
    }

    @NonNull
    public V computeIfAbsent(@NonNull String key, @NonNull Function<String, V> mapping) {
        return values.computeIfAbsent(key, mapping);
    }

    @CheckForNull
    public V merge(@NonNull String key, @NonNull V value, @NonNull BiFunction<V, V, V> remapping) {
        return values.merge(key, value, remapping);
    }

    @NonNull
    public List<String> keys() {
        return List.copyOf(values.keySet());
    }

    public void forEach(@NonNull BiConsumer<String, V> action) {
        values.forEach(action);
    }

    /** How many changes of a key were told so far (see {@link #changed}). */
    public long generation(@NonNull String key) {
        return generations.getOrDefault(key, 0L);
    }

    /** Tells of a change of a key elsewhere: a load of it in progress loads again. The value cached stays. */
    public void changed(@NonNull String key) {
        generations.merge(key, 1L, Long::sum);
    }

    /**
     * The value of a key, from the cache, else loaded and cached unless a change of the key was told while it loaded
     * (then loaded again, a few times; a key changing all the time is not cached, and loaded again next time).
     */
    @NonNull
    public V load(@NonNull String key, @NonNull Loader<V> loader) throws IOException {
        for (int attempt = 0; ; attempt++) {
            V cached = values.get(key);
            if (cached != null) {
                return cached;
            }
            long generation = generation(key);
            V loaded = loader.load(key);
            values.putIfAbsent(key, loaded);
            if (generation(key) == generation) {
                return values.getOrDefault(key, loaded); // a change from now on updates or drops the cached value
            }
            values.remove(key, loaded);
            if (attempt == 4) {
                return loaded;
            }
        }
    }

    /** Forgets what is cached about a key and everything below it ({@code <key>/…}). */
    public void forget(@NonNull String key) {
        String below = key + "/";
        values.keySet().removeIf(k -> k.equals(key) || k.startsWith(below));
        generations.keySet().removeIf(k -> k.equals(key) || k.startsWith(below));
    }

    /** Forgets what every cache keeps about a key and everything below it. */
    public static void forgetAll(@NonNull String key) {
        for (PerItemCache<?> cache : ALL) {
            cache.forget(key);
        }
    }

    /** For tests: as a controller which never cached anything (another JVM). */
    public static void forgetAllForTests() {
        for (PerItemCache<?> cache : ALL) {
            cache.values.clear();
            cache.generations.clear();
        }
    }

    /** Forgets the items deleted here or elsewhere, and the old directories of the items moved or renamed. */
    @Extension
    @Restricted(NoExternalUse.class)
    public static final class Forgetting extends ItemListener {
        @Override
        public void onDeleted(Item item) {
            if (item.getItemUuid() != null) {
                forgetAll(Keys.item(item));
            }
            String dir = PersistenceStores.keyFor(item.getRootDir());
            if (dir != null) {
                forgetAll(dir);
            }
        }

        @Override
        public void onLocationChanged(Item item, String oldFullName, String newFullName) {
            // by identity nothing changed; the old directory is gone (where folders keep their items: jobs/a/jobs/b)
            forgetAll("jobs/" + oldFullName.replace("/", "/jobs/"));
        }
    }
}
