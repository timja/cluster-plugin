package io.jenkins.plugins.cluster;

import io.jenkins.plugins.cluster.spi.Membership;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class ClusterViewTest {

    private static Membership.Member m(String origin, String name, long heartbeat, boolean alive) {
        return new Membership.Member(origin, name, "http://" + name + ":8080/", heartbeat, heartbeat - 1000, alive, "v", 1, null, null);
    }

    @Test
    void earlierIncarnationsAreLeftOut() {
        long now = 10_000_000;
        List<Membership.Member> members = List.of( // newest first, as the store lists them
                m("c", "cnj-1", now, true),
                m("b", "cnj-0", now, true),
                m("a2", "cnj-1", now - 60_000, false),
                m("a1", "cnj-0", now - 120_000, false));
        assertEquals(List.of("b", "c"), ClusterView.visible(members, now).stream().map(Membership.Member::origin).toList());
    }

    @Test
    void aControllerThatIsDownIsShownByItsLastIncarnation() {
        long now = 10_000_000;
        List<Membership.Member> members = List.of(
                m("b", "cnj-0", now, true),
                m("c2", "cnj-1", now - 60_000, false),
                m("c1", "cnj-1", now - 600_000, false),
                m("old", "cnj-2", now - ClusterView.GONE_SHOWN_MILLIS - 1, false));
        assertEquals(List.of("b", "c2"), ClusterView.visible(members, now).stream().map(Membership.Member::origin).toList(),
                "cnj-1 is down: its newest incarnation; cnj-2 gone for over an hour: left out");
    }
}
