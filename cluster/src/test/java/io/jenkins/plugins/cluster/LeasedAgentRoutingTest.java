package io.jenkins.plugins.cluster;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import hudson.model.Computer;
import hudson.slaves.DumbSlave;
import io.jenkins.plugins.cluster.agents.LaunchLease;
import io.jenkins.plugins.cluster.spi.InMemoryClusterStore;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import jenkins.model.Jenkins;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Requests for an agent that another controller connects (it holds the agent's launch lease) are answered by that
 * controller, with the user's own credentials, as if they had been sent there.
 */
@WithJenkins
class LeasedAgentRoutingTest {

    @BeforeAll
    static void cluster() {
        ClusterFixture.activate();
    }

    @AfterAll
    static void noCluster() {
        ClusterFixture.deactivate();
    }

    private JenkinsRule j;
    private InMemoryClusterStore store;
    private HttpServer holder;
    /** What the other controller was asked: method, path, and the headers that matter here. */
    private final List<String> received = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp(JenkinsRule j) throws Exception {
        this.j = j;
        store = ClusterFixture.store();
        holder = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        holder.createContext("/", exchange -> {
            String forwarded = exchange.getRequestHeaders().getFirst(Forwarding.FORWARDED);
            received.add(exchange.getRequestMethod() + " "
                    + exchange.getRequestURI().getRawPath()
                    + " authorization=" + exchange.getRequestHeaders().getFirst("Authorization")
                    + " forwarded="
                    + (forwarded != null
                            && Forwarding.isAuthentic(forwarded, "", System.currentTimeMillis(), (m, mac) -> true)));
            byte[] body = "answered by other".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        holder.start();
        store.url(
                "other",
                "http://" + holder.getAddress().getHostString() + ":"
                        + holder.getAddress().getPort() + "/");
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ, Computer.EXTENDED_READ)
                .everywhere()
                .to("alice"));
    }

    @AfterEach
    void tearDown() {
        holder.stop(0);
    }

    private DumbSlave connectedOnOther(String name) throws Exception {
        assertTrue(store.as("other").launchLeases().acquire(name) > 0);
        DumbSlave agent = j.createSlave(name, null, null);
        ClusterFixture.await(
                "refused to launch",
                () -> agent.toComputer().getOfflineCause() instanceof LaunchLease.ConnectedElsewhere);
        return agent;
    }

    private Page get(JenkinsRule.WebClient wc, String path) throws Exception {
        wc.setThrowExceptionOnFailingStatusCode(false);
        wc.setRedirectEnabled(false);
        return wc.getPage(new WebRequest(new URL(j.getURL(), path)));
    }

    @Test
    void anAgentConnectedElsewhereIsServedThere() throws Exception {
        connectedOnOther("agent");
        assertThat(agentOfflineCause(), containsString("Connected on other"));
        JenkinsRule.WebClient wc = j.createWebClient().withBasicCredentials("alice");
        Page page = get(wc, "computer/agent/log");
        assertEquals(200, page.getWebResponse().getStatusCode());
        assertEquals("answered by other", page.getWebResponse().getContentAsString());
        assertEquals("other", page.getWebResponse().getResponseHeaderValue(Forwarding.FORWARDED));
        assertEquals(1, received.size(), received::toString);
        assertThat(received.get(0), containsString("GET /jenkins/computer/agent/log "));
        assertThat(received.get(0), containsString(" forwarded=true"));
        assertThat(
                "checked there with the user's own credentials",
                received.get(0),
                containsString("authorization=Basic "));
    }

    private String agentOfflineCause() {
        return String.valueOf(j.jenkins.getComputer("agent").getOfflineCause());
    }

    @Test
    void notForwardedForUsersWhoCannotSeeIt() throws Exception {
        connectedOnOther("agent");
        store.as("other").channelConnected("agent"); // by its channel, as well as by its lease
        Page page = get(j.createWebClient(), "computer/agent/"); // anonymous: no Overall/Read
        assertTrue(received.isEmpty(), received::toString);
        assertThat(page.getWebResponse().getContentAsString(), not(containsString("answered by other")));
        assertEquals(null, page.getWebResponse().getResponseHeaderValue(Forwarding.FORWARDED));
    }

    @Test
    void otherAgentsAndOtherPathsAreAnsweredHere() throws Exception {
        connectedOnOther("agent");
        DumbSlave here = j.createOnlineSlave();
        JenkinsRule.WebClient wc = j.createWebClient().withBasicCredentials("alice");
        assertEquals(
                200,
                get(wc, "computer/" + here.getNodeName() + "/").getWebResponse().getStatusCode());
        assertEquals(404, get(wc, "computer/nosuch/").getWebResponse().getStatusCode());
        get(wc, "computer/agent%2F..%2F..%2Fscript");
        get(wc, "computer/agent/%2e%2e/%2e%2e/script");
        assertTrue(received.isEmpty(), received::toString);
    }
}
