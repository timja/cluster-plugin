package io.jenkins.plugins.cnj.store;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.Fingerprint;
import hudson.model.TaskListener;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.fingerprints.FileFingerprintStorage;
import jenkins.fingerprints.FingerprintStorage;
import jenkins.fingerprints.FingerprintStorageDescriptor;
import jenkins.fingerprints.GlobalFingerprintConfiguration;
import jenkins.persistence.ConflictException;
import jenkins.persistence.PersistenceStore;
import jenkins.persistence.PersistenceStores;
import org.jenkinsci.Symbol;

/**
 * Fingerprints in the persistence store (Target 8), so that every controller sees every usage, whichever controller
 * recorded it, and they survive the controllers.
 *
 * <p>Each fingerprint is one entry, {@code fingerprints/<md5>.xml}, in the XML {@link FileFingerprintStorage} uses.
 * Several controllers update one fingerprint concurrently (builds on each record the same file), so a save is a
 * three-way merge of usages against the version this controller last read, written with compare-and-set and retried on
 * conflict: a usage added elsewhere is kept, and a usage this controller trimmed stays trimmed.
 */
@Symbol("storeFingerprintStorage")
@Extension
public final class StoreFingerprintStorage extends FingerprintStorage {

    private static final Logger LOGGER = Logger.getLogger(StoreFingerprintStorage.class.getName());
    static final String PREFIX = "fingerprints";
    private static final int MAX_ATTEMPTS = 20;
    private static final String HEADER = "<?xml version='1.1' encoding='UTF-8'?>\n";

    /** What this controller last read or wrote, per fingerprint: the base of the next merge. */
    private final Map<String, Base> bases = new ConcurrentHashMap<>();

    private record Base(long version, Map<String, String> usages) {}

    /** Uses the store for fingerprints when one is active, unless a storage other than the file default was chosen. */
    @Initializer(after = InitMilestone.SYSTEM_CONFIG_ADAPTED)
    public static void select() {
        if (PersistenceStores.get() == null) {
            return;
        }
        GlobalFingerprintConfiguration config = GlobalFingerprintConfiguration.get();
        if (config.getStorage() instanceof FileFingerprintStorage) {
            config.setStorage(ExtensionList.lookupSingleton(StoreFingerprintStorage.class)); // not saved: the default
        }
    }

    @NonNull
    private static PersistenceStore store() throws IOException {
        PersistenceStore store = PersistenceStores.get();
        if (store == null) {
            throw new IOException("No persistence store");
        }
        return store;
    }

    static String key(String id) {
        return PREFIX + "/" + id + ".xml";
    }

    /** Usages as job → serialised range set, for merging and comparison. */
    private static Map<String, String> usagesOf(Fingerprint fp) {
        Map<String, String> result = new java.util.HashMap<>();
        Hashtable<String, Fingerprint.RangeSet> usages = fp.getUsages();
        if (usages != null) {
            for (Map.Entry<String, Fingerprint.RangeSet> e : usages.entrySet()) {
                result.put(e.getKey(), Fingerprint.RangeSet.ConverterImpl.serialize(e.getValue()));
            }
        }
        return result;
    }

    private static Fingerprint.RangeSet ranges(@CheckForNull String serialised) {
        return serialised == null ? new Fingerprint.RangeSet() : Fingerprint.RangeSet.fromString(serialised, true);
    }

    /**
     * The usages of a three-way merge: theirs, plus what mine added to the base, minus what mine removed from it.
     */
    static Map<String, String> merge(Map<String, String> base, Map<String, String> mine, Map<String, String> theirs) {
        Set<String> jobs = new HashSet<>(theirs.keySet());
        jobs.addAll(mine.keySet());
        jobs.addAll(base.keySet());
        Map<String, String> result = new java.util.HashMap<>();
        for (String job : jobs) {
            Fingerprint.RangeSet added = ranges(mine.get(job));
            added.removeAll(ranges(base.get(job)));
            Fingerprint.RangeSet removed = ranges(base.get(job));
            removed.removeAll(ranges(mine.get(job)));
            Fingerprint.RangeSet merged = ranges(theirs.get(job));
            merged.add(added);
            merged.removeAll(removed);
            if (!merged.isEmpty()) {
                result.put(job, Fingerprint.RangeSet.ConverterImpl.serialize(merged));
            }
        }
        return result;
    }

    private static Fingerprint parse(byte[] content) throws IOException {
        Object o = Fingerprint.getXStream().fromXML(new ByteArrayInputStream(content));
        if (!(o instanceof Fingerprint fp)) {
            throw new IOException("Not a fingerprint: " + (o == null ? null : o.getClass()));
        }
        return fp;
    }

    @Override
    public void save(Fingerprint fp) throws IOException {
        PersistenceStore store = store();
        String id = fp.getHashString();
        String key = key(id);
        for (int attempt = 1; ; attempt++) {
            PersistenceStore.Entry stored = store.read(key);
            byte[] content;
            Map<String, String> usages;
            synchronized (fp) {
                Map<String, String> mine = usagesOf(fp);
                Base base = bases.get(id);
                if (stored != null && (base == null || base.version() != stored.version())) {
                    // changed elsewhere since we read it: merge, and let the in-memory fingerprint see the result
                    Map<String, String> theirs = usagesOf(parse(stored.content()));
                    usages = merge(base == null ? Map.of() : base.usages(), mine, theirs);
                    Hashtable<String, Fingerprint.RangeSet> live = fp.getUsages();
                    if (live != null) {
                        live.clear();
                        usages.forEach((job, ranges) -> live.put(job, ranges(ranges)));
                    }
                } else {
                    usages = mine;
                }
                content = (HEADER + Fingerprint.getXStream().toXML(fp)).getBytes(StandardCharsets.UTF_8);
            }
            try {
                long version = store.write(key, content, stored == null ? PersistenceStore.ABSENT : stored.version());
                bases.put(id, new Base(version, usages));
                return;
            } catch (ConflictException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw new IOException("Could not save fingerprint " + id + " after " + attempt + " attempts", e);
                }
                LOGGER.fine(() -> "Fingerprint " + id + " changed meanwhile; merging again");
            }
        }
    }

    @CheckForNull
    @Override
    public Fingerprint load(String id) throws IOException {
        PersistenceStore.Entry stored = store().read(key(id));
        if (stored == null) {
            bases.remove(id);
            return null;
        }
        Fingerprint fp = parse(stored.content());
        bases.put(id, new Base(stored.version(), usagesOf(fp)));
        return fp;
    }

    @Override
    public void delete(String id) throws IOException {
        store().delete(key(id), PersistenceStore.ANY);
        bases.remove(id);
    }

    @Override
    public boolean isReady() {
        try {
            return !store().children(PREFIX).isEmpty();
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public void iterateAndCleanupFingerprints(TaskListener listener) {
        Set<String> names;
        try {
            names = store().children(PREFIX);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not list fingerprints", e);
            return;
        }
        int deleted = 0;
        for (String name : names) {
            if (!name.endsWith(".xml")) {
                continue;
            }
            try {
                Fingerprint fp = load(name.substring(0, name.length() - ".xml".length()));
                if (fp != null && cleanFingerprint(fp, listener)) {
                    deleted++;
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Could not clean fingerprint " + name, e);
            }
        }
        listener.getLogger().println("Cleaned up " + deleted + " fingerprints in the store");
    }

    @Extension
    public static final class DescriptorImpl extends FingerprintStorageDescriptor {
        @NonNull
        @Override
        public String getDisplayName() {
            return "Persistence store";
        }
    }
}
