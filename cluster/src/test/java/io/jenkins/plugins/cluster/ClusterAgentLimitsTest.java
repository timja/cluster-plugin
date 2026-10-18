package io.jenkins.plugins.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import hudson.model.Node;
import io.jenkins.plugins.cluster.spi.ClusterStore;
import java.util.List;
import java.util.Map;
import org.csanchez.jenkins.plugins.kubernetes.KubernetesSlave;
import org.csanchez.jenkins.plugins.kubernetes.PodTemplate;
import org.junit.jupiter.api.Test;

class ClusterAgentLimitsTest {

    private static PodTemplate template(String name, String id) {
        PodTemplate t = mock(PodTemplate.class);
        when(t.getName()).thenReturn(name);
        when(t.getId()).thenReturn(id);
        return t;
    }

    private static KubernetesSlave agent(String cloud, PodTemplate template, int executors) {
        KubernetesSlave a = mock(KubernetesSlave.class);
        when(a.getCloudName()).thenReturn(cloud);
        when(a.getTemplateOrNull()).thenReturn(template);
        when(a.getNumExecutors()).thenReturn(executors);
        return a;
    }

    @Test
    void agentsKeptFromBeforeAStartCountAgainUncapped() throws Exception {
        PodTemplate maven = template("maven", "id-1");
        String key = ClusterAgentLimits.templateKey(maven);
        assertTrue(List.of("maven", "id-1").contains(key), key); // the name, or the id with older plugins
        List<Node> nodes = List.of(
                agent("k8s", maven, 1),
                agent("k8s", maven, 1),
                agent("k8s", template("go", "id-2"), 2),
                agent("k8s", null, 1), // its template is gone: the plugin never releases it either
                mock(Node.class)); // not a Kubernetes agent
        Map<List<String>, Integer> survivors = ClusterAgentLimits.survivors(nodes);
        assertEquals(
                Map.of(
                        List.of("k8s-cloud:k8s", "k8s-template:k8s:" + key), 2,
                        List.of(
                                        "k8s-cloud:k8s",
                                        "k8s-template:k8s:" + ClusterAgentLimits.templateKey(template("go", "id-2"))),
                                2),
                survivors);

        ClusterStore store = mock(ClusterStore.class);
        ClusterAgentLimits.countSurvivors(store, nodes);
        verify(store)
                .addWithinCaps(
                        eq(Map.of("k8s-cloud:k8s", Integer.MAX_VALUE, "k8s-template:k8s:" + key, Integer.MAX_VALUE)),
                        eq(2));
    }
}
