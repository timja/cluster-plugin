package io.jenkins.plugins.cluster.spi;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What every {@link ClusterBlobStore} must do, logs included: each backend's test runs this against it. */
public abstract class ClusterBlobStoreContract {

    /** A fresh, empty blob store. */
    protected abstract ClusterBlobStore blobs();

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String s(byte[] b) {
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    private static void put(ClusterBlobStore blobs, String key, String content) throws IOException {
        byte[] bytes = b(content);
        blobs.put(key, new ByteArrayInputStream(bytes), bytes.length);
    }

    private static String get(ClusterBlobStore blobs, String key) throws IOException {
        try (InputStream in = blobs.get(key)) {
            return in == null ? null : s(in.readAllBytes());
        }
    }

    @Test
    void putGetListMoveDelete() throws Exception {
        ClusterBlobStore blobs = blobs();
        assertNull(get(blobs, "jobs/p/builds/1/files.tgz"));
        assertEquals(0, blobs.size("jobs/p/builds/1/files.tgz"));
        assertFalse(blobs.download("jobs/p/builds/1/files.tgz", new ByteArrayOutputStream()));
        put(blobs, "jobs/p/builds/1/files.tgz", "one");
        put(blobs, "jobs/p/builds/1/files.tgz", "two");
        put(blobs, "jobs/p/builds/2/files.tgz", "three");
        put(blobs, "jobs/p/builds/10/files.tgz", "ten");
        assertEquals("two", get(blobs, "jobs/p/builds/1/files.tgz"));
        assertEquals(3, blobs.size("jobs/p/builds/1/files.tgz"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(blobs.download("jobs/p/builds/1/files.tgz", out));
        assertEquals("two", out.toString(StandardCharsets.UTF_8));
        assertEquals(List.of("jobs/p/builds/1/files.tgz"), blobs.list("jobs/p/builds/1"), "below the key, not beside it (builds/10)");
        assertEquals(3, blobs.list("jobs/p").size());
        blobs.move("jobs/p", "jobs/q");
        assertTrue(blobs.list("jobs/p").isEmpty());
        assertEquals("three", get(blobs, "jobs/q/builds/2/files.tgz"));
        blobs.move("jobs/p", "jobs/q"); // repeated after a partial failure: nothing left to do
        blobs.delete("jobs/q/builds/1");
        assertEquals(2, blobs.list("jobs/q").size());
        blobs.delete("jobs/q");
        assertTrue(blobs.list("jobs/q").isEmpty());
    }

    @Test
    void logs() throws Exception {
        LogBlobs logs = blobs().logs();
        String log = "jobs/p/builds/1/log";
        assertEquals(0, logs.size(log));
        assertNull(logs.readFrom(log, 0));
        assertFalse(logs.download(log, new ByteArrayOutputStream()));
        assertTrue(logs.appendAt(log, b("hello "), 0, 6, 0));
        assertTrue(logs.appendAt(log, b("xxworld"), 2, 5, 6));
        assertEquals(11, logs.size(log));
        assertEquals("hello world", s(logs.readFrom(log, 0)));
        assertEquals("world", s(logs.readFrom(log, 6)));
        assertArrayEquals(new byte[0], logs.readFrom(log, 11));
        assertFalse(logs.appendAt(log, b("!"), 0, 1, 6), "another writer's position: refused");
        assertEquals("hello world", s(logs.readFrom(log, 0)));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(logs.download(log, out));
        assertEquals("hello world", out.toString(StandardCharsets.UTF_8));
        logs.complete(log);
        assertEquals("hello world", s(logs.readFrom(log, 0)), "complete keeps the log as it is");
        assertTrue(logs.appendAt(log, b("!"), 0, 1, 11), "late output, after completion");
        assertEquals("hello world!", s(logs.readFrom(log, 0)));
        assertEquals(12, logs.size(log));
        logs.delete(log);
        assertEquals(0, logs.size(log));
        assertNull(logs.readFrom(log, 0));
        assertTrue(logs.appendAt(log, b("again"), 0, 5, 0), "a deleted log starts afresh");
        assertEquals("again", s(logs.readFrom(log, 0)));
    }

    @Test
    void logsAreIndependentOfTheirNeighbours() throws Exception {
        ClusterBlobStore blobs = blobs();
        LogBlobs logs = blobs.logs();
        assertTrue(logs.appendAt("jobs/p/builds/1/log", b("log"), 0, 3, 0));
        assertTrue(logs.appendAt("jobs/p/builds/1/log-index", b("index"), 0, 5, 0));
        logs.delete("jobs/p/builds/1/log");
        assertEquals("index", s(logs.readFrom("jobs/p/builds/1/log-index", 0)));
    }

    @Test
    void aWriterResumingFromAnotherInstanceContinues() throws Exception {
        ClusterBlobStore blobs = blobs();
        String log = "jobs/p/builds/1/log";
        assertTrue(blobs.logs().appendAt(log, b("one "), 0, 4, 0));
        // e.g. a restarted or adopting controller: it learns where the log ends from the store, not from memory
        long end = blobs.logs().size(log);
        assertEquals(4, end);
        assertTrue(blobs.logs().appendAt(log, b("two"), 0, 3, end));
        assertEquals("one two", s(blobs.logs().readFrom(log, 0)));
    }
}
