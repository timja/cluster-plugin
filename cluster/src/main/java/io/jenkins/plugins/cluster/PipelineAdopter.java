package io.jenkins.plugins.cluster;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.Job;
import hudson.model.Run;
import io.jenkins.plugins.cluster.builds.BuildAdopter;
import io.jenkins.plugins.cluster.builds.BuildRecords;
import io.jenkins.plugins.cluster.builds.RunningBuilds;
import io.jenkins.plugins.cluster.spi.ClusterBlobStore;
import io.jenkins.plugins.cluster.spi.Keys;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import jenkins.model.lazy.LazyBuildMixIn;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionList;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;

/**
 * Adopts a running Pipeline build whose controller is gone, from its last committed {@linkplain SavePoints save point}.
 *
 * <p>The save point (running {@code build.xml}, {@code program.dat}, flow nodes) and the log shipped so far are
 * downloaded into a staging directory; once this controller is the recorded owner they are moved into the build
 * directory and the build is loaded, which resumes it exactly as after a restart
 * ("Resuming build at … after Jenkins restart"): {@code node} blocks wait for their agent, {@code sh} steps
 * re-attach to their processes, {@code input} waits again.
 */
@Extension
public class PipelineAdopter implements BuildAdopter {

    private static final Logger LOGGER = Logger.getLogger(PipelineAdopter.class.getName());

    @CheckForNull
    @Override
    public Adoption prepare(@NonNull Job<?, ?> job, @NonNull RunningBuilds.Placeholder placeholder) throws IOException {
        String savePoint = placeholder.getProperties().get(SavePoints.SAVE_POINT);
        ClusterBlobStore blobs = BlobLogStorage.blobs();
        int number = placeholder.getNumber();
        String build = BuildRecords.buildBlobs(job, number);
        if (savePoint == null
                || blobs == null
                || build == null
                || !(job instanceof LazyBuildMixIn.LazyLoadingJob<?, ?> lazy)) {
            return null; // e.g. not a Pipeline, or never saved (PERFORMANCE_OPTIMIZED)
        }
        String base = build + "/" + SavePoints.DIR;
        // staged next to the build directory, so that it is moved into place by a rename (the builds directory may be
        // on another file system than JENKINS_HOME); a name no build has, so nothing loads it
        Path builds = job.getBuildDir().toPath();
        Files.createDirectories(builds);
        Path staging = builds.resolve("." + number + "-adoption-" + UUID.randomUUID());
        Files.createDirectories(staging.resolve(SavePoints.NODES));
        try {
            if (!download(blobs, base + "/" + SavePoints.RECORD + "." + savePoint, staging.resolve(SavePoints.RECORD))
                    || !download(
                            blobs,
                            base + "/" + SavePoints.PROGRAM + "." + savePoint,
                            staging.resolve(SavePoints.PROGRAM))) {
                LOGGER.warning(() -> "Save point " + savePoint + " of " + build + " is incomplete; cannot adopt it");
                Util.deleteRecursive(staging.toFile());
                return null;
            }
            // the flow nodes: those of every save point up to the committed one, in order (later ones overwrite), and
            // none of a later save point an owner uploaded but did not commit
            long[] committed = SavePoints.parseSavePoint(savePoint);
            java.util.TreeMap<long[], java.util.List<String>> bySavePoint =
                    new java.util.TreeMap<>(SavePoints::compareSavePoints);
            java.util.List<String> unversioned = new java.util.ArrayList<>();
            String nodes = base + "/" + SavePoints.NODES;
            for (String key :
                    blobs.list(base)) { // not list(nodes): that holds only workflow/..., not workflow.<epoch>.<seq>/...
                if (!key.startsWith(nodes)) {
                    continue;
                }
                String rest = key.substring(nodes.length());
                if (rest.startsWith("/")) {
                    unversioned.add(key); // saved before nodes were kept per save point
                } else if (rest.startsWith(".") && rest.indexOf('/') > 0) {
                    long[] sp = SavePoints.parseSavePoint(rest.substring(1, rest.indexOf('/')));
                    if (sp != null && committed != null && SavePoints.compareSavePoints(sp, committed) <= 0) {
                        bySavePoint
                                .computeIfAbsent(sp, k -> new java.util.ArrayList<>())
                                .add(key);
                    }
                }
            }
            // flow nodes are files of one directory: a key names one by its last segment, and nothing else (keys are
            // whatever was written to the blob store, and must never become a path outside the staging directory)
            for (String key : unversioned) {
                downloadNode(blobs, key, staging);
            }
            for (java.util.List<String> keys : bySavePoint.values()) {
                for (String key : keys) {
                    downloadNode(blobs, key, staging);
                }
            }
            BlobLogStorage.fetch(blobs, build, staging.toFile());
        } catch (IOException | RuntimeException e) {
            Util.deleteRecursive(staging.toFile());
            throw e;
        }
        LOGGER.fine(() -> "Staged save point " + savePoint + " of " + build + " in " + staging);
        return new Adoption() {
            @Override
            public void resume(long epoch) throws IOException {
                File dir = new File(job.getBuildDir(), Integer.toString(number));
                if (Files.isDirectory(dir.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    // e.g. an empty directory from an earlier attempt, or one with artifacts this controller archived
                    // before the build moved away: what the save point has replaces what is there, the rest is kept
                    moveInto(staging, dir.toPath());
                } else {
                    Files.move(staging, dir.toPath()); // a rename: staged next to it
                }
                lazy.getLazyBuildMixIn()._getRuns().recognizeNumber(number);
                // loading a running Pipeline build resumes it, as after a restart
                Run<?, ?> run = job.getBuildByNumber(number);
                if (run == null) {
                    throw new IOException("Adopted " + build + " but it could not be loaded");
                }
                if (run instanceof FlowExecutionOwner.Executable executable
                        && executable.asFlowExecutionOwner() != null) {
                    FlowExecutionList.get().register(executable.asFlowExecutionOwner());
                }
                LOGGER.info(() -> "Resumed " + run + " from save point " + savePoint + " as owner with epoch " + epoch);
            }

            @Override
            public void abandon() {
                try {
                    Util.deleteRecursive(staging.toFile());
                } catch (IOException e) {
                    LOGGER.log(Level.FINE, "Failed to delete " + staging, e);
                }
            }
        };
    }

    /** Downloads a flow node into the staging directory, by the last segment of its key, if that names a file. */
    private static void downloadNode(ClusterBlobStore blobs, String key, Path staging) throws IOException {
        String name = key.substring(key.lastIndexOf('/') + 1);
        Path nodes = staging.resolve(SavePoints.NODES);
        Path target = nodes.resolve(name).normalize();
        if (!Keys.isSafeName(name) || !nodes.equals(target.getParent())) {
            LOGGER.warning(() -> "Ignoring " + key + " in the blob store: not the name of a flow node");
            return;
        }
        download(blobs, key, target);
    }

    /**
     * Moves what is staged into an existing build directory: each entry of the staging directory replaces the one of
     * the same name there (so no flow node of an older copy is left beside the save point's), the others are kept.
     */
    private static void moveInto(Path staging, Path dir) throws IOException {
        List<Path> entries;
        try (Stream<Path> list = Files.list(staging)) {
            entries = list.toList();
        }
        for (Path from : entries) {
            Path to = dir.resolve(from.getFileName().toString());
            if (Files.isDirectory(to, LinkOption.NOFOLLOW_LINKS)) {
                Util.deleteRecursive(to.toFile());
            } else {
                Files.deleteIfExists(to); // a link itself, never what it points to
            }
            Files.move(from, to);
        }
        Files.delete(staging);
    }

    private static boolean download(ClusterBlobStore blobs, String key, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        boolean found;
        // never through a link (the staging directory is new, so there should be none)
        try (OutputStream out = Files.newOutputStream(
                target,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS)) {
            found = blobs.download(key, out);
        }
        if (!found) {
            Files.deleteIfExists(target);
        }
        return found;
    }
}
