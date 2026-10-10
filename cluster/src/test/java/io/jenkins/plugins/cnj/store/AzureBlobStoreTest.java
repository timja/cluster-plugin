package io.jenkins.plugins.cnj.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
