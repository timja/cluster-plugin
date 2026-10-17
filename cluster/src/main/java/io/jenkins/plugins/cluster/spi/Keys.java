package io.jenkins.plugins.cluster.spi;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Item;
import java.util.Objects;
import jenkins.persistence.PersistenceStores;

/**
 * The one place that knows where the cluster keeps data in the {@link ClusterStore} and the {@link ClusterBlobStore}
 * (DESIGN-17 §3.1). Everything kept about an item lives below its prefix, {@link #item(Item)}, which is keyed by the
 * item's {@linkplain Item#getItemUuid identity}: renames and moves do not change it, and nothing below it moves.
 * Entries use the reserved {@code _} prefix (core never routes such keys); blobs use their own namespace.
 *
 * <table>
 * <caption>Layout</caption>
 * <tr><th>Data</th><th>Key</th></tr>
 * <tr><td>build record</td><td>{@code _items/<uuid>/builds/<N>/build.xml}</td></tr>
 * <tr><td>permalinks</td><td>{@code _items/<uuid>/builds/permalinks}</td></tr>
 * <tr><td>next build number</td><td>{@code _items/<uuid>/nextBuildNumber}</td></tr>
 * <tr><td>running placeholder</td><td>{@code _items/<uuid>/running/<N>.xml}</td></tr>
 * <tr><td>stop request, delivery</td><td>{@code _items/<uuid>/running/<N>.stop}, {@code …/<N>.deliver-<id>}</td></tr>
 * <tr><td>build lease</td><td>{@code _items/<uuid>/running/<N>}</td></tr>
 * <tr><td>item files</td><td>{@code _items/<uuid>/files/<rel>}; users {@code _users/<id>/files/<rel>}</td></tr>
 * <tr><td>blobs</td><td>{@code items/<uuid>/builds/<N>/files.tgz}, {@code items/<uuid>/builds/<N>/log},
 *     {@code items/<uuid>/files/<rel>}</td></tr>
 * <tr><td>locks</td><td>{@code _locks/<resource>}, {@code _locks-meta/<name>}</td></tr>
 * <tr><td>launch lease</td><td>{@code _nodes/<name>/launch}</td></tr>
 * </table>
 *
 * <p>While the engines are ported from core (DESIGN-17 §8.3, risk 2), {@link #LAYOUT} may be {@link Layout#PATH}:
 * the item prefixes are then the item's {@code JENKINS_HOME}-relative directory, as before. Switching to
 * {@link Layout#UUID} is then one commit. Code must build every key through this class.
 */
public final class Keys {

    private Keys() {}

    /** How item prefixes are formed. */
    public enum Layout {
        /** {@code jobs/a/jobs/b} (entries) and {@code jobs/a/jobs/b} (blobs): only while porting. */
        PATH,
        /** {@code _items/<uuid>} (entries) and {@code items/<uuid>} (blobs). */
        UUID
    }

    /** The layout in use. */
    public static final Layout LAYOUT = Layout.UUID;

    /** First segment of item entries. */
    public static final String ITEMS = "_items";

    /** First segment of item blobs. */
    public static final String ITEM_BLOBS = "items";

    /** The prefix of the store entries kept about an item. */
    @NonNull
    public static String item(@NonNull Item item) {
        if (LAYOUT == Layout.PATH) {
            return Objects.requireNonNull(PersistenceStores.keyFor(item.getRootDir()), item.getFullName());
        }
        return itemByUuid(Objects.requireNonNull(item.getItemUuid(), item.getFullName()));
    }

    /** The prefix of the store entries kept about the item with this identity ({@link Layout#UUID}). */
    @NonNull
    public static String itemByUuid(@NonNull String uuid) {
        return ITEMS + "/" + uuid;
    }

    /** The prefix of the blobs kept about an item. */
    @NonNull
    public static String blobs(@NonNull Item item) {
        if (LAYOUT == Layout.PATH) {
            return item(item);
        }
        return ITEM_BLOBS + "/" + Objects.requireNonNull(item.getItemUuid(), item.getFullName());
    }

    // entries below an item prefix (the result of item(…))

    @NonNull
    public static String builds(@NonNull String itemPrefix) {
        return itemPrefix + "/builds";
    }

    @NonNull
    public static String buildRecord(@NonNull String itemPrefix, int number) {
        return builds(itemPrefix) + "/" + number + "/build.xml";
    }

    @NonNull
    public static String permalinks(@NonNull String itemPrefix) {
        return builds(itemPrefix) + "/permalinks";
    }

    @NonNull
    public static String nextBuildNumber(@NonNull String itemPrefix) {
        return itemPrefix + "/nextBuildNumber";
    }

    @NonNull
    public static String running(@NonNull String itemPrefix) {
        return itemPrefix + "/running";
    }

    @NonNull
    public static String placeholder(@NonNull String itemPrefix, int number) {
        return running(itemPrefix) + "/" + number + ".xml";
    }

    @NonNull
    public static String stopRequest(@NonNull String itemPrefix, int number) {
        return running(itemPrefix) + "/" + number + ".stop";
    }

    @NonNull
    public static String delivery(@NonNull String itemPrefix, int number, @NonNull String id) {
        return running(itemPrefix) + "/" + number + ".deliver-" + id;
    }

    /** The key of a build's {@linkplain BuildLeases lease}. */
    @NonNull
    public static String buildLease(@NonNull String itemPrefix, int number) {
        return running(itemPrefix) + "/" + number;
    }

    /** A plugin file in an item's directory, at {@code rel} relative to it. */
    @NonNull
    public static String itemFile(@NonNull String itemPrefix, @NonNull String rel) {
        return LAYOUT == Layout.PATH ? itemPrefix + "/" + rel : itemPrefix + "/files/" + rel;
    }

    @NonNull
    public static String userFile(@NonNull String userId, @NonNull String rel) {
        return "_users/" + userId + "/files/" + rel;
    }

    // blobs below a blob prefix (the result of blobs(…))

    @NonNull
    public static String snapshotBlob(@NonNull String blobPrefix, int number) {
        return blobPrefix + "/builds/" + number + "/files.tgz";
    }

    @NonNull
    public static String logBlob(@NonNull String blobPrefix, int number) {
        return blobPrefix + "/builds/" + number + "/log";
    }

    @NonNull
    public static String itemFileBlob(@NonNull String blobPrefix, @NonNull String rel) {
        return LAYOUT == Layout.PATH ? blobPrefix + "/" + rel : blobPrefix + "/files/" + rel;
    }

    // not per item

    @NonNull
    public static String lock(@NonNull String resource) {
        return "_locks/" + resource;
    }

    @NonNull
    public static String lockMeta(@NonNull String name) {
        return "_locks-meta/" + name;
    }

    @NonNull
    public static String launchLease(@NonNull String node) {
        return "_nodes/" + node + "/launch";
    }

    /**
     * Splits a key below an item prefix of the {@link Layout#UUID} layout, as for dispatching change events.
     *
     * @return the identity and the rest of the key (such as {@code builds/7/build.xml}), or null for other keys
     */
    @CheckForNull
    public static ItemKey parse(@NonNull String key) {
        if (!key.startsWith(ITEMS + "/")) {
            return null;
        }
        int start = ITEMS.length() + 1;
        int slash = key.indexOf('/', start);
        if (slash <= start || slash == key.length() - 1) {
            return null;
        }
        return new ItemKey(key.substring(start, slash), key.substring(slash + 1));
    }

    /** A key below an item prefix: the item's identity and the rest of the key. */
    public record ItemKey(@NonNull String uuid, @NonNull String rest) {}
}
