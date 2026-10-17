package io.jenkins.plugins.cluster.spi;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;

/** Thrown by a fenced write ({@link BuildLeases#writeIfHeld}) when the lease is not held with the given epoch. */
public class LeaseLostException extends IOException {

    private final String leaseKey;
    private final long epoch;

    public LeaseLostException(@NonNull String leaseKey, long epoch) {
        super("Lease " + leaseKey + " is no longer held with epoch " + epoch);
        this.leaseKey = leaseKey;
        this.epoch = epoch;
    }

    @NonNull
    public String getLeaseKey() {
        return leaseKey;
    }

    public long getEpoch() {
        return epoch;
    }
}
