package io.jenkins.plugins.cnj.store;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.Job;
import hudson.model.Run;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.model.lazy.LazyBuildMixIn;
import jenkins.persistence.BuildAdopter;
import jenkins.persistence.PersistenceStores;
import jenkins.persistence.RunningBuilds;
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
        String jobKey = PersistenceStores.keyFor(job.getRootDir());
        if (savePoint == null
                || blobs == null
                || jobKey == null
                || !(job instanceof LazyBuildMixIn.LazyLoadingJob<?, ?> lazy)) {
            return null; // e.g. not a Pipeline, or never saved (PERFORMANCE_OPTIMIZED)
        }
        int number = placeholder.getNumber();
        String build = jobKey + "/builds/" + number;
        String base = build + "/" + SavePoints.DIR;
        // staged inside JENKINS_HOME, so it can be moved into place
        Path staging = new File(Jenkins.get().getRootDir(), "cnj-adoption/" + UUID.randomUUID()).toPath();
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
            java.util.TreeMap<long[], java.util.List<String>> bySavePoint = new java.util.TreeMap<>(SavePoints::compareSavePoints);
            java.util.List<String> unversioned = new java.util.ArrayList<>();
            String nodes = base + "/" + SavePoints.NODES;
            for (String key : blobs.list(base)) { // not list(nodes): that holds only workflow/..., not workflow.<epoch>.<seq>/...
                if (!key.startsWith(nodes)) {
                    continue;
                }
                String rest = key.substring(nodes.length());
                if (rest.startsWith("/")) {
                    unversioned.add(key); // saved before nodes were kept per save point
                } else if (rest.startsWith(".") && rest.indexOf('/') > 0) {
                    long[] sp = SavePoints.parseSavePoint(rest.substring(1, rest.indexOf('/')));
                    if (sp != null && committed != null && SavePoints.compareSavePoints(sp, committed) <= 0) {
                        bySavePoint.computeIfAbsent(sp, k -> new java.util.ArrayList<>()).add(key);
                    }
                }
            }
            for (String key : unversioned) {
                download(blobs, key, staging.resolve(SavePoints.NODES).resolve(key.substring(nodes.length() + 1)));
            }
            for (java.util.List<String> keys : bySavePoint.values()) {
                for (String key : keys) {
                    download(blobs, key, staging.resolve(SavePoints.NODES).resolve(key.substring(key.lastIndexOf('/') + 1)));
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
                if (dir.exists()) {
                    Util.deleteRecursive(dir); // e.g. an empty directory from an earlier attempt
                }
                Files.createDirectories(dir.getParentFile().toPath());
                Files.move(staging, dir.toPath(), StandardCopyOption.ATOMIC_MOVE);
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

    private static boolean download(ClusterBlobStore blobs, String key, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        boolean found;
        try (OutputStream out = Files.newOutputStream(target)) {
            found = blobs.download(key, out);
        }
        if (!found) {
            Files.deleteIfExists(target);
        }
        return found;
    }
}
