package io.jenkins.plugins.cluster.s3;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import io.jenkins.plugins.cluster.spi.ClusterMember;
import java.net.URI;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.persistence.BlobStore;
import jenkins.persistence.BlobStoreProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

/**
 * Configures an {@link S3BlobStore} from the environment:
 * <ul>
 * <li>{@code CLUSTER_S3_BUCKET}: the bucket (required; nothing is configured without it)
 * <li>{@code CLUSTER_S3_PREFIX}: a prefix for every key, such as {@code jenkins/} (default none)
 * <li>{@code CLUSTER_S3_REGION}: the region (default the SDK's default region provider, else {@code us-east-1})
 * <li>{@code CLUSTER_S3_ENDPOINT}: the endpoint of an S3-compatible store, such as {@code http://garage:3900}
 * <li>{@code CLUSTER_S3_PATH_STYLE}: {@code true} for path-style requests (most S3-compatible stores)
 * </ul>
 * System properties {@code io.jenkins.plugins.cluster.s3.bucket}, {@code .prefix}, {@code .region}, {@code .endpoint} and
 * {@code .pathStyle} take precedence. Credentials come from the SDK's default chain (environment variables, a profile,
 * a web identity token such as IRSA, or instance metadata).
 */
@Extension
public class S3BlobStoreProvider implements BlobStoreProvider {

    private static final Logger LOGGER = Logger.getLogger(S3BlobStoreProvider.class.getName());

    @CheckForNull
    @Override
    public BlobStore create() {
        String bucket = setting("bucket", "CLUSTER_S3_BUCKET");
        if (bucket == null) {
            return null;
        }
        String prefix = setting("prefix", "CLUSTER_S3_PREFIX");
        S3Client s3 = client(setting("region", "CLUSTER_S3_REGION"), setting("endpoint", "CLUSTER_S3_ENDPOINT"),
                Boolean.parseBoolean(setting("pathStyle", "CLUSTER_S3_PATH_STYLE")));
        S3BlobStore store = new S3BlobStore(s3, bucket, prefix == null ? "" : prefix);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(ClusterMember.STARTUP_WAIT_SECONDS);
        long delay = 1000;
        while (true) {
            try {
                s3.headBucket(b -> b.bucket(bucket)); // reachable, and the bucket exists
                return store;
            } catch (SdkException e) {
                if (System.nanoTime() > deadline) {
                    // as for the persistence store: refuse to run with history silently unshared
                    LOGGER.log(Level.SEVERE, "Cannot reach the S3 bucket " + bucket, e);
                    throw new IllegalStateException(e);
                }
                LOGGER.log(Level.WARNING, "Waiting for the S3 bucket " + bucket + ": " + e.getMessage());
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException x) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(x);
                }
                delay = Math.min(delay * 2, 10_000);
            }
        }
    }

    /** An S3 client; the HTTP client is set, not looked up, as service discovery does not see plugin class loaders. */
    static S3Client client(@CheckForNull String region, @CheckForNull String endpoint, boolean pathStyle) {
        S3ClientBuilder b = S3Client.builder()
                .httpClientBuilder(ApacheHttpClient.builder())
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .forcePathStyle(pathStyle);
        if (region != null) {
            b.region(Region.of(region));
        } else if (endpoint != null) {
            b.region(Region.US_EAST_1); // S3-compatible stores mostly ignore it, but the SDK needs one
        }
        if (endpoint != null) {
            b.endpointOverride(URI.create(endpoint))
                    // checksums only where S3 requires them: not every S3-compatible store accepts the newer defaults
                    .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                    .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED);
        }
        return b.build();
    }

    @CheckForNull
    private static String setting(String property, String env) {
        String v = System.getProperty(S3BlobStoreProvider.class.getPackageName() + "." + property);
        if (v == null) {
            v = System.getenv(env);
        }
        return v == null || v.isEmpty() ? null : v;
    }
}
