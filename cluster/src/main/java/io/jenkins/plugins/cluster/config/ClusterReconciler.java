package io.jenkins.plugins.cluster.config;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.XmlFile;
import hudson.model.AbstractItem;
import hudson.model.Saveable;
import hudson.model.listeners.SaveableListener;
import io.jenkins.plugins.cluster.PerItemCache;
import io.jenkins.plugins.cluster.spi.ClusterStore;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.persistence.PersistenceStore;
import jenkins.persistence.PersistenceStores;
import jenkins.persistence.StoredConfigReconciler;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * The cluster's policy for configuration changed on two controllers at once.
 *
 * <p>A conflicting save is merged three ways ({@link XmlMerge}) on top of what was stored meanwhile; if the same part
 * changed on both sides, or the content is not mergeable XML, the save is refused (core then reloads the stored version):
 * never last writer wins.
 *
 * <p>An item changed elsewhere is reloaded field by field: the new version is read into a scratch instance, and only
 * the fields whose serialisation changed since the item was last in sync are copied over. A setter that assigned a
 * field here and has not saved yet keeps its change, and its save then merges with the store. If a field changed both
 * here and elsewhere, or the item cannot be reloaded field by field (no copy of what it was in sync with, implicit
 * collections, a save here since), core's default reload applies: the stored version wins.
 *
 * <p>After a save of the item here was refused (or merged with a change made elsewhere), the stored version wins
 * outright: every field whose serialisation differs from it is copied over, so that the refused change does not stay in
 * memory, to be saved by the next unrelated save.
 */
@Restricted(NoExternalUse.class)
@Extension
public final class ClusterReconciler implements StoredConfigReconciler {

    private static final Logger LOGGER = Logger.getLogger(ClusterReconciler.class.getName());

    @CheckForNull
    @Override
    public byte[] merge(@NonNull String key, @NonNull byte[] base, @NonNull byte[] mine, @NonNull byte[] theirs) {
        if (ClusterStore.get() == null || !key.endsWith(".xml")) {
            return null;
        }
        XmlMerge.Result result = XmlMerge.merge(base, mine, theirs);
        boolean merged = result != null && result.conflicts() == 0;
        ConflictCounts.merged(merged);
        if (merged) {
            LOGGER.info(() -> key + " was changed elsewhere meanwhile; merged both changes");
            return result.merged();
        }
        LOGGER.warning(() -> key
                + " was changed elsewhere meanwhile, in the same part as here; not saved, the stored version stands");
        return null;
    }

    /** What an item was last in sync with: its object, and its serialised form then. */
    private record Synced(WeakReference<Object> object, byte[] content) {}

    /** By the store key of the item's configuration file: forgotten with the item. */
    private static final PerItemCache<Synced> synced = PerItemCache.create();

    private static void synced(XmlFile file, Object o, byte[] content) {
        String key = keyOf(file);
        if (key != null) {
            synced.put(key, new Synced(new WeakReference<>(o), content));
        }
    }

    @CheckForNull
    private static byte[] syncedContent(XmlFile file, Object o) {
        String key = keyOf(file);
        Synced s = key == null ? null : synced.get(key);
        return s != null && s.object().get() == o ? s.content() : null;
    }

    /**
     * The store key of an item's configuration file. Not through {@link XmlFile#getFile}, which would make a local copy
     * of the stored file.
     */
    @CheckForNull
    private static String keyOf(XmlFile file) {
        return PersistenceStores.keyFor(new File(file.toString()));
    }

    /** Forgets what an item was last in sync with. */
    private static void unsynced(XmlFile file) {
        String key = keyOf(file);
        if (key != null) {
            synced.remove(key);
        }
    }

    /** For tests: whether what the item of a configuration file was last in sync with is kept. */
    static boolean isSynced(@NonNull String key) {
        return synced.contains(key);
    }

    /** Items saved here: in sync with what was saved. */
    @Extension
    public static final class Saves extends SaveableListener {
        @Override
        public void onChange(Saveable o, XmlFile file) {
            if (o instanceof AbstractItem
                    && ClusterStore.get() != null
                    && !PersistenceStores.isApplyingRemoteChange()) {
                synced(file, o, serialise(file, o));
            }
        }
    }

    /**
     * After a save of the object here was refused or merged ({@link Reason#AFTER_SAVE_HERE}), what the object holds
     * that is not stored is what that save tried to store: the stored version wins outright.
     */
    @Override
    public boolean reload(
            @NonNull XmlFile file, @NonNull Saveable o, @NonNull PersistenceStore.Entry entry, @NonNull Reason reason) {
        if (!(o instanceof AbstractItem item) || ClusterStore.get() == null) {
            return false;
        }
        return reload(file, item, entry, reason == Reason.AFTER_SAVE_HERE);
    }

    /** @param stored whether the stored version wins outright (after a save here), else only what changed elsewhere */
    boolean reload(XmlFile file, AbstractItem item, PersistenceStore.Entry entry, boolean stored) {
        byte[] snapshot = syncedContent(file, item);
        unsynced(file); // whatever happens below, the snapshot is no longer what it is in sync with
        if (snapshot == null && !stored) {
            return false;
        }
        try {
            // core calls this under the item's lock, and takes the entry's version as held before it lets go of it: a
            // save here never merges against what the item held before this reload
            Object scratch = file.getXStream().fromXML(new ByteArrayInputStream(entry.content()));
            if (scratch == null || scratch.getClass() != item.getClass()) {
                return false;
            }
            Map<String, String> theirs = XmlMerge.parts(serialise(file, scratch));
            Map<String, String> fromSnapshot = snapshot == null ? null : XmlMerge.parts(snapshot);
            if (theirs == null || fromSnapshot == null && !stored) {
                return false;
            }
            synchronized (item) {
                Map<String, String> live = XmlMerge.parts(serialise(file, item));
                if (live == null) {
                    return false;
                }
                // after a save here, what differs from the stored version is that save's: as if never made
                Map<String, String> before = stored ? live : fromSnapshot;
                Set<String> changed = new HashSet<>(before.keySet());
                changed.addAll(theirs.keySet());
                changed.removeIf(name -> Objects.equals(before.get(name), theirs.get(name)));
                Map<String, Field> fields = new LinkedHashMap<>();
                for (String name : changed) {
                    if (!Objects.equals(live.get(name), before.get(name))) {
                        // changed here too since the last sync: a genuine conflict; the stored version wins
                        LOGGER.info(() -> name + " of " + item.getFullName()
                                + " changed here and elsewhere; reloading it as stored");
                        return false;
                    }
                    Field f = field(file, item.getClass(), name);
                    if (f == null) {
                        return false; // e.g. an implicit collection: cannot tell which field it is
                    }
                    fields.put(name, f);
                }
                LOGGER.fine(() -> "Reloading " + item.getFullName() + " field by field from version " + entry.version()
                        + ": changed " + changed);
                for (Field f : fields.values()) {
                    f.set(item, f.get(scratch));
                }
                if (live.equals(before)) {
                    synced(file, item, entry.content()); // in sync with the stored version now
                } // else other fields changed here: their pending save merges with the store
            }
            // core runs the item's onLoad once it lets go of the item's lock: onLoad takes the job's build lock,
            // which a new build holds while it asks for the item's lock to be numbered
            return true;
        } catch (Exception | LinkageError e) {
            LOGGER.log(Level.FINE, "Cannot reload " + item.getFullName() + " field by field", e);
            return false;
        }
    }

    private static byte[] serialise(XmlFile file, Object o) {
        return file.getXStream().toXML(o).getBytes(StandardCharsets.UTF_8);
    }

    /** The serialised field an element stands for, or null if it is not one plain field. */
    @CheckForNull
    static Field field(XmlFile file, Class<?> type, String element) {
        var mapper = file.getXStream().getMapper();
        String name = mapper.realMember(type, element);
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                int m = f.getModifiers();
                if (Modifier.isStatic(m) || Modifier.isTransient(m) || !mapper.shouldSerializeMember(c, name)) {
                    return null;
                }
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                // a superclass then
            } catch (RuntimeException e) {
                return null; // inaccessible
            }
        }
        return null;
    }
}
