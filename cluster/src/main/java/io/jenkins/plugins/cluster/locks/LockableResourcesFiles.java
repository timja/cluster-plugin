package io.jenkins.plugins.cluster.locks;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import java.util.Collection;
import java.util.List;
import jenkins.persistence.LocalFiles;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * lockable-resources' files (resources, the default store's state, the queue) belong to each controller: shared, one
 * controller's save would replace another's, and its queue would lose waiting builds. Who holds each resource is
 * shared through {@link ClusterResourceStates} instead.
 */
@Extension
@Restricted(NoExternalUse.class)
public final class LockableResourcesFiles implements LocalFiles {
    @NonNull
    @Override
    public Collection<String> patterns() {
        return List.of(
                "org.jenkins.plugins.lockableresources.LockableResourcesManager.xml",
                // the queue of waiting builds (jenkinsci/lockable-resources-plugin#1136): each controller's own
                "org.jenkins.plugins.lockableresources.LockableResourcesState.xml");
    }
}
