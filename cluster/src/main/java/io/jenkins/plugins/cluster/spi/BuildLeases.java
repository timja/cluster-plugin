package io.jenkins.plugins.cluster.spi;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;

/**
 * Exclusive ownership of running builds, so that exactly one controller works on a build at a time
 * ({@link ClusterStore#leases}).
 *
 * <p>A lease belongs to the {@linkplain jenkins.persistence.PersistenceStore#getOrigin origin} of the store instance
 * which acquired it and lapses when that origin is no longer {@linkplain Membership#aliveMembers alive}, as decided by
 * the store (heartbeats on the database clock), not by the clock of the holder.
 * Each acquisition returns a new, higher <em>epoch</em>, which holders use as a fencing token ({@link #writeIfHeld}).
 * Lease keys are {@link Keys#buildLease}: {@code _items/<uuid>/running/<N>}, which renames do not change.
 */
public interface BuildLeases {

    /**
     * Acquires the lease on a key for this origin, if it is free, lapsed, or already held by this origin.
     *
     * @return the epoch now held (unchanged if this origin already held it), or 0 if another live origin holds it
     */
    long acquire(@NonNull String leaseKey) throws IOException;

    /**
     * Whether this origin still holds the lease with this epoch.
     * Answers false, rather than throwing, once this controller cannot have been considered alive by the others for
     * long enough for the lease to lapse ({@link Membership#mayHaveLapsed}).
     */
    boolean holds(@NonNull String leaseKey, long epoch) throws IOException;

    /** Gives up a lease, if held with this epoch. */
    void release(@NonNull String leaseKey, long epoch) throws IOException;

    /**
     * Writes an entry only while this origin holds the lease with this epoch, checking and writing atomically (in one
     * transaction): the lease is the fence, so that a controller which lost a build (another took it over) cannot write
     * its record over the new owner's. There is deliberately no check-then-write default.
     *
     * @return the new version, as {@link jenkins.persistence.PersistenceStore#write}
     * @throws LeaseLostException if the lease is not (or no longer) held with this epoch
     * @throws jenkins.persistence.ConflictException if {@code expectedVersion} does not match
     */
    long writeIfHeld(@NonNull String leaseKey, long epoch, @NonNull String key, @NonNull byte[] content, long expectedVersion)
            throws IOException;
}
