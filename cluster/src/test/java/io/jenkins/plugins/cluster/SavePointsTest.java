package io.jenkins.plugins.cluster;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SavePointsTest {

    @Test
    void savePointsAreOrderedByEpochThenSequence() {
        long[] a = SavePoints.parseSavePoint("48.9");
        long[] b = SavePoints.parseSavePoint("48.10");
        long[] c = SavePoints.parseSavePoint("61.1");
        assertTrue(SavePoints.compareSavePoints(a, b) < 0, "numerically, not as text");
        assertTrue(SavePoints.compareSavePoints(b, c) < 0, "a later epoch (an adopter's) comes after");
        assertTrue(SavePoints.compareSavePoints(c, c) == 0);
        assertNull(SavePoints.parseSavePoint("workflow"));
        assertNull(SavePoints.parseSavePoint("x.1"));
    }
}
