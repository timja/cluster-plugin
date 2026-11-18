package io.jenkins.plugins.cluster.spi;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Item;
import java.util.Objects;

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
 * <tr><td>milestones (pipeline-milestone-step)</td><td>{@code _items/<uuid>/milestones}</td></tr>
 * <tr><td>folder computation lease</td><td>{@code _items/<uuid>/computation}</td></tr>
 * <tr><td>item files</td><td>{@code _items/<uuid>/files/<rel>}; users {@code _users/<id>/files/<rel>}</td></tr>
 * <tr><td>blobs</td><td>{@code items/<uuid>/builds/<N>/files.tgz}, {@code items/<uuid>/builds/<N>/log},
 *     {@code items/<uuid>/files/<rel>}; users {@code users/<id>/files/<rel>}</td></tr>
 * <tr><td>locks</td><td>{@code _locks/<resource>}, {@code _locks-meta/<name>}</td></tr>
 * <tr><td>launch lease</td><td>{@code _nodes/<name>/launch}</td></tr>
 * <tr><td>signed-out login, deleted user</td><td>{@code _logins/revoked/<login id>},
 *     {@code _logins/deleted-users/<user>}</td></tr>
 * <tr><td>queue reservation</td><td>{@code _queue/reserved/<id>}</td></tr>
 * </table>
 *
 * <p>Code must build every key through this class.
 */
public final class Keys {

    private Keys() {}

    /** First segment of item entries. */
    public static final String ITEMS = "_items";

    /** First segment of item blobs. */
    public static final String ITEM_BLOBS = "items";

    /** The prefix of the store entries kept about an item. */
    @NonNull
    public static String item(@NonNull Item item) {
        return itemByUuid(Objects.requireNonNull(item.getItemUuid(), item.getFullName()));
    }

    /** The prefix of the store entries kept about the item with this identity. */
    @NonNull
    public static String itemByUuid(@NonNull String uuid) {
        return ITEMS + "/" + uuid;
    }

    /** The prefix of the blobs kept about an item. */
    @NonNull
    public static String blobs(@NonNull Item item) {
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

    /** A job's milestones (pipeline-milestone-step). */
    @NonNull
    public static String milestones(@NonNull String itemPrefix) {
        return itemPrefix + "/milestones";
    }

    /** The key of the lease of a folder's computation (branch indexing, an organization scan). */
    @NonNull
    public static String computation(@NonNull String itemPrefix) {
        return itemPrefix + "/computation";
    }

    /** A plugin file in an item's directory, at {@code rel} relative to it. */
    @NonNull
    public static String itemFile(@NonNull String itemPrefix, @NonNull String rel) {
        return itemPrefix + "/files/" + rel;
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
    public static String userFileBlob(@NonNull String userId, @NonNull String rel) {
        return "users/" + userId + "/files/" + rel;
    }

    @NonNull
    public static String itemFileBlob(@NonNull String blobPrefix, @NonNull String rel) {
        return blobPrefix + "/files/" + rel;
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

    /** A login signed out of (see {@code jenkins.security.LoginRevocation}), by its id. */
    @NonNull
    public static String revokedLogin(@NonNull String loginId) {
        return "_logins/revoked/" + loginId;
    }

    /** A user deleted recently, by the key of its id (encoded as a key segment): its sessions end everywhere. */
    @NonNull
    public static String deletedUser(@NonNull String user) {
        return "_logins/deleted-users/" + user;
    }

    /** A controller's reservation of a queue item it is starting an agent for. */
    @NonNull
    public static String queueReservation(long id) {
        return "_queue/reserved/" + id;
    }

    /**
     * Splits a key below an item prefix, as for dispatching change events.
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

    /**
     * Whether one segment of a key can name a file in the directory of the key's parent. Keys come from other
     * controllers, or from anything else writing to the store: a segment such as {@code ..} must never become a path.
     */
    public static boolean isSafeName(@CheckForNull String name) {
        return name != null
                && !name.isEmpty()
                && !name.equals(".")
                && !name.equals("..")
                && name.indexOf('/') < 0
                && name.indexOf('\\') < 0
                && name.indexOf('\0') < 0
                && name.indexOf(':') < 0;
    }

    /** Whether every segment of a key {@linkplain #isSafeName can name a file}. */
    public static boolean isSafeKey(@NonNull String key) {
        for (String segment : key.split("/", -1)) {
            if (!isSafeName(segment)) {
                return false;
            }
        }
        return true;
    }
}
