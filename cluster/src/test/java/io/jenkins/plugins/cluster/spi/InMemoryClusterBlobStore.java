package io.jenkins.plugins.cluster.spi;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

/** A blob store in memory, with no appendable blobs (logs are {@link ChunkedLogBlobs}), as S3 is. */
public class InMemoryClusterBlobStore implements ClusterBlobStore {

    final Map<String, byte[]> blobs = new ConcurrentSkipListMap<>();
    private final LogBlobs logs = new ChunkedLogBlobs(this);

    @Override
    public void put(@NonNull String key, @NonNull InputStream content, long length) throws IOException {
        blobs.put(key, content.readAllBytes());
    }

    @Override
    public InputStream get(@NonNull String key) {
        byte[] b = blobs.get(key);
        return b == null ? null : new ByteArrayInputStream(b);
    }

    @Override
    public void delete(@NonNull String prefix) {
        for (String key : list(prefix)) {
            blobs.remove(key);
        }
    }

    @Override
    public void move(@NonNull String fromPrefix, @NonNull String toPrefix) {
        for (String key : list(fromPrefix)) {
            blobs.put(toPrefix + key.substring(fromPrefix.length()), blobs.remove(key));
        }
    }

    @NonNull
    @Override
    public List<String> list(@NonNull String prefix) {
        List<String> keys = new ArrayList<>();
        for (String key : blobs.keySet()) {
            if (key.equals(prefix) || key.startsWith(prefix + "/")) {
                keys.add(key);
            }
        }
        return keys;
    }

    @Override
    public boolean download(@NonNull String key, @NonNull OutputStream out) throws IOException {
        byte[] b = blobs.get(key);
        if (b == null) {
            return false;
        }
        out.write(b);
        return true;
    }

    @Override
    public long size(@NonNull String key) {
        byte[] b = blobs.get(key);
        return b == null ? 0 : b.length;
    }

    @NonNull
    @Override
    public LogBlobs logs() {
        return logs;
    }
}
