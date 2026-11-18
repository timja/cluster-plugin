package io.jenkins.plugins.cluster.builds;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Run;
import io.jenkins.plugins.cluster.builds.BuildRecords.Exclusion;
import java.util.Collection;
import java.util.List;

/** State a Pipeline needs only while it runs, which the cluster keeps elsewhere (save points), or never needs again. */
@Extension
public final class PipelineStateExclusion implements Exclusion {
    @NonNull
    @Override
    public Collection<String> excludes(@NonNull Run<?, ?> run) {
        return List.of("program.dat", "workflow/**", "stashes/**");
    }
}
