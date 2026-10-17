package io.jenkins.plugins.cluster;

import hudson.Extension;
import hudson.model.AdministrativeMonitor;
import io.jenkins.plugins.cluster.spi.ClusterStore;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Tells administrators when saves on one controller were refused because the same setting was changed on another at
 * the same moment: such a save is never written over the other change; the stored version stands and the object is
 * reloaded from it, so the refused change is lost and must be made again. Changes to different settings are merged.
 * Counts are per controller since it started, from the members' reports.
 */
@Extension
public final class ConflictMonitor extends AdministrativeMonitor {

    /** A controller's counts, for the monitor's message. */
    public record Counts(String controller, long merged, long refused) {}

    @Override
    public String getDisplayName() {
        return "Concurrent edits across controllers";
    }

    @Override
    public boolean isActivated() {
        return getCounts().stream().anyMatch(c -> c.refused() > 0);
    }

    public List<Counts> getCounts() {
        List<Counts> result = new ArrayList<>();
        ClusterStore store = ClusterStore.get();
        if (store != null) {
            try {
                for (ClusterStore.Member m : store.members()) {
                    if (m.alive()) {
                        MemberStatus.Info i = MemberStatus.Info.parse(m.info());
                        result.add(new Counts(m.name(), i.merged(), i.refused()));
                    }
                }
            } catch (IOException | RuntimeException e) {
                // nothing to show
            }
        }
        return result;
    }
}
