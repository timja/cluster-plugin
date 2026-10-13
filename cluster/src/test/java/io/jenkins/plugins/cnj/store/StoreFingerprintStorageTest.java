package io.jenkins.plugins.cnj.store;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.kohsuke.stapler.ClassDescriptor;
import org.junit.jupiter.api.Test;

class StoreFingerprintStorageTest {

    @Test
    void canBeBoundFromTheConfigureSystemForm() {
        // saving Configure System binds the chosen fingerprint storage from the form; without a @DataBoundConstructor
        // that failed with NoStaplerConstructorException
        assertEquals(0, new ClassDescriptor(StoreFingerprintStorage.class).loadConstructorParamNames().length);
    }

    @Test
    void usagesAddedElsewhereAreKept() {
        // we read {a: 1}; we add a#2; meanwhile another controller added b#1
        assertEquals(
                Map.of("a", "1-2", "b", "1"),
                StoreFingerprintStorage.merge(Map.of("a", "1"), Map.of("a", "1-2"), Map.of("a", "1", "b", "1")));
    }

    @Test
    void bothControllersAddToTheSameJob() {
        assertEquals(
                Map.of("p", "1-3"),
                StoreFingerprintStorage.merge(Map.of("p", "1"), Map.of("p", "1,3"), Map.of("p", "1-2")));
    }

    @Test
    void trimmedUsagesStayTrimmed() {
        // we read {a: 1-3, b: 1}; we trimmed a#1 and b (deleted builds); meanwhile a#4 was added elsewhere
        assertEquals(
                Map.of("a", "2-4"),
                StoreFingerprintStorage.merge(Map.of("a", "1-3", "b", "1"), Map.of("a", "2-3"), Map.of("a", "1-4", "b", "1")));
    }

    @Test
    void firstSave() {
        assertEquals(Map.of("a", "5"), StoreFingerprintStorage.merge(Map.of(), Map.of("a", "5"), Map.of()));
    }
}
