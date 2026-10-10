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

    public AzureBlobStore(@NonNull String connectionString, @NonNull String containerName) {
        // the SDK discovers its HTTP client through the context class loader
        this.container = withPluginClassLoader(() -> new BlobServiceClientBuilder()
                .connectionString(connectionString)
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
                try (InputStream in = Files.newInputStream(tmp)) {
                    put(target, in, Files.size(tmp));
                }
                container.getBlobClient(key).deleteIfExists();
            } catch (RuntimeException e) {
                throw wrap("move", key, e);
            } finally {
                Files.deleteIfExists(tmp);
            }
        }
    }
}
