package io.jenkins.plugins.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class OwnerRoutingTest {

    @Test
    void buildPaths() {
        assertEquals(new OwnerRoutingFilter.BuildPath("p", 5), OwnerRoutingFilter.buildPath("/job/p/5/"));
        assertEquals(new OwnerRoutingFilter.BuildPath("p", 5), OwnerRoutingFilter.buildPath("/job/p/5/logText/progressiveText"));
        assertEquals(new OwnerRoutingFilter.BuildPath("f/sub/p", 12), OwnerRoutingFilter.buildPath("/view/all/job/f/job/sub/job/p/12/input/x/proceed"));
        assertEquals(new OwnerRoutingFilter.BuildPath("my job+x", 1), OwnerRoutingFilter.buildPath("/job/my%20job+x/1"));
        assertNull(OwnerRoutingFilter.buildPath("/job/p/"), "the job");
        assertNull(OwnerRoutingFilter.buildPath("/job/p/lastBuild/"), "a permalink");
        assertNull(OwnerRoutingFilter.buildPath("/job/p/configure"));
        assertNull(OwnerRoutingFilter.buildPath("/job/p/0/"));
        assertNull(OwnerRoutingFilter.buildPath("/job/p/99999999999/"));
        assertNull(OwnerRoutingFilter.buildPath("/computer/agent1/"));
        assertNull(OwnerRoutingFilter.buildPath("/"));
    }

    @Test
    void computerPaths() {
        assertEquals("agent1", OwnerRoutingFilter.computerPath("/computer/agent1/"));
        assertEquals("agent 1", OwnerRoutingFilter.computerPath("/computer/agent%201/log"));
        assertNull(OwnerRoutingFilter.computerPath("/computer/(built-in)/"));
        assertNull(OwnerRoutingFilter.computerPath("/computer/api/json"));
        assertNull(OwnerRoutingFilter.computerPath("/computer/"));
        assertNull(OwnerRoutingFilter.computerPath("/job/p/1/"));
    }
}
