package io.jenkins.plugins.cnj.store;

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
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Logs over blobs which cannot be appended to (S3 and the like): each append is a blob of its own,
 * {@code <log>.d/<offset>.<length>}, and once the log is complete they are put together into one blob, {@code <log>}.
 *
 * <p>A reader takes {@code <log>} if it is there (it covers the start of the log) and then the chunks beyond its end, so
 * it reads the whole log whatever a compaction interrupted midway left: compaction writes {@code <log>} before it
 * deletes the chunks it covers.
 */
public final class ChunkedLogBlobs implements LogBlobs {

    private static final Pattern CHUNK = Pattern.compile("([0-9]{16})\\.([0-9]+)");

    private final ClusterBlobStore blobs;

    /** Where each log appended to here ends, as last appended or listed: saves listing before each append. */
    private final Map<String, Long> ends = new ConcurrentHashMap<>();

    public ChunkedLogBlobs(@NonNull ClusterBlobStore blobs) {
        this.blobs = blobs;
    }

    private static String chunks(String log) {
        return log + ".d";
    }

    private record Chunk(String key, long offset, long length) {}

    /** The chunks of a log beyond {@code from}, in order. */
    private List<Chunk> chunksFrom(String log, long from) throws IOException {
        List<Chunk> result = new ArrayList<>();
        String prefix = chunks(log) + "/";
        for (String key : blobs.list(chunks(log))) {
            if (!key.startsWith(prefix)) {
                continue;
            }
            Matcher m = CHUNK.matcher(key.substring(prefix.length()));
            if (m.matches()) {
                long offset = Long.parseLong(m.group(1));
                long length = Long.parseLong(m.group(2));
                if (offset + length > from) {
                    result.add(new Chunk(key, offset, length));
                }
            }
        }
        result.sort(java.util.Comparator.comparingLong(Chunk::offset));
        return result;
    }

    @Override
    public long size(@NonNull String log) throws IOException {
        long end = blobs.size(log);
        for (Chunk c : chunksFrom(log, end)) {
            if (c.offset() != end) {
                break; // a gap: what follows was not appended in order
            }
            end += c.length();
        }
        return end;
    }

    @Override
    public boolean appendAt(@NonNull String log, @NonNull byte[] bytes, int off, int len, long position) throws IOException {
        Long known = ends.get(log);
        long end = known != null ? known : size(log);
        if (end != position) {
            ends.remove(log);
            return false;
        }
        if (len > 0) {
            blobs.put(chunks(log) + "/" + String.format("%016d", position) + "." + len, new ByteArrayInputStream(bytes, off, len), len);
        }
        ends.put(log, position + len);
        return true;
    }

    @Override
    public boolean download(@NonNull String log, @NonNull OutputStream out) throws IOException {
        byte[] all = readFrom(log, 0);
        if (all == null) {
            return false;
        }
        out.write(all);
        return true;
    }

    @CheckForNull
    @Override
    public byte[] readFrom(@NonNull String log, long offset) throws IOException {
        ByteArrayOutputStream whole = new ByteArrayOutputStream();
        boolean found = blobs.download(log, whole);
        long end = whole.size();
        List<Chunk> rest = chunksFrom(log, end);
        if (!found && rest.isEmpty()) {
            return null;
        }
        for (Chunk c : rest) {
            if (c.offset() > end) {
                break; // a gap: never read past it
            }
            ByteArrayOutputStream part = new ByteArrayOutputStream();
            if (!blobs.download(c.key(), part)) {
                break; // compacted meanwhile: what was read so far is still a prefix of the log
            }
            byte[] b = part.toByteArray();
            int skip = (int) (end - c.offset());
            whole.write(b, skip, b.length - skip);
            end = c.offset() + b.length;
        }
        byte[] all = whole.toByteArray();
        if (offset >= all.length) {
            return new byte[0];
        }
        return java.util.Arrays.copyOfRange(all, (int) offset, all.length);
    }

    @Override
    public void delete(@NonNull String log) throws IOException {
        ends.remove(log);
        blobs.delete(chunks(log));
        blobs.delete(log);
    }

    @Override
    public void complete(@NonNull String log) throws IOException {
        ends.remove(log);
        if (chunksFrom(log, 0).isEmpty()) {
            return;
        }
        Path tmp = Files.createTempFile("log-compact", null);
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                if (!download(log, out)) {
                    return;
                }
            }
            try (InputStream in = Files.newInputStream(tmp)) {
                blobs.put(log, in, Files.size(tmp));
            }
            blobs.delete(chunks(log));
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
