package io.jenkins.plugins.cluster;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import io.jenkins.plugins.cluster.spi.ClusterBlobStore;
import io.jenkins.plugins.cluster.spi.ClusterBlobStoreProvider;
import io.jenkins.plugins.cluster.spi.InMemoryClusterBlobStore;
import io.jenkins.plugins.cluster.spi.InMemoryClusterStore;
import jenkins.persistence.ItemChangeApplier;
import jenkins.persistence.PersistenceStore;
import jenkins.persistence.PersistenceStoreProvider;

/**
 * An in-memory cluster store and blob store for tests run with Jenkins: active only for test classes which
 * {@linkplain #activate() activate} it (in {@code @BeforeAll}), each Jenkins started then getting fresh ones.
 * Another controller sharing them is {@link InMemoryClusterStore#as}.
 */
public final class ClusterFixture {

    private static volatile boolean active;
    private static volatile InMemoryClusterStore store;
    private static volatile InMemoryClusterBlobStore blobs;

    private ClusterFixture() {}

    public static void activate() {
        active = true;
    }

    public static void deactivate() {
        active = false;
    }

    public static InMemoryClusterStore store() {
        return store;
    }

    public static InMemoryClusterBlobStore blobs() {
        return blobs;
    }

    /** Waits until a condition holds, failing after ten seconds. */
    public static void await(String what, Condition condition) throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!condition.holds()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out waiting for " + what);
            }
            Thread.sleep(50);
        }
    }

    /**
     * Waits until this controller has applied every change other controllers made so far, through core's dispatch
     * (a handler may still be working on one off that thread).
     */
    public static void awaitApplied() throws Exception {
        await("changes made elsewhere to be applied here", ItemChangeApplier::isIdle);
    }

    /** A condition that may throw. */
    @FunctionalInterface
    public interface Condition {
        boolean holds() throws Exception;
    }

    @Extension
    public static final class Stores implements PersistenceStoreProvider {
        @CheckForNull
        @Override
        public PersistenceStore create() {
            if (!active) {
                return null;
            }
            store = new InMemoryClusterStore();
            blobs = new InMemoryClusterBlobStore();
            return store;
        }
    }

    @Extension
    public static final class Blobs implements ClusterBlobStoreProvider {
        @CheckForNull
        @Override
        public ClusterBlobStore create() {
            return active ? blobs : null;
        }
    }
}
