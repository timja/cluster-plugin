package io.jenkins.plugins.cnj.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The {@link ChunkedLogBlobs} of a blob store without appendable blobs. */
class ChunkedLogBlobsTest extends ClusterBlobStoreContract {

    @Override
    protected ClusterBlobStore blobs() {
        return new InMemoryClusterBlobStore();
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void completeCompactsIntoOneBlob() throws Exception {
        InMemoryClusterBlobStore blobs = new InMemoryClusterBlobStore();
        String log = "jobs/p/builds/1/log";
        blobs.logs().appendAt(log, b("a"), 0, 1, 0);
        blobs.logs().appendAt(log, b("b"), 0, 1, 1);
        blobs.logs().appendAt(log, b("c"), 0, 1, 2);
        assertEquals(3, blobs.list(log + ".d").size());
        blobs.logs().complete(log);
        assertTrue(blobs.list(log + ".d").isEmpty());
        assertEquals("abc", new String(blobs.blobs.get(log), StandardCharsets.UTF_8));
    }

    @Test
    void aCompactionInterruptedMidwayLosesNothing() throws Exception {
        InMemoryClusterBlobStore blobs = new InMemoryClusterBlobStore();
        String log = "jobs/p/builds/1/log";
        blobs.logs().appendAt(log, b("one "), 0, 4, 0);
        blobs.logs().appendAt(log, b("two "), 0, 4, 4);
        blobs.logs().appendAt(log, b("three"), 0, 5, 8);
        // the compacted blob was written, and only the first chunk deleted, before the controller died
        blobs.put(log, new ByteArrayInputStream(b("one two three")), 13);
        blobs.blobs.remove(blobs.list(log + ".d").get(0));
        LogBlobs fresh = new ChunkedLogBlobs(blobs); // another controller
        assertEquals("one two three", new String(fresh.readFrom(log, 0), StandardCharsets.UTF_8));
        assertEquals(13, fresh.size(log));
        fresh.complete(log); // done again: finishes the job
        assertTrue(blobs.list(log + ".d").isEmpty());
        assertEquals("one two three", new String(fresh.readFrom(log, 0), StandardCharsets.UTF_8));
    }

    @Test
    void aGapIsNeverReadPast() throws Exception {
        InMemoryClusterBlobStore blobs = new InMemoryClusterBlobStore();
        String log = "jobs/p/builds/1/log";
        blobs.logs().appendAt(log, b("one "), 0, 4, 0);
        // a chunk beyond the end, as a writer that lost track of the log might leave
        blobs.put(log + ".d/" + String.format("%016d", 10) + ".3", new ByteArrayInputStream(b("odd")), 3);
        assertEquals(4, blobs.logs().size(log));
        assertEquals("one ", new String(blobs.logs().readFrom(log, 0), StandardCharsets.UTF_8));
    }
}
