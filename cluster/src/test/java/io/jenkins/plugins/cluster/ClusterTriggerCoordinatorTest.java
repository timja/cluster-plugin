package io.jenkins.plugins.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import hudson.triggers.Trigger;
import io.jenkins.plugins.cluster.spi.ClusterStore;
import io.jenkins.plugins.cluster.spi.Membership;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ClusterTriggerCoordinatorTest {

    @Test
    void rendezvousOwnership() {
        java.util.List<String> three = List.of("m1", "m2", "m3");
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (int i = 0; i < 300; i++) {
            String name = "job" + i;
            String owner = ClusterTriggerCoordinator.ownerOf(name, three);
            counts.merge(owner, 1, Integer::sum);
            // removing a member only moves the items it owned
            for (String gone : three) {
                java.util.List<String> two = new java.util.ArrayList<>(three);
                two.remove(gone);
                if (!owner.equals(gone)) {
                    assertEquals(owner, ClusterTriggerCoordinator.ownerOf(name, two));
                }
            }
        }
        for (String m : three) {
            assertTrue(counts.get(m) > 60, counts::toString);
        }
        assertEquals("folder", ClusterTriggerCoordinator.topLevel("folder/sub/job"));
    }

    private static Membership.Member member(String origin, boolean alive, String info) {
        return new Membership.Member(origin, origin, "http://" + origin + ":8080/", 0, 0, alive, "v", 2, null, info);
    }

    @Test
    void onlyReadyMembersOwnTriggers() throws Exception {
        List<Membership.Member> all = List.of(
                member("a", true, new MemberStatus.Info(0, 0, java.util.Map.of(), 0, 0).toJson()),
                member("b", true, MemberStatus.STARTING), // heartbeating, still loading its items
                member("c", true, null), // an older controller, which does not say
                member("d", false, null)); // gone
        assertEquals(Set.of("a", "c"), ClusterTriggerCoordinator.readyMembers(all, "a", true, () -> Set.of("x")));
        assertEquals(Set.of("c"), ClusterTriggerCoordinator.readyMembers(all, "a", false, () -> Set.of("x")),
                "this controller as it knows itself, whatever its last heartbeat said");
        assertEquals(Set.of("a", "b", "c"), ClusterTriggerCoordinator.readyMembers(all, "b", true, () -> Set.of("x")));
        assertEquals(Set.of("x"), ClusterTriggerCoordinator.readyMembers(List.of(member("b", true, MemberStatus.STARTING)), "b", false,
                () -> Set.of("x")), "nobody ready: every live member, as before");
    }

    /** A name that hashes to {@code owner} among {@code members}. */
    private static String itemOwnedBy(String owner, List<String> members) {
        for (int i = 0; ; i++) {
            if (ClusterTriggerCoordinator.ownerOf("job" + i, members).equals(owner)) {
                return "job" + i;
            }
        }
    }

    @Test
    void aGoneOwnersMissedTicksRunOnceOnTheNewOwner() throws Exception {
        ClusterStore store = mock(ClusterStore.class);
        when(store.getOrigin()).thenReturn("a");
        String ready = new MemberStatus.Info(0, 0, java.util.Map.of(), 0, 0).toJson();
        when(store.members()).thenReturn(List.of(member("a", true, ready), member("b", true, ready)));
        Set<String> claims = new HashSet<>();
        when(store.claim(anyString(), anyString(), anyLong())).thenAnswer(i -> claims.add(i.getArgument(0) + "/" + i.getArgument(1) + "@" + i.getArgument(2)));
        ClusterTriggerCoordinator c = new ClusterTriggerCoordinator();
        c.readyHere = () -> true;
        String job = itemOwnedBy("b", List.of("a", "b"));

        // b owns the job: a leaves its ticks to b, which dies before running the second one
        assertFalse(c.shouldRun(store, job, "timer", 100, "t"));
        claims.add(job + "/timer@100"); // b ran this one
        assertFalse(c.shouldRun(store, job, "timer", 101, "t"));
        assertFalse(c.shouldRun(store, job, "timer", 102, "t"));

        Trigger<?> trigger = mock(Trigger.class);
        c.catchUp(store, 103, (item, id) -> trigger);
        verify(trigger, never()).run(); // b is still alive: its ticks are its own

        when(store.members()).thenReturn(List.of(member("a", true, ready), member("b", false, ready)));
        c.forgetMembers();
        c.catchUp(store, 103, (item, id) -> trigger);
        verify(trigger, times(1)).run(); // once for 101 and 102
        assertTrue(claims.containsAll(Set.of(job + "/timer@101", job + "/timer@102")), claims::toString);
        c.catchUp(store, 104, (item, id) -> trigger);
        verify(trigger, times(1)).run();

        // the next tick is a's own
        assertTrue(c.shouldRun(store, job, "timer", 104, "t"));
        assertFalse(c.shouldRun(store, job, "timer", 104, "t"), "claimed already");
    }

    @Test
    void missedTicksAlreadyClaimedAreNotRunAgain() throws Exception {
        ClusterStore store = mock(ClusterStore.class);
        when(store.getOrigin()).thenReturn("a");
        String ready = new MemberStatus.Info(0, 0, java.util.Map.of(), 0, 0).toJson();
        when(store.members()).thenReturn(List.of(member("a", true, ready), member("b", true, ready)));
        when(store.claim(anyString(), anyString(), anyLong())).thenReturn(false); // b ran them before it went
        ClusterTriggerCoordinator c = new ClusterTriggerCoordinator();
        c.readyHere = () -> true;
        String job = itemOwnedBy("b", List.of("a", "b"));
        assertFalse(c.shouldRun(store, job, "timer", 100, "t"));
        when(store.members()).thenReturn(List.of(member("a", true, ready)));
        c.forgetMembers();
        Trigger<?> trigger = mock(Trigger.class);
        c.catchUp(store, 102, (item, id) -> trigger);
        verify(trigger, never()).run();
    }

    @Test
    void aStartingControllerOwnsNothing() throws Exception {
        ClusterStore store = mock(ClusterStore.class);
        when(store.getOrigin()).thenReturn("b");
        String ready = new MemberStatus.Info(0, 0, java.util.Map.of(), 0, 0).toJson();
        when(store.members()).thenReturn(List.of(member("a", true, ready), member("b", true, MemberStatus.STARTING)));
        when(store.claim(anyString(), anyString(), anyLong())).thenReturn(true);
        ClusterTriggerCoordinator c = new ClusterTriggerCoordinator();
        c.readyHere = () -> false;
        String job = itemOwnedBy("b", List.of("a", "b"));
        assertFalse(c.shouldRun(store, job, "timer", 100, "t"), "left to a while b loads its items");
        verify(store, never()).claim(anyString(), anyString(), anyLong());
    }
}
