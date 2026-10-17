package io.jenkins.plugins.cluster.files;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.TaskListener;
import hudson.util.AtomicFileWriter;
import io.jenkins.plugins.cluster.config.XmlMerge;
import io.jenkins.plugins.cluster.spi.ClusterStore;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.persistence.ConflictException;
import jenkins.persistence.PersistenceStore;
import jenkins.persistence.PersistenceStores;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Writes local copies of stored files ({@code XmlFile.getFile()}) that were changed directly on disk back to the store,
 * every minute.
 *
 * <p>Plugins that write a configuration file directly ({@code getFile()} plus their own writer) change only the local
 * copy, which nothing reads back. Such a change is written to the store here, merged ({@link XmlMerge}) with what was
 * stored meanwhile against the content the copy was made from; if the same part changed elsewhere too, the stored
 * version stands (never last writer wins) and the copy is left as it is. Other controllers then apply it as any other
 * change. The object in memory here, if any, is not reloaded: as on a single controller, a file written behind an
 * object's back takes effect when it is next loaded.
 */
@Restricted(NoExternalUse.class)
@Extension
public final class MaterializedWriteBack extends AsyncPeriodicWork {

    private static final Logger LOGGER = Logger.getLogger(MaterializedWriteBack.class.getName());

    /** A copy as written back here: what core's record of it was replaced with. */
    private record Copy(File file, byte[] content, long version, long modified) {}

    /** By key: copies written back here, newer than core's record of them. */
    private static final Map<String, Copy> written = new ConcurrentHashMap<>();

    public MaterializedWriteBack() {
        super("Files changed directly on disk");
    }

    @Override
    public long getRecurrencePeriod() {
        return TimeUnit.SECONDS.toMillis(Long.getLong(MaterializedWriteBack.class.getName() + ".periodSeconds", 60));
    }

    @Override
    protected void execute(TaskListener listener) {
        ClusterStore store = ClusterStore.get();
        if (store == null) {
            return;
        }
        for (PersistenceStores.MaterializedCopy c : PersistenceStores.materializedCopies()) {
            Copy mine = written.get(c.key());
            Copy copy = mine != null && mine.file().equals(c.file()) && mine.version() > c.version()
                    ? mine
                    : new Copy(c.file(), c.content(), c.version(), c.modified());
            writeBack(store, c.key(), copy);
        }
    }

    /** What {@link #writeBack} did. */
    enum Outcome {
        UNCHANGED,
        WRITTEN,
        STALE,
        REFUSED,
        FAILED
    }

    static Outcome writeBack(PersistenceStore store, String key, Copy c) {
        File f = c.file();
        if (!f.isFile() || f.lastModified() == c.modified() && f.length() == c.content().length) {
            return Outcome.UNCHANGED;
        }
        try {
            byte[] mine = Files.readAllBytes(f.toPath());
            if (Arrays.equals(mine, c.content())) {
                return Outcome.UNCHANGED;
            }
            for (int attempt = 0; attempt < 5; attempt++) {
                PersistenceStore.Entry theirs = store.read(key);
                if (theirs == null) {
                    return Outcome.STALE; // deleted meanwhile: the copy is stale
                }
                byte[] content = mine;
                if (theirs.version() != c.version()) {
                    XmlMerge.Result merged = XmlMerge.merge(c.content(), mine, theirs.content());
                    if (merged == null || merged.conflicts() > 0) {
                        LOGGER.warning(
                                () -> key
                                        + " was changed both on disk here and elsewhere, in the same part; the stored version stands");
                        return Outcome.REFUSED;
                    }
                    content = merged.merged();
                }
                long version;
                try {
                    version = store.write(key, content, theirs.version());
                } catch (ConflictException again) {
                    continue; // changed again meanwhile: merge against that
                }
                if (!Arrays.equals(content, mine)) {
                    replace(f, content); // merged with a change made elsewhere: the copy has both
                }
                written.put(key, new Copy(f, content, version, f.lastModified()));
                LOGGER.info(() -> "Wrote " + key + " back to the store: it was changed directly on disk here");
                return Outcome.WRITTEN;
            }
            throw new IOException(key + " kept changing elsewhere while being written back");
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not write " + f + " back to the store; trying again later", e);
            return Outcome.FAILED;
        }
    }

    /** Replaces a file at once, so that a reader never sees it half written. */
    @SuppressFBWarnings(value = "OS_OPEN_STREAM", justification = "abort() in the finally block closes the writer")
    private static void replace(File file, byte[] content) throws IOException {
        AtomicFileWriter w = new AtomicFileWriter(file.toPath(), StandardCharsets.ISO_8859_1);
        try {
            w.write(new String(content, StandardCharsets.ISO_8859_1)); // each byte as it is
            w.commit();
        } finally {
            w.abort();
        }
    }

    @Override
    protected Level getNormalLoggingLevel() {
        return Level.FINE;
    }

    @CheckForNull
    static Copy writtenForTests(String key) {
        return written.get(key);
    }
}
