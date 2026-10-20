package io.jenkins.plugins.cluster;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** The plugin manager says that its changes apply to every controller of the cluster. */
@WithJenkins
class ClusterPluginManagerNoticeTest {

    @BeforeAll
    static void cluster() {
        ClusterFixture.activate();
    }

    @AfterAll
    static void noCluster() {
        ClusterFixture.deactivate();
    }

    @Test
    void thePluginManagerShowsTheNotice(JenkinsRule r) throws Exception {
        try (JenkinsRule.WebClient wc = r.createWebClient()) {
            assertNotNull(wc.goTo("manage/pluginManager/").querySelector(".jenkins-cluster-plugins"));
        }
    }
}
