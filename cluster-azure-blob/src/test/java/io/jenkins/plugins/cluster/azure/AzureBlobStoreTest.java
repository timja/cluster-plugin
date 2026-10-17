package io.jenkins.plugins.cluster.azure;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class AzureBlobStoreTest {

    @Container
    static final GenericContainer<?> AZURITE = new GenericContainer<>("mcr.microsoft.com/azure-storage/azurite:3.34.0")
            .withCommand("azurite-blob", "--blobHost", "0.0.0.0", "--skipApiVersionCheck")
            .withExposedPorts(10000);

    /** Azurite's well-known development account. */
    static String connectionString() {
        return "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;"
                + "AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==;"
                + "BlobEndpoint=http://" + AZURITE.getHost() + ":" + AZURITE.getMappedPort(10000)
                + "/devstoreaccount1;";
    }

    private AzureBlobStore blobs;

    @BeforeEach
    void setUp() {
        blobs = new AzureBlobStore(connectionString(), "test-" + UUID.randomUUID());
    }

    private void put(String key, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        blobs.put(key, new ByteArrayInputStream(bytes), bytes.length);
    }

    private String get(String key) throws IOException {
        try (InputStream in = blobs.get(key)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void putGetReplace() throws Exception {
        assertNull(get("jobs/p/builds/1/files.tgz"));
        put("jobs/p/builds/1/files.tgz", "one");
        assertEquals("one", get("jobs/p/builds/1/files.tgz"));
        put("jobs/p/builds/1/files.tgz", "two");
        assertEquals("two", get("jobs/p/builds/1/files.tgz"));
    }

    @Test
    void deleteAndMoveWholePrefixesOnly() throws Exception {
        put("jobs/a/builds/1/files.tgz", "a1");
        put("jobs/a/builds/2/files.tgz", "a2");
        put("jobs/ab/builds/1/files.tgz", "ab1");

        blobs.move("jobs/a", "jobs/f/jobs/a");
        assertNull(get("jobs/a/builds/1/files.tgz"));
        assertEquals("a1", get("jobs/f/jobs/a/builds/1/files.tgz"));
        assertEquals("a2", get("jobs/f/jobs/a/builds/2/files.tgz"));
        assertEquals("ab1", get("jobs/ab/builds/1/files.tgz"), "sibling with a common name prefix untouched");

        blobs.delete("jobs/f/jobs/a/builds/1");
        assertNull(get("jobs/f/jobs/a/builds/1/files.tgz"));
        assertEquals("a2", get("jobs/f/jobs/a/builds/2/files.tgz"));
        blobs.delete("jobs/f");
        assertNull(get("jobs/f/jobs/a/builds/2/files.tgz"));
        assertEquals("ab1", get("jobs/ab/builds/1/files.tgz"));
    }

    @Test
    void appendAndReadFromOffset() throws Exception {
        String key = "jobs/p/builds/3/log";
        assertEquals(0, blobs.size(key));
        assertNull(blobs.readFrom(key, 0));
        byte[] first = "first line\n".getBytes(StandardCharsets.UTF_8);
        blobs.append(key, first, 0, first.length);
        byte[] second = "second line\n".getBytes(StandardCharsets.UTF_8);
        blobs.append(key, second, 0, second.length);

        assertEquals(first.length + second.length, blobs.size(key));
        assertEquals("first line\nsecond line\n", new String(blobs.readFrom(key, 0), StandardCharsets.UTF_8));
        assertArrayEquals(second, blobs.readFrom(key, first.length));
        assertEquals(0, blobs.readFrom(key, first.length + second.length).length);
    }

    @Test
    void appendLargerThanOneBlock() throws Exception {
        byte[] big = new byte[AzureBlobStore.MAX_APPEND_BLOCK + 1234];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) ('a' + i % 26);
        }
        blobs.append("jobs/p/builds/4/log", big, 0, big.length);
        assertArrayEquals(big, blobs.readFrom("jobs/p/builds/4/log", 0));
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void aStaleWriterCannotAppendToASegmentAnotherWentOnFrom() throws Exception {
        String container = "test-" + UUID.randomUUID();
        AzureBlobStore a = new AzureBlobStore(connectionString(), container, 2);
        AzureBlobStore b = new AzureBlobStore(connectionString(), container, 2);
        String log = "jobs/p/builds/1/log";
        assertTrue(a.logs().appendAt(log, b("one\n"), 0, 4, 0)); // segment 0, one block
        // b adopts the build and appends two blocks' worth: more than segment 0 has room for
        byte[] big = new byte[AzureBlobStore.MAX_APPEND_BLOCK + 10];
        java.util.Arrays.fill(big, (byte) 'b');
        long end = b.logs().size(log);
        assertTrue(b.logs().appendAt(log, big, 0, big.length, end));
        assertEquals(2, b.segments(log).size(), "its first block filled segment 0, its second went on in segment 1");
        // a wakes: one more block would fit in segment 0 as a last saw it
        assertFalse(a.logs().appendAt(log, b("stale\n"), 0, 6, 4), "refused: segment 0 is longer");
        assertEquals(4 + big.length, a.logs().size(log));
        byte[] all = a.logs().readFrom(log, 0);
        assertEquals("one\n", new String(all, 0, 4, StandardCharsets.UTF_8));
        assertEquals(4 + big.length, all.length);
        for (int i = 4; i < all.length; i++) {
            assertEquals('b', all[i]);
        }

        // at a segment's end: whoever appends next goes on in the next segment, so a stale writer meets the other there
        String log2 = "jobs/p/builds/2/log";
        assertTrue(a.logs().appendAt(log2, b("1"), 0, 1, 0));
        assertTrue(a.logs().appendAt(log2, b("2"), 0, 1, 1)); // segment 0 is full
        assertTrue(b.logs().appendAt(log2, b("3"), 0, 1, b.logs().size(log2)));
        assertFalse(a.logs().appendAt(log2, b("X"), 0, 1, 2), "segment 1 is b's already");
        assertEquals("123", new String(a.logs().readFrom(log2, 0), StandardCharsets.UTF_8));
    }

    @Test
    void segmentedLogsReadAsOne() throws Exception {
        String base = "jobs/p/builds/1/log";
        assertTrue(blobs.appendAt(base, b("one\n"), 0, 4, 0));
        assertTrue(blobs.appendAt(AzureBlobStore.segment(base, 1), b("two\n"), 0, 4, 0));
        assertTrue(blobs.appendAt(AzureBlobStore.segment(base, 2), b("three\n"), 0, 6, 0));
        assertTrue(blobs.appendAt(base + "-index", b("idx"), 0, 3, 0)); // shares the name as a prefix: not a segment
        java.util.List<AzureBlobStore.Segment> segments = blobs.segments(base);
        assertEquals(java.util.List.of(0, 1, 2), segments.stream().map(AzureBlobStore.Segment::index).toList());
        assertEquals(1, segments.get(0).blocks());
        assertEquals(14, blobs.segmentedSize(base));
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        assertTrue(blobs.downloadSegmented(base, out));
        assertEquals("one\ntwo\nthree\n", out.toString(StandardCharsets.UTF_8));
        assertEquals("o\nthree\n", new String(blobs.readSegmentedFrom(base, 6), StandardCharsets.UTF_8), "across a boundary");
        blobs.deleteSegmented(base);
        assertTrue(blobs.segments(base).isEmpty());
        assertEquals(3, blobs.size(base + "-index"), "the index is left alone");
    }

    @Test
    void movedLogsStayAppendable() throws Exception {
        assertTrue(blobs.appendAt("jobs/old/builds/1/log", b("before\n"), 0, 7, 0));
        put("jobs/old/builds/1/files.tgz", "snapshot");
        blobs.move("jobs/old", "jobs/new");
        assertTrue(blobs.appendAt("jobs/new/builds/1/log", b("after\n"), 0, 6, 7), "still an append blob, at its length");
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        blobs.download("jobs/new/builds/1/log", out);
        assertEquals("before\nafter\n", out.toString(StandardCharsets.UTF_8));
        assertEquals("snapshot", get("jobs/new/builds/1/files.tgz"));
    }
}
