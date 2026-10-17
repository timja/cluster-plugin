package io.jenkins.plugins.cluster.azure;

import io.jenkins.plugins.cluster.spi.ClusterBlobStore;
import io.jenkins.plugins.cluster.spi.ClusterBlobStoreContract;
import java.util.UUID;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** {@link AzureBlobStore} against Azurite: what every cluster blob store must do, with logs as segmented append blobs. */
@Testcontainers
class AzureClusterBlobStoreTest extends ClusterBlobStoreContract {

    @Container
    static final GenericContainer<?> AZURITE = new GenericContainer<>("mcr.microsoft.com/azure-storage/azurite:3.34.0")
            .withCommand("azurite-blob", "--blobHost", "0.0.0.0", "--skipApiVersionCheck")
            .withExposedPorts(10000);

    private final java.util.Map<ClusterBlobStore, String> containers = new java.util.IdentityHashMap<>();

    private static String connectionString() {
        return "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;"
                + "AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==;"
                + "BlobEndpoint=http://" + AZURITE.getHost() + ":" + AZURITE.getMappedPort(10000) + "/devstoreaccount1;";
    }

    @Override
    protected ClusterBlobStore blobs() {
        String container = "test-" + UUID.randomUUID();
        ClusterBlobStore blobs = new AzureBlobStore(connectionString(), container);
        containers.put(blobs, container);
        return blobs;
    }

    @Override
    protected ClusterBlobStore another(ClusterBlobStore blobs) {
        return new AzureBlobStore(connectionString(), containers.get(blobs));
    }
}
