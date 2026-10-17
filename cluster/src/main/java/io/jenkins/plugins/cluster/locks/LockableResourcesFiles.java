package io.jenkins.plugins.cluster.locks;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import java.util.Collection;
import java.util.List;
import jenkins.persistence.LocalFiles;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * The state of lockable-resources (who holds what, who waits) belongs to each controller: shared, one controller's
 * save would replace another's view. What is shared goes through {@link ClusterLocks} instead.
 */
@Extension
@Restricted(NoExternalUse.class)
public final class LockableResourcesFiles implements LocalFiles {
    @NonNull
    @Override
    public Collection<String> patterns() {
        return List.of("org.jenkins.plugins.lockableresources.LockableResourcesManager.xml");
    }
}
