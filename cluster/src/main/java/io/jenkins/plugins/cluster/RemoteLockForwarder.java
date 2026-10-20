package io.jenkins.plugins.cluster;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.plugins.cluster.spi.ClusterMember;
import io.jenkins.plugins.cluster.spi.ClusterStore;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Forwards a remote lock client's calls about its lock to the controller holding it.
 *
 * <p>lockable-resources' remote lock API keeps each lock (and each queued request) in the memory of the controller
 * that accepted it. Its client then polls, renews and releases it ({@code /lockable-resources/remote/v1/acquire/<id>},
 * {@code …/lease/<id>/heartbeat}, {@code …/release}) through whichever controller the load balancer picks. Remote lock
 * ids start with the name of the controller that made them ({@code <controller>~<uuid>}), and such a call reaching
 * another controller is forwarded there ({@link OwnerRoutingFilter}), as for bound objects: the id only routes the
 * request, with the client's own credentials, and authorizes nothing. A lock of a controller that is gone is answered
 * here: not found, as after a restart.
 */
@Restricted(NoExternalUse.class)
public final class RemoteLockForwarder {

    private RemoteLockForwarder() {}

    static final String SEPARATOR = "~";

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]+");

    private static final Pattern PATH =
            Pattern.compile("/lockable-resources/remote/v1/(?:acquire|lease)/([^/]+)(?:/.*)?");

    /** The id of a new remote lock of this controller: a UUID, after this controller's name if a URL can hold it. */
    @NonNull
    public static String newLockId() {
        String uuid = UUID.randomUUID().toString();
        return NAME.matcher(ClusterMember.NAME).matches() ? ClusterMember.NAME + SEPARATOR + uuid : uuid;
    }

    /** The controller holding the lock a request is about, or null if none is named or it is this one. */
    @CheckForNull
    static String ownerOf(@NonNull String path, @NonNull String ours) {
        Matcher m = PATH.matcher(path);
        if (!m.matches()) {
            return null;
        }
        String id = m.group(1);
        int sep = id.lastIndexOf(SEPARATOR);
        if (sep <= 0) {
            return null;
        }
        String owner = id.substring(0, sep);
        return NAME.matcher(owner).matches() && !owner.equals(ours) ? owner : null;
    }

    /** A remote lock of another controller: to that controller (502 if it cannot be reached: the lock is nowhere else). */
    @CheckForNull
    static OwnerRoutingFilter.Route route(HttpServletRequest req, String path, ClusterStore store) throws IOException {
        String owner = ownerOf(path, ClusterMember.NAME);
        String url = owner == null ? null : store.urlOf(owner);
        return url == null ? null : new OwnerRoutingFilter.Forward(owner, url, true, null);
    }
}
