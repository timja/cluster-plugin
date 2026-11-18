package io.jenkins.plugins.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class JobPathTest {

    @Test
    void theItemsAPathGoesThroughAndWhatFollows() {
        JobPath p = JobPath.parse("/view/all/job/f/job/sub/job/p/12/input/x/proceed");
        assertEquals(List.of("f", "sub", "p"), p.names());
        assertEquals("f/sub/p", p.fullName());
        assertEquals(List.of("f", "f/sub", "f/sub/p"), p.fullNames());
        assertEquals("/view/all/job/f/job/sub/job/p", p.before());
        assertEquals("12", p.next());
        assertEquals("input/x/proceed", p.afterNext());

        JobPath last = JobPath.parse("/job/p/lastBuild/");
        assertEquals("lastBuild", last.next());
        assertEquals("", last.afterNext());
        assertEquals("/job/p", last.before());

        JobPath job = JobPath.parse("/job/p");
        assertEquals("p", job.fullName());
        assertNull(job.next());
    }

    @Test
    void oneDecodingRule() {
        // percent-decoded, a plus staying a plus, wherever the path is read
        assertEquals("my job+x", JobPath.parse("/job/my%20job+x/1").fullName());
        assertEquals("a%b", JobPath.parse("/job/a%25b/").fullName());
        assertEquals(List.of("my job+x"), ItemCatchUpFilter.itemNames("/job/my%20job+x/build"));
        assertEquals(
                "my job+x", OwnerRoutingFilter.buildPath("/job/my%20job+x/1").job());
    }

    @Test
    void pathsNamingNoItemOrNotReadingAsWrittenAreNotRead() {
        assertNull(JobPath.parse("/"));
        assertNull(JobPath.parse("/job/"));
        assertNull(JobPath.parse("/manage/configure"));
        assertNull(JobPath.parse("/view/all/"));
        assertNull(JobPath.parse("/job/a%2Fb/1"), "an encoded separator in a name");
        assertNull(JobPath.parse("/job/a%5Cb/1"), "an encoded backslash in a name");
        assertNull(JobPath.parse("/job/p/1/../../../script"), "a dot segment after the items");
        assertNull(JobPath.parse("/job/p/%2e%2e/script"), "an encoded dot segment");
        assertNull(JobPath.parse("/job/%zz/1"), "not a URL");
        assertEquals(List.of(), ItemCatchUpFilter.itemNames("/job/f/job/a%2Fb/"));
        assertNull(OwnerRoutingFilter.buildPath("/job/p/5/../../../script"));
        assertNull(JobPath.segments("/computer/agent1/./log"));
        assertEquals(List.of("", "computer", "agent 1", ""), JobPath.segments("/computer/agent%201/"));
    }
}
