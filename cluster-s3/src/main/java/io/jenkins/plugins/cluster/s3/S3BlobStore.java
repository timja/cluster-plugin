package io.jenkins.plugins.cluster.s3;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.plugins.cluster.spi.ChunkedLogBlobs;
import io.jenkins.plugins.cluster.spi.ClusterBlobStore;
import io.jenkins.plugins.cluster.spi.LogBlobs;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * A {@link ClusterBlobStore} in an S3 bucket (Amazon S3, or a store speaking its API: MinIO, Garage, Ceph, R2, …), keys
 * below an optional prefix. Objects cannot be appended to, so logs are {@link ChunkedLogBlobs}.
 */
public class S3BlobStore implements ClusterBlobStore {

    /** Objects larger than this are uploaded in parts (a single PUT takes at most 5 GiB). */
    static final long MULTIPART_THRESHOLD = Long.getLong(S3BlobStore.class.getName() + ".multipartThreshold", 64L * 1024 * 1024);

    static final int PART_SIZE = Integer.getInteger(S3BlobStore.class.getName() + ".partSize", 16 * 1024 * 1024);

    private final S3Client s3;
    private final String bucket;
    private final String prefix;
    private final long multipartThreshold;
    private final int partSize;
    private final LogBlobs logs = new ChunkedLogBlobs(this);

    /**
     * @param prefix prepended to every key, such as {@code jenkins/} (empty for the bucket's root)
     */
    public S3BlobStore(@NonNull S3Client s3, @NonNull String bucket, @NonNull String prefix) {
        this(s3, bucket, prefix, MULTIPART_THRESHOLD, PART_SIZE);
    }

    S3BlobStore(@NonNull S3Client s3, @NonNull String bucket, @NonNull String prefix, long multipartThreshold, int partSize) {
        this.s3 = s3;
        this.bucket = bucket;
        this.prefix = prefix;
        this.multipartThreshold = multipartThreshold;
        this.partSize = partSize;
    }

    private String object(String key) {
        return prefix + key;
    }

    private static IOException wrap(String what, String key, SdkException e) {
        return new IOException("Failed to " + what + " " + key, e);
    }

    private static boolean notFound(SdkException e) {
        return e instanceof NoSuchKeyException || e instanceof S3Exception s && s.statusCode() == 404;
    }

    @Override
    public void put(@NonNull String key, @NonNull InputStream content, long length) throws IOException {
        try {
            if (length <= multipartThreshold) {
                s3.putObject(b -> b.bucket(bucket).key(object(key)), RequestBody.fromInputStream(content, length));
            } else {
                putInParts(key, content, length);
            }
        } catch (SdkException e) {
            throw wrap("upload", key, e);
        }
    }

    private void putInParts(String key, InputStream content, long length) throws IOException {
        String upload = s3.createMultipartUpload(b -> b.bucket(bucket).key(object(key))).uploadId();
        try {
            List<CompletedPart> parts = new ArrayList<>();
            byte[] buf = new byte[partSize];
            long sent = 0;
            for (int number = 1; sent < length; number++) {
                int n = content.readNBytes(buf, 0, (int) Math.min(buf.length, length - sent));
                if (n == 0) {
                    throw new IOException("Stream of " + key + " ended at " + sent + " of " + length + " bytes");
                }
                int part = number;
                String etag = s3.uploadPart(b -> b.bucket(bucket).key(object(key)).uploadId(upload).partNumber(part),
                        RequestBody.fromBytes(java.util.Arrays.copyOf(buf, n))).eTag();
                parts.add(CompletedPart.builder().partNumber(part).eTag(etag).build());
                sent += n;
            }
            s3.completeMultipartUpload(b -> b.bucket(bucket).key(object(key)).uploadId(upload)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(parts).build()));
        } catch (IOException | SdkException e) {
            try {
                s3.abortMultipartUpload(b -> b.bucket(bucket).key(object(key)).uploadId(upload));
            } catch (SdkException x) {
                e.addSuppressed(x);
            }
            throw e;
        }
    }

    @CheckForNull
    @Override
    public InputStream get(@NonNull String key) throws IOException {
        try {
            return s3.getObject(b -> b.bucket(bucket).key(object(key)));
        } catch (SdkException e) {
            if (notFound(e)) {
                return null;
            }
            throw wrap("download", key, e);
        }
    }

    @Override
    public boolean download(@NonNull String key, @NonNull OutputStream out) throws IOException {
        try (InputStream in = get(key)) {
            if (in == null) {
                return false;
            }
            in.transferTo(out);
            return true;
        }
    }

    @Override
    public long size(@NonNull String key) throws IOException {
        try {
            return s3.headObject(b -> b.bucket(bucket).key(object(key))).contentLength();
        } catch (SdkException e) {
            if (notFound(e)) {
                return 0;
            }
            throw wrap("inspect", key, e);
        }
    }

    @NonNull
    @Override
    public List<String> list(@NonNull String key) throws IOException {
        List<String> keys = new ArrayList<>();
        try {
            for (S3Object o : s3.listObjectsV2Paginator(b -> b.bucket(bucket).prefix(object(key))).contents()) {
                String k = o.key().substring(prefix.length());
                if (k.equals(key) || k.startsWith(key + "/")) {
                    keys.add(k);
                }
            }
        } catch (SdkException e) {
            throw wrap("list", key, e);
        }
        return keys;
    }

    @Override
    public void delete(@NonNull String key) throws IOException {
        List<String> keys = list(key);
        try {
            for (int i = 0; i < keys.size(); i += 1000) { // at most 1,000 keys per request
                List<ObjectIdentifier> batch = keys.subList(i, Math.min(keys.size(), i + 1000)).stream()
                        .map(k -> ObjectIdentifier.builder().key(object(k)).build())
                        .toList();
                s3.deleteObjects(b -> b.bucket(bucket).delete(Delete.builder().objects(batch).quiet(true).build()));
            }
        } catch (SdkException e) {
            throw wrap("delete", key, e);
        }
    }

    @Override
    public void move(@NonNull String fromPrefix, @NonNull String toPrefix) throws IOException {
        // copy then delete each object, so a repeated move after a partial failure finishes the job
        for (String key : list(fromPrefix)) {
            String target = toPrefix + key.substring(fromPrefix.length());
            try {
                if (size(key) > multipartThreshold) {
                    try (InputStream in = get(key)) { // a single copy takes at most 5 GiB
                        if (in != null) {
                            putInParts(target, in, size(key));
                        }
                    }
                } else {
                    s3.copyObject(b -> b.sourceBucket(bucket).sourceKey(object(key)).destinationBucket(bucket).destinationKey(object(target)));
                }
                s3.deleteObject(b -> b.bucket(bucket).key(object(key)));
            } catch (SdkException e) {
                if (!notFound(e)) {
                    throw wrap("move", key, e);
                }
            }
        }
    }

    @NonNull
    @Override
    public LogBlobs logs() {
        return logs;
    }
}
