package io.jenkins.plugins.cluster;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The items a URL path (below the context path) goes through, read as Jenkins resolves it:
 * {@code [/view/v…]/job/a[/job/b…][/rest…]}.
 *
 * <p>One reading for every filter which acts on such a path: each segment is percent-decoded, a {@code +} staying a plus
 * (as Stapler reads a path). A path a segment of which cannot be decoded, or decodes to {@code .} or {@code ..} or to
 * something holding a {@code /} or {@code \}, does not read as it is written, and is never read: it is answered by the
 * controller it reached, as it would be without a cluster.
 *
 * @param names the item names, outermost first
 * @param before the path up to the first segment after the items, as written (no trailing {@code /})
 * @param rest the segments after the items, as written
 */
record JobPath(
        @NonNull List<String> names,
        @NonNull String before,
        @NonNull List<String> rest) {

    /**
     * The decoded segments of a path, the empty ones included, or null if it does not read as it is written (see above).
     */
    @CheckForNull
    static List<String> segments(@NonNull String path) {
        List<String> decoded = new ArrayList<>();
        for (String segment : path.split("/", -1)) {
            String d;
            try {
                d = URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return null; // not a URL at all
            }
            if (d.equals(".") || d.equals("..") || d.contains("/") || d.contains("\\")) {
                return null;
            }
            decoded.add(d);
        }
        return decoded;
    }

    /** The items a path goes through, or null if it names none, or does not read as it is written. */
    @CheckForNull
    static JobPath parse(@NonNull String path) {
        List<String> decoded = segments(path);
        if (decoded == null) {
            return null;
        }
        String[] raw = path.split("/", -1);
        int i = 0;
        while (i < raw.length && raw[i].isEmpty()) {
            i++;
        }
        while (i + 1 < raw.length && raw[i].equals("view")) {
            i += 2;
        }
        List<String> names = new ArrayList<>();
        while (i + 1 < raw.length && raw[i].equals("job") && !decoded.get(i + 1).isEmpty()) {
            names.add(decoded.get(i + 1));
            i += 2;
        }
        if (names.isEmpty()) {
            return null;
        }
        return new JobPath(
                List.copyOf(names),
                String.join("/", Arrays.copyOfRange(raw, 0, i)),
                List.of(Arrays.copyOfRange(raw, i, raw.length)));
    }

    /** The full name of the innermost item: {@code /job/f/job/p/…} gives {@code f/p}. */
    @NonNull
    String fullName() {
        return String.join("/", names);
    }

    /** The full names of the items, outermost first: {@code /job/f/job/p/…} gives {@code f, f/p}. */
    @NonNull
    List<String> fullNames() {
        List<String> all = new ArrayList<>();
        for (int i = 1; i <= names.size(); i++) {
            all.add(String.join("/", names.subList(0, i)));
        }
        return all;
    }

    /** The first segment after the items, as written, or null if there is none. */
    @CheckForNull
    String next() {
        return rest.isEmpty() ? null : rest.get(0);
    }

    /** The path after the first segment after the items, as written (no leading {@code /}; empty if there is none). */
    @NonNull
    String afterNext() {
        return rest.size() <= 1 ? "" : String.join("/", rest.subList(1, rest.size()));
    }
}
