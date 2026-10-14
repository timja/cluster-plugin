package io.jenkins.plugins.cluster.s3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.jenkins.plugins.cluster.spi.ClusterBlobStore;
import io.jenkins.plugins.cluster.spi.ClusterBlobStoreContract;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * {@link S3BlobStore} against Garage, an S3-compatible store: what every cluster blob store must do, with logs as chunks.
 */
@Testcontainers
class S3BlobStoreTest extends ClusterBlobStoreContract {

    static final String ACCESS_KEY = "GKcluster0test0key";
    static final String SECRET_KEY = "cluster-test-secret-key-0123456789";
    static final String BUCKET = "jenkins";

    @Container
    static final GenericContainer<?> GARAGE = new GenericContainer<>("dxflrs/garage:v2.4.1")
            .withCopyToContainer(Transferable.of("""
                    metadata_dir = "/tmp/meta"
                    data_dir = "/tmp/data"
                    db_engine = "sqlite"
                    replication_factor = 1
                    rpc_bind_addr = "[::]:3901"
                    rpc_public_addr = "127.0.0.1:3901"
                    rpc_secret = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

                    [s3_api]
                    s3_region = "garage"
                    api_bind_addr = "[::]:3900"
                    root_domain = ".s3.garage.localhost"
                    """), "/etc/garage.toml")
            .withEnv("GARAGE_DEFAULT_ACCESS_KEY", ACCESS_KEY)
            .withEnv("GARAGE_DEFAULT_SECRET_KEY", SECRET_KEY)
            .withEnv("GARAGE_DEFAULT_BUCKET", BUCKET)
            .withCommand("/garage", "server", "--single-node", "--default-bucket")
            .withExposedPorts(3900)
            .waitingFor(Wait.forLogMessage(".*S3 API server listening.*", 1));

    static S3Client client() {
        return S3Client.builder()
                .httpClientBuilder(ApacheHttpClient.builder())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .region(Region.of("garage"))
                .endpointOverride(URI.create("http://" + GARAGE.getHost() + ":" + GARAGE.getMappedPort(3900)))
                .forcePathStyle(true)
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .build();
    }

    @Override
    protected ClusterBlobStore blobs() {
        return new S3BlobStore(client(), BUCKET, "test-" + UUID.randomUUID() + "/"); // a fresh prefix: an empty store
    }

    @Test
    void listsPastOnePage() throws Exception {
        ClusterBlobStore blobs = blobs();
        for (int i = 0; i < 1_050; i++) { // a ListObjectsV2 page holds at most 1,000 keys
            blobs.put("jobs/p/builds/" + i + "/build.xml", new ByteArrayInputStream(new byte[] {1}), 1);
        }
        assertEquals(1_050, blobs.list("jobs/p").size());
        blobs.delete("jobs/p"); // DeleteObjects takes at most 1,000 keys
        assertEquals(0, blobs.list("jobs/p").size());
    }

    @Test
    void uploadsLargeObjectsInParts() throws Exception {
        // a small threshold and parts (5 MiB is the smallest part S3 takes, but for the last one)
        S3BlobStore blobs = new S3BlobStore(client(), BUCKET, "test-" + UUID.randomUUID() + "/", 6 * 1024 * 1024, 5 * 1024 * 1024);
        byte[] big = new byte[12 * 1024 * 1024 + 7];
        new Random(42).nextBytes(big);
        blobs.put("jobs/p/builds/1/files.tgz", new ByteArrayInputStream(big), big.length);
        assertEquals(big.length, blobs.size("jobs/p/builds/1/files.tgz"));
        try (InputStream in = blobs.get("jobs/p/builds/1/files.tgz")) {
            assertArrayEquals(big, in.readAllBytes());
        }
        blobs.move("jobs/p", "jobs/q"); // too large for one CopyObject at real thresholds: copied in parts too
        try (InputStream in = blobs.get("jobs/q/builds/1/files.tgz")) {
            assertArrayEquals(big, in.readAllBytes());
        }
    }
}
