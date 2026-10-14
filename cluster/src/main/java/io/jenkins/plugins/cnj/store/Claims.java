package io.jenkins.plugins.cnj.store;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Work done by one controller only: the first to claim a tick of an item's trigger runs it. */
public interface Claims {
    /** Claims {@code (item, trigger, tick)}; true for exactly one controller. */
    boolean claim(@NonNull String item, @NonNull String trigger, long tick) throws IOException;

    /** The name of the controller which last claimed a tick of {@code (item, trigger)}, or null. */
    @CheckForNull
    String lastClaimant(@NonNull String item, @NonNull String trigger) throws IOException;
}
