package io.jenkins.plugins.cluster.builds;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.BuildBadgeAction;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * The badge of a {@link BuildElsewhere} in the build history: the controller running it, as text (its name is
 * configuration, never markup).
 */
@Restricted(NoExternalUse.class)
public final class RunningElsewhereBadge implements BuildBadgeAction {

    private final String location;

    RunningElsewhereBadge(@NonNull String location) {
        this.location = location;
    }

    /** The display name of the controller running the build. */
    @NonNull
    public String getLocation() {
        return location;
    }

    @Override
    public String getIconFileName() {
        return null;
    }

    @Override
    public String getDisplayName() {
        return null;
    }

    @Override
    public String getUrlName() {
        return null;
    }
}
