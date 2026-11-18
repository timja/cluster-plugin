package io.jenkins.plugins.cluster.builds;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ExtensionPoint;
import hudson.model.Job;
import hudson.model.Run;
import java.io.IOException;
import jenkins.model.CauseOfInterruption;

/**
 * Takes over a build which was running on a controller that is gone, so that it continues here.
 *
 * <p>{@link RunningBuilds} calls adopters with the build's lease already acquired by this controller.
 * Adoption happens in two steps, so that it can be abandoned if the previous owner turns out to have
 * moved on in the meantime: {@link #prepare} fetches what is needed without starting anything;
 * once this controller has been recorded as the owner, {@link Adoption#resume} continues the build.
 * If no adopter takes a build, it is closed (as aborted, or with the result its owner recorded).
 */
public interface BuildAdopter extends ExtensionPoint {

    /**
     * Prepares to adopt a build.
     *
     * @param placeholder the build's placeholder as last written by its owner, including any
     *     {@linkplain RunningBuilds#update properties} the owner recorded
     * @return an adoption to complete, or null if this adopter cannot resume this build
     */
    @CheckForNull
    Adoption prepare(@NonNull Job<?, ?> job, @NonNull RunningBuilds.Placeholder placeholder) throws IOException;

    /**
     * Stops this controller's copy of a build which another controller took over, without completing it here: nothing
     * more of it runs (no {@code finally} block or {@code post} section), and no listener is told that it completed, as
     * it carries on there. Once it has stopped, the adopter calls {@link RunningBuilds#letGo}.
     *
     * @param cause why it stops, for what it was doing
     * @return whether this adopter stops it; if none does, it is interrupted, and completes here as not built
     */
    default boolean stopWithoutCompleting(@NonNull Run<?, ?> run, @NonNull CauseOfInterruption cause) {
        return false;
    }

    /**
     * A prepared adoption.
     */
    interface Adoption {
        /**
         * Continues the build on this controller, which is now its owner with the given epoch.
         */
        void resume(long epoch) throws IOException;

        /**
         * Discards what {@link BuildAdopter#prepare} fetched, as the adoption did not go ahead.
         */
        void abandon();
    }
}
