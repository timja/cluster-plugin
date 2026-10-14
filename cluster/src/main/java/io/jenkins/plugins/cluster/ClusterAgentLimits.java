package io.jenkins.plugins.cluster;

import io.jenkins.plugins.cluster.spi.ClusterStore;
import hudson.Extension;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.persistence.PersistenceStores;
import org.csanchez.jenkins.plugins.kubernetes.ClusterProvisioningLimits;

/**
 * Kubernetes container and instance caps for the whole cluster (Target 9): each controller's share is a counter in the
 * store, and an agent is admitted only if the shares of the live controllers stay within the caps. A dead controller's
 * share stops counting with its membership.
 */
@Extension(optional = true)
public final class ClusterAgentLimits implements ClusterProvisioningLimits {

    private static final Logger LOGGER = Logger.getLogger(ClusterAgentLimits.class.getName());

    static String cloudScope(String cloud) {
        return "k8s-cloud:" + cloud;
    }

    static String templateScope(String cloud, String template) {
        return "k8s-template:" + cloud + ":" + template;
    }

    @Override
    public boolean register(String cloudName, int containerCap, String templateId, int instanceCap, int numExecutors) {
        if (!(PersistenceStores.get() instanceof ClusterStore store)) {
            return true;
        }
        Map<String, Integer> caps = new LinkedHashMap<>();
        caps.put(cloudScope(cloudName), containerCap);
        caps.put(templateScope(cloudName, templateId), instanceCap);
        try {
            return store.addWithinCaps(caps, numExecutors);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Cannot count agents of " + cloudName + " against the cluster caps; not provisioning", e);
            return false;
        }
    }

    @Override
    public void unregister(String cloudName, String templateId, int numExecutors) {
        if (PersistenceStores.get() instanceof ClusterStore store) {
            try {
                store.subtractFromCounters(Set.of(cloudScope(cloudName), templateScope(cloudName, templateId)), numExecutors);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Cannot release agents of " + cloudName + " in the cluster caps", e);
            }
        }
    }
}
