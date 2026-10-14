package io.jenkins.plugins.cnj.store;

import hudson.Extension;
import hudson.model.AdministrativeMonitor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import jenkins.persistence.PersistenceStores;

/**
 * Tells administrators when a controller overwrote a change made elsewhere (Target 15): two controllers saved the same
 * setting at once, and the lenient policy kept the later save. Counts are per controller since it started, from the
 * members' reports. With {@code -Djenkins.persistence.PersistenceStores.conflicts=strict-ui}, such saves from the UI are
 * refused instead (the user reloads); background saves still merge.
 */
@Extension
public final class ConflictMonitor extends AdministrativeMonitor {

    /** A controller's counts, for the monitor's message. */
    public record Counts(String controller, long merged, long overwritten, long refused) {}

    @Override
    public String getDisplayName() {
        return "Concurrent edits across controllers";
    }

    @Override
    public boolean isActivated() {
        return getCounts().stream().anyMatch(c -> c.overwritten() > 0);
    }

    public List<Counts> getCounts() {
        List<Counts> result = new ArrayList<>();
        if (PersistenceStores.get() instanceof ClusterStore store) {
            try {
                for (ClusterStore.Member m : store.members()) {
                    if (m.alive()) {
                        MemberStatus.Info i = MemberStatus.Info.parse(m.info());
                        result.add(new Counts(m.name(), i.merged(), i.overwritten(), i.refused()));
                    }
                }
            } catch (IOException | RuntimeException e) {
                // nothing to show
            }
        }
        return result;
    }

    public String getPolicy() {
        return System.getProperty(PersistenceStores.class.getName() + ".conflicts", "warn");
    }
}
