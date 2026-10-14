package io.jenkins.plugins.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class ItemCatchUpFilterTest {

    @Test
    void itemsAPathGoesThrough() {
        assertEquals(List.of("p"), ItemCatchUpFilter.itemNames("/job/p/build"));
        assertEquals(List.of("f", "f/p"), ItemCatchUpFilter.itemNames("/job/f/job/p/1/console"));
        assertEquals(List.of("f", "f/p"), ItemCatchUpFilter.itemNames("/view/all/job/f/job/p/"));
        assertEquals(List.of("a b"), ItemCatchUpFilter.itemNames("/job/a%20b/"));
        assertEquals(List.of(), ItemCatchUpFilter.itemNames("/manage/configure"));
        assertEquals(List.of(), ItemCatchUpFilter.itemNames("/job/"));
    }

    @Test
    void configKeys() {
        assertEquals("jobs/p/config.xml", ItemCatchUpFilter.configKey("p"));
        assertEquals("jobs/f/jobs/p/config.xml", ItemCatchUpFilter.configKey("f/p"));
    }
}
