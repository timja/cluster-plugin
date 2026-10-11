package io.jenkins.plugins.cnj.store;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.AppendBlobRequestConditions;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.BlobRange;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.models.ListBlobsOptions;
import com.azure.storage.blob.specialized.AppendBlobClient;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import jenkins.persistence.BlobStore;

/**
 * A {@link BlobStore} in one Azure Blob Storage container, which also holds append blobs for streamed logs.
 * Keys are used as blob names unchanged.
 */
public class AzureBlobStore implements BlobStore {

    /** Largest block an append blob accepts in one call. */
    static final int MAX_APPEND_BLOCK = 4 * 1024 * 1024;

    private final BlobContainerClient container;

    /** Per try of one blob request (large snapshots and artifacts included). */
    static final int TRY_TIMEOUT_SECONDS = Integer.getInteger(AzureBlobStore.class.getName() + ".tryTimeoutSeconds", 120);

    public AzureBlobStore(@NonNull String connectionString, @NonNull String containerName) {
        // the SDK discovers its HTTP client through the context class loader
        this.container = withPluginClassLoader(() -> new BlobServiceClientBuilder()
                .connectionString(connectionString)
                // bounded tries (Target 14): a stalled blob store fails calls, retried by their callers, instead of
                // hanging the threads that ship logs and save points
                .retryOptions(new com.azure.storage.common.policy.RequestRetryOptions(
                        com.azure.storage.common.policy.RetryPolicyType.EXPONENTIAL, 3, TRY_TIMEOUT_SECONDS, 500L, 4000L, null))
                .clientOptions(new com.azure.core.util.HttpClientOptions()
                        .setConnectTimeout(java.time.Duration.ofSeconds(10))
                        .setResponseTimeout(java.time.Duration.ofSeconds(TRY_TIMEOUT_SECONDS))
                        .setReadTimeout(java.time.Duration.ofSeconds(TRY_TIMEOUT_SECONDS)))
                .buildClient()
                .getBlobContainerClient(containerName));
        withPluginClassLoader(container::createIfNotExists);
    }

    static <T> T withPluginClassLoader(Supplier<T> body) {
        Thread t = Thread.currentThread();
        ClassLoader old = t.getContextClassLoader();
        t.setContextClassLoader(AzureBlobStore.class.getClassLoader());
        try {
            return body.get();
        } finally {
            t.setContextClassLoader(old);
        }
    }

    private static IOException wrap(String what, String key, RuntimeException e) {
        return new IOException("Failed to " + what + " " + key, e);
    }

    private static boolean notFound(RuntimeException e) {
        return e instanceof BlobStorageException b && b.getStatusCode() == 404;
    }

    @Override
    public void put(@NonNull String key, @NonNull InputStream content, long length) throws IOException {
        try {
            container.getBlobClient(key).upload(content, length, true);
        } catch (RuntimeException e) {
            throw wrap("upload", key, e);
        }
    }

    @CheckForNull
    @Override
    public InputStream get(@NonNull String key) throws IOException {
        try {
            return container.getBlobClient(key).openInputStream();
        } catch (RuntimeException e) {
            if (notFound(e)) {
                return null;
            }
            throw wrap("download", key, e);
        }
    }

    /**
     * Copies a blob to a stream.
     *
     * @return false if there is no such blob
     */
    public boolean download(@NonNull String key, @NonNull OutputStream out) throws IOException {
        try {
            container.getBlobClient(key).downloadStream(out);
            return true;
        } catch (RuntimeException e) {
            if (notFound(e)) {
                return false;
            }
            throw wrap("download", key, e);
        }
    }

    /**
     * The size of a blob, or 0 if there is no such blob.
     */
    public long size(@NonNull String key) throws IOException {
        try {
            return container.getBlobClient(key).getProperties().getBlobSize();
        } catch (RuntimeException e) {
            if (notFound(e)) {
                return 0;
            }
            throw wrap("inspect", key, e);
        }
    }

    /**
     * Reads a blob from an offset to its current end, as for following a log which is still being appended to.
     *
     * @return the bytes, empty if there are none beyond {@code offset}, or null if there is no such blob
     */
    @CheckForNull
    public byte[] readFrom(@NonNull String key, long offset) throws IOException {
        BlobClient blob = container.getBlobClient(key);
        try {
            long size = blob.getProperties().getBlobSize();
            if (offset >= size) {
                return new byte[0];
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            blob.downloadStreamWithResponse(out, new BlobRange(offset, size - offset), null, null, false, null, null);
            return out.toByteArray();
        } catch (RuntimeException e) {
            if (notFound(e)) {
                return null;
            }
            throw wrap("read", key, e);
        }
    }

    /**
     * Appends to an append blob at a known position, creating it first if needed.
     *
     * @param position the current length of the blob as this writer knows it
     * @return false if the blob has a different length, i.e. someone else appended to it
     */
    public boolean appendAt(@NonNull String key, @NonNull byte[] bytes, int off, int len, long position)
            throws IOException {
        AppendBlobClient blob = container.getBlobClient(key).getAppendBlobClient();
        try {
            if (position == 0 && !blob.exists()) {
                blob.createIfNotExists();
            }
            for (int pos = off; pos < off + len; pos += MAX_APPEND_BLOCK) {
                int n = Math.min(MAX_APPEND_BLOCK, off + len - pos);
                blob.appendBlockWithResponse(
                        new ByteArrayInputStream(bytes, pos, n),
                        n,
                        null,
                        new AppendBlobRequestConditions().setAppendPosition(position + (pos - off)),
                        null,
                        null);
            }
            return true;
        } catch (RuntimeException e) {
            if (e instanceof BlobStorageException b && (b.getStatusCode() == 412 || b.getStatusCode() == 404)) {
                return false;
            }
            throw wrap("append to", key, e);
        }
    }

    /**
     * Appends to an append blob, creating it first if needed.
     */
    public void append(@NonNull String key, @NonNull byte[] bytes, int off, int len) throws IOException {
        AppendBlobClient blob = container.getBlobClient(key).getAppendBlobClient();
        try {
            if (!blob.exists()) {
                blob.createIfNotExists();
            }
            for (int pos = off; pos < off + len; pos += MAX_APPEND_BLOCK) {
                int n = Math.min(MAX_APPEND_BLOCK, off + len - pos);
                blob.appendBlock(new ByteArrayInputStream(bytes, pos, n), n);
            }
        } catch (RuntimeException e) {
            throw wrap("append to", key, e);
        }
    }

    // ------------------------------------------------------------------ segmented append blobs (Target 14)

    /**
     * Most blocks appended to one segment of a log before the next segment starts. An append blob takes at most
     * 50,000 blocks: a build logging steadily for half a day reaches that (one block per shipment).
     */
    static final int MAX_SEGMENT_BLOCKS = Integer.getInteger(AzureBlobStore.class.getName() + ".maxSegmentBlocks", 49_000);

    /** The blob holding segment {@code i} of a segmented blob: {@code base}, then {@code base.1}, {@code base.2}, … */
    static String segment(String base, int i) {
        return i == 0 ? base : base + "." + i;
    }

    /** One segment of a segmented blob: its index, size and committed block count. */
    record Segment(int index, String key, long size, int blocks) {}

    /** The segments of a segmented blob, in order (empty if there is none). */
    @NonNull
    List<Segment> segments(@NonNull String base) throws IOException {
        java.util.TreeMap<Integer, String> names = new java.util.TreeMap<>();
        try {
            for (BlobItem item : container.listBlobs(new ListBlobsOptions().setPrefix(base), null)) {
                String name = item.getName();
                if (name.equals(base)) {
                    names.put(0, name);
                } else if (name.startsWith(base + ".") && name.substring(base.length() + 1).matches("[0-9]+")) {
                    names.put(Integer.parseInt(name.substring(base.length() + 1)), name);
                }
            }
            List<Segment> result = new ArrayList<>();
            for (Map.Entry<Integer, String> e : names.entrySet()) {
                com.azure.storage.blob.models.BlobProperties props = container.getBlobClient(e.getValue()).getProperties();
                Integer blocks = props.getCommittedBlockCount();
                result.add(new Segment(e.getKey(), e.getValue(), props.getBlobSize(), blocks == null ? 0 : blocks));
            }
            return result;
        } catch (RuntimeException e) {
            if (notFound(e)) {
                return List.of(); // deleted while being listed
            }
            throw wrap("list segments of", base, e);
        }
    }

    /** The total size of a segmented blob, or 0 if there is none. */
    long segmentedSize(@NonNull String base) throws IOException {
        long size = 0;
        for (Segment s : segments(base)) {
            size += s.size();
        }
        return size;
    }

    /** Copies a whole segmented blob to a stream; false if there is none. */
    boolean downloadSegmented(@NonNull String base, @NonNull OutputStream out) throws IOException {
        List<Segment> segments = segments(base);
        if (segments.isEmpty()) {
            return false;
        }
        for (Segment s : segments) {
            download(s.key(), out);
        }
        return true;
    }

    /** Reads a segmented blob from an offset to its current end; null if there is none. */
    @CheckForNull
    byte[] readSegmentedFrom(@NonNull String base, long offset) throws IOException {
        List<Segment> segments = segments(base);
        if (segments.isEmpty()) {
            return null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long start = 0;
        for (Segment s : segments) {
            if (offset < start + s.size()) {
                byte[] part = readFrom(s.key(), Math.max(0, offset - start));
                if (part != null) {
                    out.write(part, 0, part.length);
                }
            }
            start += s.size();
        }
        return out.toByteArray();
    }

    /** Deletes every segment of a segmented blob (not other blobs sharing its name as a prefix). */
    void deleteSegmented(@NonNull String base) throws IOException {
        for (Segment s : segments(base)) {
            try {
                container.getBlobClient(s.key()).deleteIfExists();
            } catch (RuntimeException e) {
                throw wrap("delete", s.key(), e);
            }
        }
    }

    /**
     * Names of the blob with this key and every blob below it, for cleanup and diagnostics.
     */
    @NonNull
    public List<String> list(@NonNull String prefix) throws IOException {
        List<String> keys = new ArrayList<>();
        try {
            for (BlobItem item : container.listBlobs(new ListBlobsOptions().setPrefix(prefix), null)) {
                String name = item.getName();
                if (name.equals(prefix) || name.startsWith(prefix + "/")) {
                    keys.add(name);
                }
            }
        } catch (RuntimeException e) {
            throw wrap("list", prefix, e);
        }
        return keys;
    }

    @Override
    public void delete(@NonNull String prefix) throws IOException {
        for (String key : list(prefix)) {
            try {
                container.getBlobClient(key).deleteIfExists();
            } catch (RuntimeException e) {
                throw wrap("delete", key, e);
            }
        }
    }

    @Override
    public void move(@NonNull String fromPrefix, @NonNull String toPrefix) throws IOException {
        // copy then delete each blob, so a repeated move after a partial failure finishes the job
        for (String key : list(fromPrefix)) {
            String target = toPrefix + key.substring(fromPrefix.length());
            Path tmp = Files.createTempFile("blob-move", null);
            try {
                try (OutputStream out = Files.newOutputStream(tmp)) {
                    if (!download(key, out)) {
                        continue;
                    }
                }
                if (isAppendBlob(key)) {
                    // a running build's log stays appendable under its new name (Target 14)
                    copyAsAppendBlob(tmp, target);
                } else {
                    try (InputStream in = Files.newInputStream(tmp)) {
                        put(target, in, Files.size(tmp));
                    }
                }
                container.getBlobClient(key).deleteIfExists();
            } catch (RuntimeException e) {
                throw wrap("move", key, e);
            } finally {
                Files.deleteIfExists(tmp);
            }
        }
    }

    private boolean isAppendBlob(String key) {
        try {
            return container.getBlobClient(key).getProperties().getBlobType() == com.azure.storage.blob.models.BlobType.APPEND_BLOB;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void copyAsAppendBlob(Path file, String target) throws IOException {
        AppendBlobClient blob = container.getBlobClient(target).getAppendBlobClient();
        blob.deleteIfExists();
        blob.create();
        byte[] buffer = new byte[MAX_APPEND_BLOCK];
        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.readNBytes(buffer, 0, buffer.length)) > 0) {
                blob.appendBlock(new ByteArrayInputStream(buffer, 0, n), n);
            }
        }
    }
}
