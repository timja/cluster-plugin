package io.jenkins.plugins.cnj.store;

import hudson.Extension;
import hudson.model.Computer;
import hudson.model.TaskListener;
import hudson.slaves.ComputerListener;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.persistence.PersistenceStores;

/**
 * Publishes which controller holds each agent's channel (Target 7), so that requests for the agent reaching another
 * controller can be forwarded to the holder ({@link OwnerRoutingFilter}). Rows of a dead controller are ignored, as
 * the lookup joins live members.
 */
@Extension
public final class ChannelPublisher extends ComputerListener {

    private static final Logger LOGGER = Logger.getLogger(ChannelPublisher.class.getName());

    @Override
    public void onOnline(Computer c, TaskListener listener) {
        if (c != Jenkins.get().toComputer() && PersistenceStores.get() instanceof ClusterStore store) {
            try {
                store.channelConnected(c.getName());
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Could not publish the channel of " + c.getName(), e);
            }
        }
    }

    @Override
    public void onOffline(Computer c, hudson.slaves.OfflineCause cause) {
        if (c != Jenkins.get().toComputer() && PersistenceStores.get() instanceof ClusterStore store) {
            try {
                store.channelDisconnected(c.getName());
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Could not withdraw the channel of " + c.getName(), e);
            }
        }
    }
}
