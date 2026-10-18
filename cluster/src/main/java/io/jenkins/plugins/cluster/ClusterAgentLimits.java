package io.jenkins.plugins.cluster;

import hudson.Extension;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.Node;
import io.jenkins.plugins.cluster.spi.ClusterStore;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.persistence.PersistenceStores;
import org.csanchez.jenkins.plugins.kubernetes.ClusterProvisioningLimits;
import org.csanchez.jenkins.plugins.kubernetes.KubernetesSlave;
import org.csanchez.jenkins.plugins.kubernetes.PodTemplate;

/**
 * Kubernetes container and instance caps for the whole cluster (Target 9): each controller's share is a counter in the
 * store, and an agent is admitted only if the shares of the live controllers stay within the caps. A dead controller's
 * share stops counting with its membership. A controller restarting with its home keeps its agents (pods that are
 * still running): they count again, under its new membership, as it starts ({@link #countSurvivors}).
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
            LOGGER.log(
                    Level.WARNING,
                    "Cannot count agents of " + cloudName + " against the cluster caps; not provisioning",
                    e);
            return false;
        }
    }

    /**
     * Counts the Kubernetes agents this controller already has (kept in its home across a restart) in its share, beyond
     * the caps if need be: they run whether or not they fit, and each is released again when it goes. Without this a
     * restarted controller's share starts at 0 while its pods run, and the releases of those pods would then cancel
     * the counts of agents started since.
     */
    static void countSurvivors(ClusterStore store, Iterable<? extends Node> nodes) {
        for (Map.Entry<List<String>, Integer> e : survivors(nodes).entrySet()) {
            Map<String, Integer> uncapped = new LinkedHashMap<>();
            for (String scope : e.getKey()) {
                uncapped.put(scope, Integer.MAX_VALUE);
            }
            try {
                store.addWithinCaps(uncapped, e.getValue());
                LOGGER.info(() -> "Counted " + e.getValue()
                        + " executor(s) of Kubernetes agents kept from before this start in " + e.getKey());
            } catch (IOException x) {
                LOGGER.log(
                        Level.WARNING,
                        "Cannot count the Kubernetes agents kept from before this start in " + e.getKey()
                                + "; the cluster caps undercount them until they go",
                        x);
            }
        }
    }

    /** The executors of the Kubernetes agents among {@code nodes}, by the scopes they count in (cloud, template). */
    static Map<List<String>, Integer> survivors(Iterable<? extends Node> nodes) {
        Map<List<String>, Integer> result = new LinkedHashMap<>();
        for (Node node : nodes) {
            if (node instanceof KubernetesSlave agent && agent.getCloudName() != null) {
                PodTemplate template;
                try {
                    template = agent.getTemplateOrNull();
                } catch (RuntimeException e) {
                    template = null; // its cloud is gone
                }
                if (template == null) {
                    continue; // never released either (the plugin releases agents by their template)
                }
                result.merge(
                        List.of(
                                cloudScope(agent.getCloudName()),
                                templateScope(agent.getCloudName(), templateKey(template))),
                        agent.getNumExecutors(),
                        Integer::sum);
            }
        }
        return result;
    }

    /**
     * The key the plugin counts a template by: {@code ClusterProvisioningLimits.templateKey} (its name) where the plugin
     * has it, else its id, as older versions passed.
     */
    static String templateKey(PodTemplate template) {
        try {
            return (String) ClusterProvisioningLimits.class
                    .getMethod("templateKey", PodTemplate.class)
                    .invoke(null, template);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return template.getId();
        }
    }

    /** Counts the agents kept from before, once per start (a start is a new member), as soon as nodes and clouds are loaded. */
    public static final class Survivors {
        @Initializer(after = InitMilestone.SYSTEM_CONFIG_LOADED, before = InitMilestone.JOB_LOADED)
        public static void count() {
            if (PersistenceStores.get() instanceof ClusterStore store
                    && Jenkins.get().getPlugin("kubernetes") != null) {
                countSurvivors(store, Jenkins.get().getNodes());
            }
        }

        private Survivors() {}
    }

    @Override
    public void unregister(String cloudName, String templateId, int numExecutors) {
        if (PersistenceStores.get() instanceof ClusterStore store) {
            try {
                store.subtractFromCounters(
                        Set.of(cloudScope(cloudName), templateScope(cloudName, templateId)), numExecutors);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Cannot release agents of " + cloudName + " in the cluster caps", e);
            }
        }
    }
}
