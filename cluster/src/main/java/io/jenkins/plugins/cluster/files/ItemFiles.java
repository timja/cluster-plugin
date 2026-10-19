package io.jenkins.plugins.cluster.files;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.XmlFile;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.AbstractItem;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Item;
import hudson.model.Saveable;
import hudson.model.TaskListener;
import hudson.model.User;
import hudson.model.listeners.ItemListener;
import hudson.model.listeners.SaveableListener;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.cluster.ClusterRouting;
import io.jenkins.plugins.cluster.PerItemCache;
import io.jenkins.plugins.cluster.spi.ClusterBlobStore;
import io.jenkins.plugins.cluster.spi.ClusterStore;
import io.jenkins.plugins.cluster.spi.Keys;
import io.jenkins.plugins.cluster.spi.Resync;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.ItemUuids;
import jenkins.model.Jenkins;
import jenkins.persistence.ConflictException;
import jenkins.persistence.LocalFiles;
import jenkins.persistence.PersistenceStore;
import jenkins.persistence.PersistenceStores;
import jenkins.persistence.RemoteChangeHandler;
import jenkins.util.SystemProperties;
import jenkins.util.Timer;
import net.sf.json.JSONObject;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Opt-in sharing of the other files plugins keep in item and user directories, for plugins that write state next to
 * {@code config.xml} with their own file APIs (such as the avatar plugin's {@code users/<id>/avatar.png}): with
 * {@code -Dio.jenkins.plugins.cluster.files.ItemFiles.enabled=true}, every minute each item's and user's directory is
 * compared with what was last shared; changed and removed files are shared (a manifest per directory mapping paths to
 * checksums, {@code _items/<uuid>/files/item-files.json} or {@code _users/<id>/files/item-files.json}, with the files in
 * blob storage), and other controllers write them into their own copy of the directory.
 *
 * <p>Left out: what the store holds already ({@code config.xml} and the like), builds, workspaces, sub-items, files
 * declared per controller ({@link LocalFiles}), and files larger than {@link #MAX_SIZE}. The last writer of a file wins;
 * plugins that cache a file's content in memory see a change made elsewhere only once they read it again (as when it is
 * changed on disk on a single controller). An item's shared files are kept by its identity: a rename or move keeps them,
 * a deletion removes them with the rest of what is stored about the item.
 */
@Restricted(NoExternalUse.class)
public final class ItemFiles {

    private static final Logger LOGGER = Logger.getLogger(ItemFiles.class.getName());

    static boolean enabled() {
        return SystemProperties.getBoolean(ItemFiles.class.getName() + ".enabled");
    }

    static final long MAX_SIZE = SystemProperties.getLong(ItemFiles.class.getName() + ".maxSize", 1024L * 1024);
    static final String MANIFEST = "item-files.json";
    /** What was last shared or taken of a directory, kept in it: the baseline of its next sync, after a restart too. */
    static final String KNOWN = ".item-files-known.json";
    /** Directories of an item's directory never shared this way. */
    static final Set<String> SKIPPED = Set.of(
            "builds",
            "workspace",
            "running",
            "jobs",
            "branches",
            "promotions",
            "configurations",
            "indexing",
            "lastStable",
            "lastSuccessful");

    /** The version of each manifest this controller last took or shared over what it had taken, by manifest key. */
    private static final PerItemCache<Long> taken = PerItemCache.create();

    /** What was last shared or taken here, per manifest key: path → checksum. */
    private static final PerItemCache<Map<String, String>> known = PerItemCache.create();

    /**
     * One take or publication of a directory at a time, by its manifest key: of two takes at once, the one of the older
     * manifest could otherwise finish last, leaving its files and baseline here while the store has newer ones.
     */
    private static final PerItemCache<Object> locks = PerItemCache.create();

    private ItemFiles() {}

    private static Object lock(String manifest) {
        return locks.computeIfAbsent(manifest, k -> new Object());
    }

    /** Where the files of a directory are shared: its manifest's key, and how a file's blob is named. */
    record Place(
            @NonNull File dir,
            @NonNull String manifest,
            @NonNull String blobs,
            @NonNull String dirKey) {
        String blob(String sha) {
            return blobs + "/" + sha;
        }
    }

    /** Where an item's files are shared, or null if they are not (no stores, no identity). */
    @CheckForNull
    static Place of(@NonNull Item item) {
        String dirKey = PersistenceStores.keyFor(item.getRootDir());
        if (ClusterStore.get() == null || item.getItemUuid() == null || dirKey == null) {
            return null;
        }
        String manifest = Keys.itemFile(Keys.item(item), MANIFEST);
        String blob = Keys.itemFileBlob(Keys.blobs(item), "x");
        return new Place(item.getRootDir(), manifest, blob.substring(0, blob.length() - 2), dirKey);
    }

    /** Whether a name can be a file name here: never {@code .}, {@code ..}, empty, nor holding a separator. */
    static boolean isSafeName(@CheckForNull String name) {
        return name != null
                && !name.isEmpty()
                && !name.equals(".")
                && !name.equals("..")
                && name.indexOf('/') < 0
                && name.indexOf('\\') < 0
                && name.indexOf(':') < 0
                && name.indexOf('\0') < 0;
    }

    /** Where a user's files are shared (by the name of the user's directory), or null. */
    @CheckForNull
    static Place ofUser(@NonNull File dir) {
        String dirKey = PersistenceStores.keyFor(dir);
        String id = dir.getName();
        if (ClusterStore.get() == null || dirKey == null || !isSafeName(id)) {
            return null;
        }
        String blob = Keys.userFileBlob(id, "x");
        return new Place(dir, Keys.userFile(id, MANIFEST), blob.substring(0, blob.length() - 2), dirKey);
    }

    /** The files to share of one directory: relative path → checksum. */
    static Map<String, String> scan(@NonNull Place place) throws IOException {
        Map<String, String> files = new TreeMap<>();
        Path root = place.dir().toPath();
        if (!Files.isDirectory(root)) {
            return files;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                if (d.equals(root)) {
                    return FileVisitResult.CONTINUE;
                }
                Path fileName = d.getFileName();
                if (fileName == null) {
                    return FileVisitResult.CONTINUE;
                }
                String name = fileName.toString();
                return root.equals(d.getParent()) && (SKIPPED.contains(name) || name.startsWith("workspace"))
                        ? FileVisitResult.SKIP_SUBTREE
                        : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) throws IOException {
                String rel = root.relativize(f).toString().replace(File.separatorChar, '/');
                if (attrs.isRegularFile() && attrs.size() <= MAX_SIZE && shared(rel, place)) {
                    files.put(rel, sha256(f));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

    /**
     * Whether a file of a directory, by its path relative to it, is one shared this way: what {@link #scan} shares, and
     * all that {@link #take} writes, whatever a manifest names.
     */
    static boolean shared(@NonNull String rel, @NonNull Place place) {
        String[] segments = rel.split("/");
        for (String segment : segments) {
            if (!isSafeName(segment)) {
                return false;
            }
        }
        if (segments.length > 1 && (SKIPPED.contains(segments[0]) || segments[0].startsWith("workspace"))) {
            return false;
        }
        String key = place.dirKey() + "/" + rel;
        return !rel.equals(KNOWN) && !rel.endsWith(".tmp") && !ClusterRouting.isShared(key) && !declaredLocal(key);
    }

    private static boolean declaredLocal(String key) {
        Path path = Path.of(key);
        for (LocalFiles local : ExtensionList.lookup(LocalFiles.class)) {
            for (String p : local.patterns()) {
                if (FileSystems.getDefault().getPathMatcher("glob:" + p).matches(path)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Shares what changed in a directory since it was last shared or taken here. */
    static void publish(@NonNull Place place) throws IOException {
        ClusterStore store = ClusterStore.get();
        ClusterBlobStore blobs = ClusterBlobStore.get();
        if (store == null || blobs == null) {
            return;
        }
        synchronized (lock(place.manifest())) {
            publish(place, store, blobs);
        }
    }

    private static void publish(Place place, ClusterStore store, ClusterBlobStore blobs) throws IOException {
        Map<String, String> local = scan(place);
        Map<String, String> last = last(place);
        if (local.equals(last)) {
            return;
        }
        for (Map.Entry<String, String> f : local.entrySet()) {
            if (!f.getValue().equals(last.get(f.getKey()))) {
                Path p = place.dir().toPath().resolve(f.getKey());
                // read once and checked against what was scanned: a file rewritten since is not stored under the old
                // checksum (it is shared with the next publication)
                byte[] content = Files.size(p) <= MAX_SIZE ? Files.readAllBytes(p) : null;
                if (content == null || !sha256(content).equals(f.getValue())) {
                    LOGGER.fine(() ->
                            f.getKey() + " of " + place.dir() + " changed while it was shared; sharing it next time");
                    return;
                }
                blobs.put(place.blob(f.getValue()), new ByteArrayInputStream(content), content.length);
            }
        }
        for (int attempt = 0; attempt < 10; attempt++) {
            PersistenceStore.Entry e = store.read(place.manifest());
            Map<String, String> shared = parse(e);
            for (Map.Entry<String, String> f : local.entrySet()) {
                if (!f.getValue().equals(last.get(f.getKey()))) {
                    shared.put(f.getKey(), f.getValue()); // changed here
                }
            }
            for (Map.Entry<String, String> gone : last.entrySet()) {
                if (!local.containsKey(gone.getKey()) && gone.getValue().equals(shared.get(gone.getKey()))) {
                    shared.remove(gone.getKey()); // removed here, and not changed elsewhere since
                }
            }
            try {
                long written = store.write(
                        place.manifest(), toBytes(shared), e == null ? PersistenceStore.ABSENT : e.version());
                if (e == null || Long.valueOf(e.version()).equals(taken.get(place.manifest()))) {
                    taken.put(place.manifest(), written); // else what changed elsewhere is taken by the catch-up
                }
                remember(place, local);
                LOGGER.fine(() -> "Shared the files of " + place.dir());
                return;
            } catch (ConflictException again) {
                // changed elsewhere at the same moment: apply ours on top of theirs
            }
        }
    }

    /** Writes the shared files of a directory into it here (after a change elsewhere, or at startup). */
    static void take(@NonNull Place place) throws IOException {
        ClusterStore store = ClusterStore.get();
        ClusterBlobStore blobs = ClusterBlobStore.get();
        if (store == null || blobs == null) {
            return;
        }
        synchronized (lock(place.manifest())) {
            take(place, store, blobs);
        }
    }

    private static void take(Place place, ClusterStore store, ClusterBlobStore blobs) throws IOException {
        PersistenceStore.Entry entry = store.read(place.manifest());
        Map<String, String> shared = parse(entry);
        Map<String, String> last = last(place);
        if (shared.isEmpty() && !known.contains(place.manifest())) {
            return; // nothing shared, nothing taken before: leave the directory as it is (or absent)
        }
        Path dir = place.dir().toPath();
        Map<String, String> local = scan(place);
        // what is in sync after this: what was taken, plus what was in sync before (not what changed here meanwhile)
        Map<String, String> synced = new TreeMap<>(last);
        for (Map.Entry<String, String> f : shared.entrySet()) {
            String here = local.get(f.getKey());
            // only where this copy did not change since it was last shared or taken: a change made here, not shared
            // yet,
            // wins (the next publication shares it)
            boolean unchangedHere = here == null ? !last.containsKey(f.getKey()) : here.equals(last.get(f.getKey()));
            if (!f.getValue().equals(here) && unchangedHere) {
                Path target = dir.resolve(f.getKey()).normalize();
                if (!target.startsWith(dir)
                        || !shared(f.getKey(), place)
                        || !f.getValue().matches("[0-9a-f]{64}")
                        || throughLink(dir, target)) {
                    // never outside the directory, nor a file that is not shared this way (its configuration, a
                    // build's),
                    // nor through a link
                    LOGGER.warning(
                            () -> "Not taking " + f.getKey() + " of " + place.dir() + ": not a file shared this way");
                    continue;
                }
                Path parent = target.getParent();
                if (parent == null) {
                    continue; // the root itself: not a file
                }
                Files.createDirectories(parent);
                Path tmp = Files.createTempFile(parent, ".item-file", ".tmp");
                try (InputStream in = blobs.get(place.blob(f.getValue()))) {
                    if (in == null) {
                        Files.deleteIfExists(tmp);
                        continue;
                    }
                    Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                }
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                synced.put(f.getKey(), f.getValue());
            } else if (f.getValue().equals(here)) {
                synced.put(f.getKey(), here);
            }
        }
        for (Map.Entry<String, String> l : last.entrySet()) {
            String path = l.getKey();
            if (!shared.containsKey(path) && l.getValue().equals(local.get(path))) {
                Path target = dir.resolve(path).normalize();
                if (target.startsWith(dir) && shared(path, place) && !throughLink(dir, target)) {
                    Files.deleteIfExists(target); // removed elsewhere, unchanged here
                }
                synced.remove(path);
            }
        }
        remember(place, synced);
        if (entry != null) {
            taken.put(place.manifest(), entry.version());
        }
    }

    /** What was last shared or taken of a directory: as known in memory, or as kept in it (as before a restart). */
    private static Map<String, String> last(Place place) {
        Map<String, String> files = known.get(place.manifest());
        if (files != null) {
            return files;
        }
        Path f = place.dir().toPath().resolve(KNOWN);
        if (Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) {
            try {
                files = parse(new PersistenceStore.Entry(Files.readAllBytes(f), PersistenceStore.ANY));
                known.put(place.manifest(), files);
                return files;
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.FINE, "Could not read " + f, e);
            }
        }
        return Map.of();
    }

    /** Records what was shared or taken of a directory, in memory and in it. */
    private static void remember(Place place, Map<String, String> files) {
        known.put(place.manifest(), files);
        File dir = place.dir();
        if (!dir.isDirectory()) {
            return;
        }
        try {
            Path tmp = Files.createTempFile(dir.toPath(), ".item-files-known", ".tmp");
            try {
                Files.write(tmp, toBytes(files));
                Files.move(
                        tmp,
                        dir.toPath().resolve(KNOWN),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Could not keep what was shared of " + dir, e);
        }
    }

    /** Whether a directory between a directory and a file in it is a link (or something else than a directory). */
    private static boolean throughLink(Path root, Path target) {
        for (Path d = target.getParent(); d != null && !d.equals(root); d = d.getParent()) {
            if (Files.isSymbolicLink(d)
                    || Files.exists(d, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(d, LinkOption.NOFOLLOW_LINKS)) {
                return true;
            }
        }
        return Files.isSymbolicLink(target);
    }

    private static Map<String, String> parse(@CheckForNull PersistenceStore.Entry e) {
        Map<String, String> files = new TreeMap<>();
        if (e != null) {
            JSONObject o = JSONObject.fromObject(new String(e.content(), StandardCharsets.UTF_8));
            for (Object k : o.keySet()) {
                files.put((String) k, o.getString((String) k));
            }
        }
        return files;
    }

    private static byte[] toBytes(Map<String, String> files) {
        JSONObject o = new JSONObject();
        o.putAll(files);
        return o.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(Path f) throws IOException {
        return sha256(Files.readAllBytes(f));
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    /** Takes the shared files of a directory, or, failing that, has the {@link Publisher} take them. */
    private static void takeOrLater(@CheckForNull Place place) {
        if (place == null) {
            return;
        }
        try {
            take(place);
            failed.remove(place);
        } catch (IOException | RuntimeException e) {
            LOGGER.log(
                    Level.WARNING,
                    "Could not take the shared files of " + place.dir() + "; trying again with the next publication",
                    e);
            failed.add(place);
        }
    }

    /** Directories whose shared files could not be taken (at startup, or when they changed elsewhere): taken again by the {@link Publisher}. */
    private static final Set<Place> failed = ConcurrentHashMap.newKeySet();

    /** Applies manifests other controllers wrote. */
    @Extension
    public static final class Changes implements RemoteChangeHandler {
        @Override
        public boolean handles(@NonNull String key) {
            return key.endsWith("/files/" + MANIFEST)
                    && (key.startsWith(Keys.ITEMS + "/") || key.startsWith("_users/"));
        }

        @Override
        public void changed(@NonNull String key, boolean deleted) {
            if (!enabled() || deleted) {
                return;
            }
            String[] segments = key.split("/");
            if (segments.length != 4) {
                return;
            }
            Timer.get().submit(() -> {
                try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) { // whoever may see it
                    Place place;
                    if (segments[0].equals(Keys.ITEMS)) {
                        Item item = ItemUuids.get(segments[1]);
                        place = item instanceof AbstractItem ? ItemFiles.of(item) : null;
                    } else {
                        place = ItemFiles.ofUser(new File(new File(Jenkins.get().getRootDir(), "users"), segments[1]));
                    }
                    takeOrLater(place);
                }
            });
        }
    }

    /**
     * Takes the shared files of every item and user whose manifest changed since this controller last took it, as if
     * the events had just arrived: an event may have been lost.
     */
    static void catchUp() throws IOException {
        ClusterStore store = ClusterStore.get();
        if (!enabled() || store == null) {
            return;
        }
        List<Place> places = new ArrayList<>();
        for (Item item : Jenkins.get().allItems()) {
            if (item instanceof AbstractItem) {
                places.add(ItemFiles.of(item));
            }
        }
        for (File dir : userDirs()) {
            places.add(ItemFiles.ofUser(dir));
        }
        for (Place place : places) {
            if (place == null) {
                continue;
            }
            PersistenceStore.Entry e = store.read(place.manifest());
            if (e != null && !Long.valueOf(e.version()).equals(taken.get(place.manifest()))) {
                takeOrLater(place);
            }
        }
    }

    /** Catches item and user files up with the store: events may have been lost. */
    @Extension
    public static final class CatchUp extends Resync {
        @Override
        public void catchUp() throws IOException {
            try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
                ItemFiles.catchUp();
            }
        }
    }

    /** A fresh home takes every item's and user's shared files. */
    @Initializer(after = InitMilestone.JOB_LOADED)
    public static void takeAll() {
        if (!enabled() || ClusterStore.get() == null) {
            return;
        }
        for (Item item : Jenkins.get().allItems()) {
            if (item instanceof AbstractItem) {
                takeOrLater(ItemFiles.of(item));
            }
        }
        for (File dir : userDirs()) {
            takeOrLater(ItemFiles.ofUser(dir));
        }
    }

    /** Removes the shared files of a user deleted here: a user made again with the same id takes none of them. */
    @Extension
    public static final class UserDeleted extends SaveableListener {
        @Override
        public void onDeleted(Saveable o, XmlFile file) {
            if (!(o instanceof User user)) {
                return;
            }
            File dir = user.getUserFolder();
            Place place = dir == null ? null : ItemFiles.ofUser(dir);
            if (place == null) {
                return;
            }
            PerItemCache.forgetAll(place.manifest()); // a user is not an item: forgotten here
            if (PersistenceStores.isApplyingRemoteChange()) {
                return; // deleted elsewhere: that controller removes them
            }
            Timer.get().submit(() -> {
                try {
                    ClusterStore store = ClusterStore.get();
                    if (store == null) {
                        return; // no cluster store (any more): nothing shared to remove
                    }
                    store.delete(place.manifest(), PersistenceStore.ANY);
                    ClusterBlobStore blobs = ClusterBlobStore.get();
                    if (blobs != null) {
                        blobs.delete(place.blobs());
                    }
                } catch (IOException | RuntimeException e) {
                    LOGGER.log(Level.WARNING, "Could not remove the shared files of " + user.getId(), e);
                }
            });
        }
    }

    /** The directories of the users known here. */
    static List<File> userDirs() {
        List<File> dirs = new ArrayList<>();
        for (User u : User.getAll()) {
            File dir = u.getUserFolder();
            if (dir != null) {
                dirs.add(dir);
            }
        }
        return dirs;
    }

    /**
     * An item created elsewhere takes its shared files once loaded here: a manifest applied before the item was (its
     * creation behind here) found no item to take them for. An item deleted is forgotten with what else is cached about
     * it ({@link PerItemCache}); its files are removed from the stores with the rest of what is kept about it.
     */
    @Extension
    public static final class Lifecycle extends ItemListener {
        @Override
        public void onCreated(Item item) {
            if (enabled() && item instanceof AbstractItem && PersistenceStores.isApplyingRemoteChange()) {
                Place place = ItemFiles.of(item);
                Timer.get().submit(() -> takeOrLater(place));
            }
        }
    }

    /** Shares what plugins changed in item and user directories, every minute. */
    @Extension
    public static final class Publisher extends AsyncPeriodicWork {
        public Publisher() {
            super("Share files in item and user directories");
        }

        @Override
        public long getRecurrencePeriod() {
            return TimeUnit.SECONDS.toMillis(
                    SystemProperties.getLong(ItemFiles.class.getName() + ".periodSeconds", 60L));
        }

        @Override
        protected void execute(TaskListener listener) {
            if (!enabled() || ClusterStore.get() == null) {
                return;
            }
            for (Place place : Set.copyOf(failed)) {
                try {
                    take(place);
                    failed.remove(place);
                } catch (IOException | RuntimeException e) {
                    LOGGER.log(Level.FINE, "Still cannot take the shared files of " + place.dir(), e);
                }
            }
            for (Item item : Jenkins.get().allItems()) {
                Place place = item instanceof AbstractItem ? ItemFiles.of(item) : null;
                if (place != null) {
                    try {
                        publish(place);
                    } catch (IOException | RuntimeException e) {
                        LOGGER.log(Level.WARNING, "Could not share the files of " + item.getFullName(), e);
                    }
                }
            }
            for (File dir : userDirs()) {
                Place place = ItemFiles.ofUser(dir);
                if (place != null) {
                    try {
                        publish(place);
                    } catch (IOException | RuntimeException e) {
                        LOGGER.log(Level.WARNING, "Could not share the files of " + dir, e);
                    }
                }
            }
        }

        @Override
        protected Level getNormalLoggingLevel() {
            return Level.FINE;
        }
    }
}
