package io.jenkins.plugins.cluster.spi;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;

/**
 * Which controller holds the outbound connection of a static agent (such as an SSH agent), so that exactly one of
 * them launches it ({@link ClusterStore#launchLeases}; DESIGN-17 §5). Keyed by {@link Keys#launchLease}
 * ({@code _nodes/<name>/launch}).
 *
 * <p>A lease is not renewed: it lapses when its holder is no longer {@linkplain Membership#aliveMembers alive}.
 * The plugin's {@code ComputerListener.preLaunch} acquires it, and refuses to launch while another live member holds
 * it; it is released when the agent goes offline here and when the node is deleted.
 */
public interface LaunchLeases {

    /**
     * Acquires the launch lease of a node for this origin: creates it if absent, keeps it if this origin holds it, and
     * takes it over if its holder is not alive.
     *
     * @return the epoch now held, or 0 if another live origin holds it
     */
    long acquire(@NonNull String node) throws IOException;

    /** Who holds the launch lease of a node, alive or not, or null if nobody does. */
    @CheckForNull
    Holder holder(@NonNull String node) throws IOException;

    /** Gives up the launch lease of a node, if held by this origin with this epoch. */
    void release(@NonNull String node, long epoch) throws IOException;

    /**
     * The holder of a launch lease.
     *
     * @param origin the holder's {@linkplain jenkins.persistence.PersistenceStore#getOrigin origin}
     * @param epoch increases with each acquisition by another origin
     */
    record Holder(@NonNull String origin, long epoch) {}
}
