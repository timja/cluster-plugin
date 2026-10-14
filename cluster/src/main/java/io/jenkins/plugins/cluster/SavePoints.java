package io.jenkins.plugins.cluster;

import io.jenkins.plugins.cluster.spi.ClusterBlobStore;
import io.jenkins.plugins.cluster.spi.ClusterStore;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.listeners.RunListener;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.persistence.ConflictException;
import jenkins.persistence.PersistenceStores;
import jenkins.persistence.RunningBuilds;
import jenkins.util.Timer;
import org.jenkinsci.plugins.workflow.cps.CpsFlowExecution;
import org.jenkinsci.plugins.workflow.cps.ProgramSavedListener;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;

/**
 * Replicates save points of running Pipeline builds to blob storage, so that another controller can
 * {@linkplain PipelineAdopter adopt} a build if this one is lost.
 *
 * <p>A save point is what CPS needs to resume: {@code program.dat}, the running {@code build.xml},
 * and the flow nodes in {@code workflow/}. {@link ProgramSavedListener} reports each consistent one; it is copied
 * in memory on the CPS VM thread, and the latest is uploaded every {@link #SHIP_PERIOD_MILLIS}
 * (intermediate ones are coalesced), under keys unique to the owner's lease epoch and a sequence number:
 * <ul>
 * <li>{@code jobs/<item>/builds/<N>/running/program.dat.<epoch>.<seq>} and {@code .../build.xml.<epoch>.<seq>}
 * <li>{@code .../running/workflow.<epoch>.<seq>/<id>.xml}: the flow nodes changed since the previous save point
 * </ul>
 * An adopter applies the nodes of every save point up to the committed one, in order, and none of later ones: an owner
 * dying after uploading a save point's nodes but before committing it leaves nodes newer than the program it would
 * resume (Target 15: the resumed program then allocated a node id the staged nodes had used for another step).
 * It is then committed by recording {@code savePoint=<epoch>.<seq>} in the build's placeholder
 * ({@link RunningBuilds#update}), a compare-and-set which fails once another controller has taken the build over;
 * this controller then stops the build ({@link RunningBuilds#lost}).
 * Everything under {@code running/} is deleted once the build completes here.
 */
public final class SavePoints {

    private static final Logger LOGGER = Logger.getLogger(SavePoints.class.getName());

    static final String DIR = "running";
    static final String PROGRAM = "program.dat";
    static final String RECORD = "build.xml";
    static final String NODES = "workflow";
    static final String SAVE_POINT = "savePoint";

    static final long SHIP_PERIOD_MILLIS = Long.getLong(SavePoints.class.getName() + ".shipPeriodMillis", 1_000);

    private SavePoints() {}

    /** A save point copied but not yet uploaded. */
    private static final class Pending {
        byte[] program;
        byte[] record;
        final Map<String, byte[]> nodes = new HashMap<>();
    }

    /** Replication state of one running build. */
    private static final class State {
        final Run<?, ?> run;
        final String base;
        /** File name → last modified and length, to copy only changed nodes. */
        final Map<String, String> nodeStamps = new HashMap<>();

        Pending pending;
        long seq;
        String committed;
        volatile boolean stopped;
        /** Held while a save point ships, so that the cleanup at completion never runs in the middle of one. */
        final Object shipping = new Object();

        State(Run<?, ?> run, String base) {
            this.run = run;
            this.base = base;
        }
    }

    private static final Map<String, State> states = new ConcurrentHashMap<>();
    private static ScheduledFuture<?> task;

    /** Blob key prefix of a build's save points, or null if they are not replicated. */
    @CheckForNull
    static String base(@NonNull Run<?, ?> run) {
        String key = BlobLogStorage.buildKey(run.getRootDir());
        return key == null ? null : key + "/" + DIR;
    }

    @Extension
    public static final class Copier implements ProgramSavedListener {
        @Override
        public void onProgramSaved(@NonNull CpsFlowExecution execution, @NonNull File programDat) {
            if (BlobLogStorage.blobs() == null) {
                return;
            }
            Run<?, ?> run;
            try {
                Queue.Executable executable = execution.getOwner().getExecutable();
                if (!(executable instanceof Run<?, ?> r)) {
                    return;
                }
                run = r;
            } catch (IOException e) {
                return;
            }
            String base = base(run);
            if (base == null || RunningBuilds.ownedPlaceholder(run) == null) {
                return; // not shared, or not leased by this controller
            }
            State st = states.computeIfAbsent(base, k -> new State(run, k));
            if (st.stopped) {
                return;
            }
            try {
                Pending p = new Pending();
                p.program = Files.readAllBytes(programDat.toPath());
                p.record = Files.readAllBytes(new File(run.getRootDir(), RECORD).toPath());
                File[] nodes = new File(run.getRootDir(), NODES).listFiles((d, n) -> n.endsWith(".xml"));
                synchronized (st) {
                    if (nodes != null) {
                        for (File node : nodes) {
                            String stamp = node.lastModified() + ":" + node.length();
                            if (!stamp.equals(st.nodeStamps.get(node.getName()))) {
                                p.nodes.put(node.getName(), Files.readAllBytes(node.toPath()));
                                st.nodeStamps.put(node.getName(), stamp);
                            }
                        }
                    }
                    if (st.pending != null) {
                        p.nodes.putAll(merge(st.pending.nodes, p.nodes));
                    }
                    st.pending = p;
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Could not copy the save point of " + run, e);
                return;
            }
            schedule();
        }
    }

    /**
     * Orders save point ids ({@code <epoch>.<seq>}) numerically; null if not one.
     */
    @CheckForNull
    static long[] parseSavePoint(String id) {
        int dot = id.indexOf('.');
        try {
            return dot <= 0 ? null : new long[] {Long.parseLong(id.substring(0, dot)), Long.parseLong(id.substring(dot + 1))};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static int compareSavePoints(long[] a, long[] b) {
        return a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(a[1], b[1]);
    }

    private static Map<String, byte[]> merge(Map<String, byte[]> older, Map<String, byte[]> newer) {
        Map<String, byte[]> all = new HashMap<>(older);
        all.putAll(newer);
        return all;
    }

    private static synchronized void schedule() {
        if (task == null) {
            task = Timer.get()
                    .scheduleWithFixedDelay(
                            SavePoints::shipAll, SHIP_PERIOD_MILLIS, SHIP_PERIOD_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    private static void shipAll() {
        for (State st : states.values()) {
            try {
                ship(st);
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.WARNING, "Failed to ship the save point of " + st.run + "; will retry", e);
            }
        }
    }

    /** Ships the latest save point of a build now, if one is pending (e.g. before handing the build over). */
    static void flush(@NonNull Run<?, ?> run) throws IOException {
        String base = base(run);
        State st = base == null ? null : states.get(base);
        if (st != null) {
            ship(st);
        }
    }

    private static void ship(State st) throws IOException {
        synchronized (st.shipping) {
            shipLocked(st);
        }
    }

    private static void shipLocked(State st) throws IOException {
        ClusterBlobStore blobs = BlobLogStorage.blobs();
        Pending p;
        synchronized (st) {
            p = st.pending;
            st.pending = null;
        }
        if (p == null || st.stopped || blobs == null) {
            return;
        }
        if (jenkins.persistence.PersistenceStores.get() instanceof ClusterStore store
                && store.mayHaveLapsed()) {
            // e.g. just woke from a pause: another controller may own the build by now; ship once the heartbeat is back
            synchronized (st) {
                if (st.pending == null) {
                    st.pending = p;
                } else {
                    st.pending.nodes.putAll(merge(p.nodes, st.pending.nodes));
                }
            }
            return;
        }
        RunningBuilds.Placeholder owned = RunningBuilds.ownedPlaceholder(st.run);
        if (owned == null) {
            return; // completed meanwhile
        }
        String savePoint = owned.getEpoch() + "." + (st.seq + 1);
        if (!RunningBuilds.owns(st.run)) {
            // fenced: another controller took the build over; never write over its flow nodes or leave save points
            st.stopped = true;
            states.remove(st.base);
            deleteIfCompleted(blobs, st);
            BlobLogStorage.Shipper.drop(BlobLogStorage.buildKey(st.run.getRootDir()));
            RunningBuilds.lost(st.run);
            return;
        }
        try {
            for (Map.Entry<String, byte[]> node : p.nodes.entrySet()) {
                put(blobs, st.base + "/" + NODES + "." + savePoint + "/" + node.getKey(), node.getValue());
            }
            put(blobs, st.base + "/" + PROGRAM + "." + savePoint, p.program);
            put(blobs, st.base + "/" + RECORD + "." + savePoint, p.record);
        } catch (IOException e) {
            synchronized (st) { // retry with whatever is newer
                if (st.pending == null) {
                    st.pending = p;
                } else {
                    st.pending.nodes.putAll(merge(p.nodes, st.pending.nodes));
                }
            }
            throw e;
        }
        // the log must hold every line the save point has consumed (e.g. a sh step's output up to its offset), or a
        // controller resuming from it would skip lines the owner read but had not shipped yet
        BlobLogStorage.Shipper.shipNow(BlobLogStorage.buildKey(st.run.getRootDir()));
        try {
            RunningBuilds.update(st.run, Map.of(SAVE_POINT, savePoint));
        } catch (ConflictException e) {
            // lost the build meanwhile: the save point just uploaded is not one; remove it
            blobs.delete(st.base + "/" + PROGRAM + "." + savePoint);
            blobs.delete(st.base + "/" + RECORD + "." + savePoint);
            blobs.delete(st.base + "/" + NODES + "." + savePoint);
            st.stopped = true;
            states.remove(st.base);
            deleteIfCompleted(blobs, st);
            BlobLogStorage.Shipper.drop(BlobLogStorage.buildKey(st.run.getRootDir()));
            RunningBuilds.lost(st.run);
            return;
        }
        st.seq++;
        String previous = st.committed;
        st.committed = savePoint;
        if (previous != null) {
            blobs.delete(st.base + "/" + PROGRAM + "." + previous);
            blobs.delete(st.base + "/" + RECORD + "." + previous);
        }
        LOGGER.fine(() -> "Committed save point " + savePoint + " of " + st.run + " (" + p.program.length
                + " bytes of program, " + p.nodes.size() + " nodes)");
    }

    /**
     * A fenced owner (e.g. one woken from a pause) may have uploaded flow nodes after the new owner completed the build
     * and deleted its save points: once the build has no running placeholder any more, nothing needs them.
     */
    private static void deleteIfCompleted(ClusterBlobStore blobs, State st) {
        try {
            String placeholder = PersistenceStores.keyFor(st.run.getParent().getRootDir()) + "/running/" + st.run.getNumber() + ".xml";
            if (PersistenceStores.get().read(placeholder) == null) {
                blobs.delete(st.base);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.FINE, "Could not clean up after " + st.run, e);
        }
    }

    private static void put(ClusterBlobStore blobs, String key, byte[] content) throws IOException {
        blobs.put(key, new ByteArrayInputStream(content), content.length);
    }

    @Extension(ordinal = 1000) // with the log shipper, before the record is published
    public static final class Cleanup extends RunListener<Run<?, ?>> {
        @Override
        public void onFinalized(Run<?, ?> run) {
            if (!(run instanceof FlowExecutionOwner.Executable)) {
                return;
            }
            String base = base(run);
            if (base == null) {
                return;
            }
            State st = states.remove(base);
            if (st != null) {
                st.stopped = true;
                synchronized (st.shipping) {
                    // wait for a save point shipping meanwhile (e.g. a build adopted moments before it completed)
                }
            }
            ClusterBlobStore blobs = BlobLogStorage.blobs();
            if (blobs != null && RunningBuilds.owns(run)) { // a new owner still needs them otherwise
                try {
                    blobs.delete(base);
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Failed to delete the save points of " + run, e);
                }
            }
        }
    }
}
