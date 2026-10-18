package io.jenkins.plugins.cluster.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/** The {@link ChunkedLogBlobs} of a blob store without appendable blobs. */
class ChunkedLogBlobsTest extends ClusterBlobStoreContract {

    @Override
    protected ClusterBlobStore blobs() {
        return new InMemoryClusterBlobStore();
    }

    @Override
    protected ClusterBlobStore another(ClusterBlobStore blobs) {
        return new InMemoryClusterBlobStore((InMemoryClusterBlobStore) blobs);
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
        assertEquals(3, blobs.list(log + ".d").size(), "a chunk per append");
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
    void aChunkThatCannotBeReadWhileCompactingIsKept() throws Exception {
        Set<String> failing = ConcurrentHashMap.newKeySet();
        InMemoryClusterBlobStore blobs = new InMemoryClusterBlobStore() {
            @Override
            public boolean download(@NonNull String key, @NonNull OutputStream out) throws IOException {
                return !failing.contains(key) && super.download(key, out);
            }
        };
        String log = "jobs/p/builds/1/log";
        LogBlobs logs = new ChunkedLogBlobs(blobs);
        logs.appendAt(log, b("one "), 0, 4, 0);
        logs.appendAt(log, b("two "), 0, 4, 4);
        logs.appendAt(log, b("three"), 0, 5, 8);
        List<String> chunks = blobs.list(log + ".d");
        assertEquals(3, chunks.size());
        failing.add(chunks.get(1)); // a transient failure, or a stale listing
        logs.complete(log);
        failing.clear();
        assertEquals(
                "one two three",
                new String(new ChunkedLogBlobs(blobs).readFrom(log, 0), StandardCharsets.UTF_8),
                "nothing lost");
        logs.complete(log); // again, as the next compaction
        assertTrue(blobs.list(log + ".d").isEmpty());
        assertEquals("one two three", new String(blobs.blobs.get(log), StandardCharsets.UTF_8));
    }

    @Test
    void aGapIsNeverReadPast() throws Exception {
        InMemoryClusterBlobStore blobs = new InMemoryClusterBlobStore();
        String log = "jobs/p/builds/1/log";
        blobs.logs().appendAt(log, b("one "), 0, 4, 0);
        // a chunk beyond the end, as a writer that lost track of the log might leave
        blobs.put(log + ".d/" + String.format("%016d", 10) + ".e1.abc", new ByteArrayInputStream(b("odd")), 3);
        assertEquals(4, blobs.logs().size(log));
        assertEquals("one ", new String(blobs.logs().readFrom(log, 0), StandardCharsets.UTF_8));
    }

    @Test
    void aWriterAppendingAfterCompactionElsewhereIsNeverRead() throws Exception {
        InMemoryClusterBlobStore blobs = new InMemoryClusterBlobStore();
        String log = "jobs/p/builds/1/log";
        LogBlobs a = blobs.logs();
        LogBlobs b = new ChunkedLogBlobs(blobs);
        assertTrue(a.appendAt(log, b("one "), 0, 4, 0));
        assertTrue(b.appendAt(log, b("two three"), 0, 9, b.size(log)));
        b.complete(log); // b completed the build: the chunks are gone, so nothing fences a at 4 any more
        assertFalse(a.appendAt(log, b("STALE OUTPUT"), 0, 12, 4), "fenced off still");
        // as if it had been written anyway
        blobs.put(log + ".d/" + String.format("%016d", 4) + ".e1.aaa", new ByteArrayInputStream(b("STALE OUTPUT")), 12);
        assertEquals(
                "one two three",
                new String(b.readFrom(log, 0), StandardCharsets.UTF_8),
                "a chunk across the end is not read");
        assertEquals(13, b.size(log));
        assertTrue(b.appendAt(log, b("!"), 0, 1, 13), "late output at the end still is");
        assertEquals("one two three!", new String(b.readFrom(log, 0), StandardCharsets.UTF_8));
    }

    @Test
    void whereAStaleWriterRacedTheNewOneTheNewOneIsRead() throws Exception {
        InMemoryClusterBlobStore blobs = new InMemoryClusterBlobStore();
        String log = "jobs/p/builds/1/log";
        LogBlobs a = new ChunkedLogBlobs(blobs, "aaaa");
        LogBlobs b = new ChunkedLogBlobs(blobs, "bbbb");
        assertTrue(a.appendAt(log, b("one "), 0, 4, 0));
        assertTrue(b.appendAt(log, b("two "), 0, 4, 4)); // b adopts, with a higher epoch
        // a had read the fence just before b wrote it: its chunk at 4 lands too, with its lower epoch
        blobs.put(log + ".d/" + String.format("%016d", 4) + ".e1.aaaa", new ByteArrayInputStream(b("STALE!")), 6);
        assertTrue(b.appendAt(log, b("three"), 0, 5, 8));
        assertEquals("one two three", new String(a.readFrom(log, 0), StandardCharsets.UTF_8));
        assertEquals(13, a.size(log));
        assertFalse(a.appendAt(log, b("more"), 0, 4, 10), "and a is refused from then on");
        b.complete(log);
        assertEquals("one two three", new String(a.readFrom(log, 0), StandardCharsets.UTF_8));
        b.delete(log);
        assertTrue(b.appendAt(log, b("afresh"), 0, 6, 0), "a rewrite by the owner");
        assertFalse(a.appendAt(log, b("x"), 0, 1, 13), "a where it last was");
        // a chunk of a's old epoch at the new end is not read either: epochs only grow, so b's next one is above it
        blobs.put(log + ".d/" + String.format("%016d", 6) + ".e1.aaaa", new ByteArrayInputStream(b("!!")), 2);
        assertTrue(b.appendAt(log, b("?"), 0, 1, 6));
        assertEquals("afresh?", new String(b.readFrom(log, 0), StandardCharsets.UTF_8));
    }

    @Test
    void chunksWrittenBeforeTheFenceAreRead() throws Exception {
        InMemoryClusterBlobStore blobs = new InMemoryClusterBlobStore();
        String log = "jobs/p/builds/1/log";
        blobs.put(log + ".d/" + String.format("%016d", 0) + ".4", new ByteArrayInputStream(b("one ")), 4);
        blobs.put(log + ".d/" + String.format("%016d", 4) + ".3", new ByteArrayInputStream(b("two")), 3);
        assertEquals(7, blobs.logs().size(log));
        assertTrue(blobs.logs().appendAt(log, b(" three"), 0, 6, 7));
        assertEquals("one two three", new String(blobs.logs().readFrom(log, 0), StandardCharsets.UTF_8));
    }
}
