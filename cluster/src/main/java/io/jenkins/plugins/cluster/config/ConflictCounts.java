package io.jenkins.plugins.cluster.config;

import java.util.concurrent.atomic.AtomicLong;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/** Saves that conflicted with a change made elsewhere since this controller started: merged, or refused. */
@Restricted(NoExternalUse.class)
public final class ConflictCounts {

    private static final AtomicLong merged = new AtomicLong();
    private static final AtomicLong refused = new AtomicLong();

    private ConflictCounts() {}

    public static long merged() {
        return merged.get();
    }

    public static long refused() {
        return refused.get();
    }

    static void merged(boolean wasMerged) {
        (wasMerged ? merged : refused).incrementAndGet();
    }
}
