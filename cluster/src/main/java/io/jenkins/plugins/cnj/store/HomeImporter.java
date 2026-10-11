package io.jenkins.plugins.cnj.store;

import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.Job;
import hudson.model.Run;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import jenkins.model.Jenkins;
import jenkins.persistence.BuildRecords;
import jenkins.persistence.ConflictException;
import jenkins.persistence.PersistenceStore;
import jenkins.persistence.PersistenceStores;

/**
 * Imports an existing, file-based Jenkins home into the store (Target 10): start one controller on the old home with
 * {@code -Dio.jenkins.plugins.cnj.store.importHome=true}. It uploads every file the store owns but lacks (jobs and folders,
 * users, nodes, global and descriptor configuration, credentials), the fingerprints, and, once jobs are loaded, the
 * completed builds with their logs. Keys the store already has are left alone, so running it again changes nothing.
 * Runtime secrets ({@code secrets/*}) are published by core at startup anyway; the pre-plugin keys of the old home become
 * the cluster's seed.
 *
 * <p>A report is written to {@code $JENKINS_HOME/cnj-import-report.txt}.
 */
public final class HomeImporter {

    private static final Logger LOGGER = Logger.getLogger(HomeImporter.class.getName());
    static final boolean IMPORT = Boolean.getBoolean(HomeImporter.class.getPackageName() + ".importHome");
    /** Never imported: caches, workspaces, the plugins and war, logs, build directories (imported as builds). */
    private static final Pattern SKIP = Pattern.compile(
            "(plugins|war|workspace|caches|logs|updates|userContent|fingerprints|secrets|\\.cache|\\.groovy|\\.java|\\.m2|tmp)(/.*)?"
                    + "|.*/(builds|workspace|lastStable|lastSuccessful|indexing)(/.*)?|.*\\.(tmp|log|bak|lock)");
    private static final Pattern FINGERPRINT = Pattern.compile("fingerprints/[0-9a-f]{2}/[0-9a-f]{2}/([0-9a-f]{28})\\.xml");

    private static final List<String> report = new ArrayList<>();
    private static int imported;
    private static int kept;

    private HomeImporter() {}

    /** Configuration and fingerprints: before anything is loaded, so that Jenkins loads them from the store. */
    @Initializer(after = InitMilestone.EXTENSIONS_AUGMENTED, before = InitMilestone.SYSTEM_CONFIG_LOADED) // the store exists from EXTENSIONS_AUGMENTED
    public static void importFiles() throws IOException {
        if (!IMPORT || !(PersistenceStores.get() instanceof PostgresPersistenceStore store)) {
            return;
        }
        Path home = Jenkins.get().getRootDir().toPath();
        LOGGER.info(() -> "Importing " + home + " into the store");
        List<Path> files;
        try (Stream<Path> walk = Files.walk(home)) {
            files = walk.filter(Files::isRegularFile).toList();
        }
        for (Path f : files) {
            String key = home.relativize(f).toString().replace(File.separatorChar, '/');
            Matcher fp = FINGERPRINT.matcher(key);
            if (fp.matches()) {
                String md5 = key.substring("fingerprints/".length(), "fingerprints/".length() + 2)
                        + key.substring("fingerprints/xx/".length(), "fingerprints/xx/".length() + 2) + fp.group(1);
                put(store, StoreFingerprintStorage.key(md5), f);
            } else if (!SKIP.matcher(key).matches() && store.owns(key)) {
                put(store, key, f);
            }
        }
        LOGGER.info(() -> "Imported " + imported + " files; " + kept + " were already in the store");
    }

    private static void put(PersistenceStore store, String key, Path f) throws IOException {
        if (store.read(key) != null) {
            kept++;
            report.add("kept   " + key);
            return;
        }
        try {
            store.write(key, Files.readAllBytes(f), PersistenceStore.ABSENT);
            imported++;
            report.add("import " + key);
        } catch (ConflictException e) {
            kept++;
            report.add("kept   " + key + " (written meanwhile)");
        }
    }

    /** Completed builds, once jobs are loaded: records and snapshots (T3), and Pipeline logs into their blobs. */
    @Initializer(after = InitMilestone.JOB_CONFIG_ADAPTED)
    public static void importBuilds() throws IOException {
        if (!IMPORT || !(PersistenceStores.get() instanceof PostgresPersistenceStore)) {
            return;
        }
        int builds = 0;
        int skipped = 0;
        for (Job<?, ?> job : Jenkins.get().getAllItems(Job.class)) {
            if (!BuildRecords.isShared(job)) {
                continue;
            }
            Set<Integer> stored = BuildRecords.storedNumbers(job);
            for (Run<?, ?> run : job.getBuilds()) {
                if (run.isBuilding() || stored.contains(run.getNumber())) {
                    skipped++;
                    continue;
                }
                try {
                    importLog(run);
                    BuildRecords.publish(run);
                    builds++;
                    report.add("build  " + job.getFullName() + " #" + run.getNumber());
                } catch (IOException | RuntimeException e) {
                    LOGGER.log(Level.WARNING, "Could not import " + run, e);
                    report.add("FAILED " + job.getFullName() + " #" + run.getNumber() + ": " + e);
                }
            }
        }
        int b = builds;
        int s = skipped;
        LOGGER.info(() -> "Imported " + b + " builds; " + s + " were already in the store (or running)");
        File out = new File(Jenkins.get().getRootDir(), "cnj-import-report.txt");
        try (PrintWriter w = new PrintWriter(out, StandardCharsets.UTF_8)) {
            report.forEach(w::println);
            w.println("files imported " + imported + ", kept " + kept + "; builds imported " + b + ", kept " + s);
        }
    }

    /** A Pipeline log kept in append blobs (T3): upload the old log and its index if the blobs are not there. */
    private static void importLog(Run<?, ?> run) throws IOException {
        AzureBlobStore blobs = BlobLogStorage.blobs();
        String key = BlobLogStorage.buildKey(run.getRootDir());
        if (blobs == null || key == null) {
            return;
        }
        for (String name : List.of(BlobLogStorage.LOG, BlobLogStorage.INDEX)) {
            File local = new File(run.getRootDir(), name);
            if (local.isFile() && blobs.size(key + "/" + name) == 0) {
                try (InputStream in = new FileInputStream(local)) {
                    blobs.put(key + "/" + name, in, local.length());
                }
            }
        }
    }
}
