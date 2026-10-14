package io.jenkins.plugins.cnj.store;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import jenkins.persistence.BlobStore;
import jenkins.persistence.PersistenceStores;

/**
 * What the cluster needs of a {@link BlobStore} beyond the core SPI: implemented by the blob store a backend plugin
 * provides (such as the Azure Blob Storage or the S3 one).
 */
public interface ClusterBlobStore extends BlobStore {

    /** The active blob store, if it is a cluster blob store. */
    @CheckForNull
    static ClusterBlobStore get() {
        return PersistenceStores.getBlobStore() instanceof ClusterBlobStore b ? b : null;
    }

    /** The keys of the blob with this key and of every blob below it ({@code prefix/…}), in no particular order. */
    @NonNull
    List<String> list(@NonNull String prefix) throws IOException;

    /**
     * Copies a blob to a stream.
     *
     * @return false if there is no such blob
     */
    boolean download(@NonNull String key, @NonNull OutputStream out) throws IOException;

    /** The size of a blob, or 0 if there is no such blob. */
    long size(@NonNull String key) throws IOException;

    /**
     * Logs: blobs appended to while builds run. A backend without appendable blobs returns
     * {@link ChunkedLogBlobs}, which keeps each append as its own blob.
     */
    @NonNull
    LogBlobs logs();
}
