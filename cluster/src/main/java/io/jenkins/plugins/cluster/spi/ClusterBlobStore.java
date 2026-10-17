package io.jenkins.plugins.cluster.spi;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stores large, rarely changing content which the controllers of a cluster share, such as snapshots and logs of
 * builds: implemented by the blob store a backend plugin provides (such as the Azure Blob Storage or the S3 one),
 * through a {@link ClusterBlobStoreProvider}. Complements the {@link ClusterStore}, which holds small records.
 *
 * <p>Keys use the layout of {@link Keys}: everything kept about an item is below {@code items/<uuid>/}, so blobs
 * never move when an item is renamed or moved. There are no versions or change events: writers publish a blob before
 * the record which refers to it.
 */
public interface ClusterBlobStore {

    /**
     * The active blob store, or null if none is configured (or no {@link ClusterStore} is active): the first non-null
     * one of the {@link ClusterBlobStoreProvider}s, resolved once for the active store.
     */
    @CheckForNull
    static ClusterBlobStore get() {
        return BlobStores.get();
    }

    /**
     * Stores a blob, replacing any existing blob with the same key.
     *
     * @param length the number of bytes {@code content} will deliver
     */
    void put(@NonNull String key, @NonNull InputStream content, long length) throws IOException;

    /**
     * Opens a blob for reading.
     *
     * @return the content, which the caller must close, or null if there is no such blob
     */
    @CheckForNull
    InputStream get(@NonNull String key) throws IOException;

    /** Deletes the blob with this key and every blob below it ({@code prefix/…}), if any. */
    void delete(@NonNull String prefix) throws IOException;

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

    /** The sizes of the blob with this key and of every blob below it, by key, as {@link #list} and {@link #size}. */
    @NonNull
    default Map<String, Long> sizes(@NonNull String prefix) throws IOException {
        Map<String, Long> result = new HashMap<>();
        for (String key : list(prefix)) {
            result.put(key, size(key));
        }
        return result;
    }

    /**
     * Logs: blobs appended to while builds run. A backend without appendable blobs returns
     * {@link ChunkedLogBlobs}, which keeps each append as its own blob.
     */
    @NonNull
    LogBlobs logs();
}
