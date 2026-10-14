package io.jenkins.plugins.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
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
}
