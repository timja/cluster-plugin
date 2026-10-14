package io.jenkins.plugins.cluster.spi;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Cluster-wide counters with caps (e.g. how many Kubernetes agents each cloud and template has). */
public interface Counters {
    /** Adds {@code delta} to every counter of {@code caps} if none would exceed its cap; all or nothing. */
    boolean addWithinCaps(@NonNull Map<String, Integer> caps, int delta) throws IOException;

    void subtractFromCounters(@NonNull Set<String> scopes, int delta) throws IOException;
}
