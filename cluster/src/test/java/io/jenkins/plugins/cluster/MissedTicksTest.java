package io.jenkins.plugins.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MissedTicksTest {

    private static final String A = "a";
    private static final String B = "b";
    private static final String C = "c";

    @Test
    void ticksLeftToAGoneOwnerAreDueOnceTogether() {
        MissedTicks m = new MissedTicks(15, 1000);
        m.leftTo("job", "timer", 100, B);
        m.leftTo("job", "timer", 101, B);
        m.leftTo("other", "timer", 100, C);
        // b is still ready: nothing to catch up
        assertTrue(m.due(103, Set.of(A, B, C), A, name -> A).isEmpty());
        // b is gone: both its ticks at once; c's stay with c
        assertEquals(
                List.of(new MissedTicks.Due("job", "timer", List.of(100L, 101L))),
                m.due(103, Set.of(A, C), A, name -> A));
        assertTrue(m.due(104, Set.of(A, C), A, name -> A).isEmpty(), "never twice");
        assertEquals(1, m.size(), "c's tick is kept until the window ends");
    }

    @Test
    void notDueUntilAMinuteOld() {
        MissedTicks m = new MissedTicks(15, 1000);
        m.leftTo("job", "timer", 100, B);
        assertTrue(m.due(100, Set.of(A), A, name -> A).isEmpty(), "the owner may still be running it");
        assertEquals(1, m.due(101, Set.of(A), A, name -> A).size());
    }

    @Test
    void onlyTheOwnerNowCatchesUp() {
        MissedTicks m = new MissedTicks(15, 1000);
        m.leftTo("folder/job", "timer", 100, B);
        assertTrue(m.due(102, Set.of(A, C), A, name -> C).isEmpty(), "c owns it now");
        assertEquals(1, m.size(), "kept, in case this controller turns out to own it");
        assertEquals(1, m.due(103, Set.of(A, C), A, name -> A).size());
    }

    @Test
    void aLaterTickHandledSinceCoversTheMissedOnes() {
        MissedTicks m = new MissedTicks(15, 1000);
        m.leftTo("job", "timer", 100, B);
        m.handledHere("job", "timer", 101); // this controller became the owner and ran the next tick
        assertTrue(m.due(103, Set.of(A), A, name -> A).isEmpty());
        assertEquals(0, m.size());

        m.leftTo("job2", "timer", 100, B);
        m.leftTo("job2", "timer", 101, C); // the next tick went to c, which is still ready
        assertTrue(m.due(103, Set.of(A, C), A, name -> A).isEmpty());

        m.leftTo("job3", "timer", 100, C);
        m.leftTo("job3", "timer", 101, B); // a tick left to c, then the later one to b which is gone
        assertEquals(
                List.of(new MissedTicks.Due("job3", "timer", List.of(101L))), m.due(103, Set.of(A, C), A, name -> A));
    }

    @Test
    void boundedWindowAndSize() {
        MissedTicks m = new MissedTicks(15, 2);
        m.leftTo("job", "timer", 100, B);
        m.leftTo("job", "timer", 101, B);
        m.leftTo("job", "timer", 102, B); // beyond the bound: not remembered
        assertEquals(2, m.size());
        assertTrue(m.due(120, Set.of(A), A, name -> A).isEmpty(), "older than the window");
        assertEquals(0, m.size());

        MissedTicks off = new MissedTicks(0, 1000);
        off.leftTo("job", "timer", 100, B);
        assertEquals(0, off.size());
    }
}
