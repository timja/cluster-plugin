package io.jenkins.plugins.cluster.spi;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Logs over blobs which cannot be appended to (S3 and the like): each append is a blob of its own,
 * {@code <log>.d/<offset>.e<epoch>.<writer>}, and once the log is complete they are put together into one blob,
 * {@code <log>}.
 *
 * <p>Fenced without conditional writes (which not every S3-compatible store honours): a writer that learns where a log
 * ends from the store (an adopting controller, or one appending to a log for the first time) takes an epoch above every
 * epoch the log has, and records it in {@code <log>.fence} (kept when the log is compacted or deleted, so that epochs
 * only grow). A writer still appending with an older epoch (an owner
 * that was paused long enough for another controller to adopt its build, and woke with a stale idea of where the log
 * ends) finds the newer fence before its next append and is refused; and where its last chunk raced the new writer's
 * first one, at the same offset, readers take the chunk with the higher epoch. So the log never holds two versions of
 * the same bytes.
 *
 * <p>A reader takes {@code <log>} if it is there (it covers the start of the log) and then the chain of chunks from its
 * end, each starting where the previous one ends, so it reads the whole log whatever a compaction interrupted midway
 * left: compaction writes {@code <log>} before it deletes the chunks it covers. A chunk off the
 * chain (beyond a gap, or across the end of {@code <log>}) is never read. Chunks named {@code <offset>.<length>}
 * (written before the fence) are read too, below any fenced chunk at the same offset.
 */
public final class ChunkedLogBlobs implements LogBlobs {

    private static final Pattern CHUNK = Pattern.compile("([0-9]{16})\\.(?:e([0-9]+)\\.([0-9a-f]+)|([0-9]+))");

    private final ClusterBlobStore blobs;

    /** This writer, to order chunks of the same epoch (two writers learning a log's end at once). */
    private final String writer;

    /** Where each log appended to here ends, and the epoch it is appended to with: saves listing before each append. */
    private final Map<String, Tail> tails = new ConcurrentHashMap<>();

    private record Tail(long end, Epoch epoch) {}

    /** A writer's place in a log: higher epochs (then higher writers) win. */
    record Epoch(long epoch, String writer) implements Comparable<Epoch> {
        static final Epoch NONE = new Epoch(0, "");

        @Override
        public int compareTo(Epoch o) {
            int c = Long.compare(epoch, o.epoch);
            return c != 0 ? c : writer.compareTo(o.writer);
        }

        boolean isAbove(Epoch o) {
            return compareTo(o) > 0;
        }

        @Override
        public String toString() {
            return epoch + "." + writer;
        }

        @NonNull
        static Epoch parse(@CheckForNull String s) {
            if (s != null) {
                int dot = s.indexOf('.');
                try {
                    return dot == -1
                            ? new Epoch(Long.parseLong(s.trim()), "")
                            : new Epoch(
                                    Long.parseLong(s.substring(0, dot)),
                                    s.substring(dot + 1).trim());
                } catch (NumberFormatException e) {
                    // as none
                }
            }
            return NONE;
        }
    }

    public ChunkedLogBlobs(@NonNull ClusterBlobStore blobs) {
        this(blobs, UUID.randomUUID().toString().replace("-", "").substring(0, 12));
    }

    ChunkedLogBlobs(@NonNull ClusterBlobStore blobs, @NonNull String writer) {
        this.blobs = blobs;
        this.writer = writer;
    }

    private static String chunks(String log) {
        return log + ".d";
    }

    private static String fence(String log) {
        return log + ".fence";
    }

    private static String chunk(String log, long offset, Epoch epoch) {
        return chunks(log) + "/" + String.format("%016d", offset) + ".e" + epoch.epoch() + "." + epoch.writer();
    }

    private record Chunk(String key, long offset, long length, Epoch epoch) {}

    /** What the store has of a log's chunks: the one read at each offset, and the highest epoch of any. */
    private record Listing(Map<Long, Chunk> byOffset, Epoch highest) {
        /** The chain from {@code from} (where {@code <log>} ends), in order: each starting where the last ends. */
        List<Chunk> chainFrom(long from) {
            List<Chunk> result = new ArrayList<>();
            long end = from;
            Chunk next;
            while ((next = byOffset.get(end)) != null && next.length() > 0) {
                result.add(next);
                end += next.length();
            }
            return result;
        }
    }

    private Listing list(String log) throws IOException {
        Map<Long, Chunk> byOffset = new HashMap<>();
        Epoch highest = Epoch.NONE;
        String prefix = chunks(log) + "/";
        for (Map.Entry<String, Long> e : blobs.sizes(chunks(log)).entrySet()) {
            String key = e.getKey();
            if (!key.startsWith(prefix)) {
                continue;
            }
            Matcher m = CHUNK.matcher(key.substring(prefix.length()));
            if (!m.matches()) {
                continue;
            }
            long offset = Long.parseLong(m.group(1));
            Chunk c = m.group(2) != null
                    ? new Chunk(key, offset, e.getValue(), new Epoch(Long.parseLong(m.group(2)), m.group(3)))
                    : new Chunk(key, offset, Long.parseLong(m.group(4)), Epoch.NONE);
            Chunk other = byOffset.get(offset);
            if (other == null || c.epoch().isAbove(other.epoch())) {
                byOffset.put(offset, c);
            }
            if (c.epoch().isAbove(highest)) {
                highest = c.epoch();
            }
        }
        return new Listing(byOffset, highest);
    }

    private Epoch readFence(String log) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        return blobs.download(fence(log), out) ? Epoch.parse(out.toString(StandardCharsets.UTF_8)) : Epoch.NONE;
    }

    @Override
    public long size(@NonNull String log) throws IOException {
        long end = blobs.size(log);
        for (Chunk c : list(log).chainFrom(end)) {
            end += c.length();
        }
        return end;
    }

    @Override
    public boolean appendAt(@NonNull String log, @NonNull byte[] bytes, int off, int len, long position)
            throws IOException {
        Tail t = tails.get(log);
        if (t == null || t.end() != position) {
            // learn where the log ends from the store, and fence off any writer that learnt it before
            Listing listing = list(log);
            long end = blobs.size(log);
            for (Chunk c : listing.chainFrom(end)) {
                end += c.length();
            }
            if (end != position) {
                tails.remove(log);
                return false;
            }
            Epoch fence = readFence(log);
            Epoch mine = new Epoch(Math.max(listing.highest().epoch(), fence.epoch()) + 1, writer);
            byte[] f = mine.toString().getBytes(StandardCharsets.UTF_8);
            blobs.put(fence(log), new ByteArrayInputStream(f), f.length);
            t = new Tail(end, mine);
        } else if (readFence(log).isAbove(t.epoch())) {
            tails.remove(log); // another writer has learnt where the log ends since: it owns the log now
            return false;
        }
        if (len > 0) {
            blobs.put(chunk(log, position, t.epoch()), new ByteArrayInputStream(bytes, off, len), len);
        }
        tails.put(log, new Tail(position + len, t.epoch()));
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
        List<Chunk> rest = list(log).chainFrom(whole.size());
        if (!found && rest.isEmpty()) {
            return null;
        }
        for (Chunk c : rest) {
            ByteArrayOutputStream part = new ByteArrayOutputStream();
            if (!blobs.download(c.key(), part) || part.size() != c.length()) {
                break; // compacted meanwhile: what was read so far is still a prefix of the log
            }
            part.writeTo(whole);
        }
        byte[] all = whole.toByteArray();
        if (offset >= all.length) {
            return new byte[0];
        }
        return java.util.Arrays.copyOfRange(all, (int) offset, all.length);
    }

    /** Deletes the log, but not its fence: whoever appends to it next does so above every writer it had. */
    @Override
    public void delete(@NonNull String log) throws IOException {
        tails.remove(log);
        blobs.delete(chunks(log));
        blobs.delete(log);
    }

    @Override
    public void complete(@NonNull String log) throws IOException {
        tails.remove(log);
        if (blobs.list(chunks(log)).isEmpty()) {
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
