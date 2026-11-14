package io.jenkins.plugins.cluster.agents;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.ComputerSet;
import hudson.model.Label;
import hudson.model.Node;
import io.jenkins.plugins.cluster.spi.ClusterStore;
import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import jenkins.model.IComputer;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * The computers shown in the computer list, the executors widgets and label pages: this controller's, with each agent
 * connected to another controller of the cluster shown as {@link AgentElsewhere}, online with its executors as they
 * are there. Through core's {@link ComputerSet.ComputerSource} and {@link Label.LabelComputerSource}, which replace the
 * displayed list; scheduling never sees them.
 */
@Extension
@Restricted(NoExternalUse.class)
public final class AgentsElsewhereSource implements ComputerSet.ComputerSource, Label.LabelComputerSource {

    private static final Logger LOGGER = Logger.getLogger(AgentsElsewhereSource.class.getName());

    @Override
    public Collection<? extends IComputer> get() {
        return shown(Arrays.stream(Jenkins.get().getComputers()));
    }

    @NonNull
    @Override
    public Collection<? extends IComputer> get(@NonNull Label label) {
        return shown(label.getNodes().stream().map(Node::toComputer).filter(Objects::nonNull));
    }

    private static Collection<? extends IComputer> shown(Stream<Computer> computers) {
        if (ClusterStore.get() == null) {
            return computers.toList();
        }
        return computers.map(AgentsElsewhereSource::shown).toList();
    }

    /** The computer, or how its agent is doing where it is connected, if that is another controller. */
    @NonNull
    static IComputer shown(@NonNull Computer c) {
        AgentsElsewhere.Connection connection = connectedElsewhere(c);
        return connection == null ? c : new AgentElsewhere(c, connection);
    }

    /**
     * How the agent of a computer is doing elsewhere: only asked about an agent not connected here and not marked
     * temporarily offline (shown offline, as it is), and a failure counts as not knowing.
     */
    @CheckForNull
    static AgentsElsewhere.Connection connectedElsewhere(@NonNull Computer c) {
        Node node = c.getNode();
        if (node == null || node.getTemporaryOfflineCause() != null || c.isConnected()) {
            return null;
        }
        try {
            return AgentsElsewhere.get().connectedElsewhere(c);
        } catch (RuntimeException x) {
            LOGGER.log(Level.WARNING, "Could not tell whether " + c.getName() + " is connected elsewhere", x);
            return null;
        }
    }
}
