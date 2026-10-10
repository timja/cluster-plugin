package io.jenkins.plugins.cnj.store;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Executor;
import hudson.model.Job;
import hudson.model.Run;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.persistence.ConflictException;
import jenkins.persistence.PersistenceStore;
import jenkins.persistence.PersistenceStores;
import jenkins.persistence.RunningBuilds;
import org.jenkinsci.plugins.pipeline.milestone.MilestoneStorage;

/**
 * Keeps the milestones each running build of a job has passed in the persistence store, so that
 * {@code milestone} works across controllers: a newer build passing a milestone cancels older builds
 * wherever they run (through a stop request to their owner).
 *
 * <p>Key {@code milestones/<job key>}: one {@code <build>=<ordinal>} line per running build ({@code -} before its first milestone),
 * updated by compare-and-set.
 */
@Extension(ordinal = 100)
public class ClusterMilestoneStorage implements MilestoneStorage {

    private static final Logger LOGGER = Logger.getLogger(ClusterMilestoneStorage.class.getName());
    private static final int ATTEMPTS = 100;

    @CheckForNull
    private static String key(Job<?, ?> job) {
        String jobKey = PersistenceStores.keyFor(job.getRootDir());
        return jobKey == null ? null : "milestones/" + jobKey;
    }

    private static Map<Integer, Integer> parse(byte[] content) {
        Map<Integer, Integer> m = new TreeMap<>();
        for (String line : new String(content, StandardCharsets.UTF_8).split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                String v = line.substring(eq + 1).trim();
                m.put(Integer.parseInt(line.substring(0, eq).trim()), v.equals("-") ? null : Integer.parseInt(v));
            }
        }
        return m;
    }

    private static byte[] format(Map<Integer, Integer> m) {
        StringBuilder b = new StringBuilder();
        m.forEach((build, ordinal) -> b.append(build)
                .append('=')
                .append(ordinal == null ? "-" : ordinal)
                .append('\n'));
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Applies a change to a job's milestones with compare-and-set, retrying on conflicts. */
    private static Map<Integer, Integer> modify(Job<?, ?> job, UnaryOperator<Map<Integer, Integer>> change) {
        PersistenceStore store = PersistenceStores.get();
        String key = key(job);
        if (store == null || key == null) {
            return change.apply(new TreeMap<>());
        }
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            try {
                PersistenceStore.Entry e = store.read(key);
                Map<Integer, Integer> m = change.apply(e == null ? new TreeMap<>() : parse(e.content()));
                if (m.isEmpty()) {
                    if (e != null) {
                        store.delete(key, e.version());
                    }
                } else {
                    store.write(key, format(m), e == null ? PersistenceStore.ABSENT : e.version());
                }
                return m;
            } catch (ConflictException x) {
                // another controller changed it meanwhile: retry
            } catch (IOException x) {
                LOGGER.log(Level.WARNING, "Cannot update milestones of " + job.getFullName(), x);
                return Collections.emptyMap();
            }
        }
        LOGGER.warning(() -> "Gave up updating milestones of " + job.getFullName());
        return Collections.emptyMap();
    }

    @Override
    public Map<Integer, Integer> store(@NonNull Run<?, ?> run, @CheckForNull Integer ordinal) {
        return Collections.unmodifiableMap(modify(run.getParent(), m -> {
            m.put(run.getNumber(), ordinal);
            return m;
        }));
    }

    @NonNull
    @Override
    public ClearResult clear(@NonNull Run<?, ?> run) {
        Integer[] previous = new Integer[1];
        Map<Integer, Integer> rest = modify(run.getParent(), m -> {
            previous[0] = m.remove(run.getNumber());
            return m;
        });
        return new ClearResult(previous[0], Collections.unmodifiableMap(rest));
    }

    @Override
    public void onDeletedJob(@NonNull Job<?, ?> job) {
        modify(job, m -> new TreeMap<>());
    }

    @Override
    public void cancel(Job<?, ?> job, int buildNumber, @NonNull String externalizableId) {
        Run<?, ?> run = job.getBuildByNumber(buildNumber);
        Executor executor = run == null ? null : run.getExecutor();
        if (executor != null) {
            MilestoneStorage.super.cancel(job, buildNumber, externalizableId); // running here
            return;
        }
        try {
            RunningBuilds.requestStop(job, buildNumber, "milestone passed by " + externalizableId);
        } catch (IOException e) {
            LOGGER.log(
                    Level.WARNING,
                    "Cannot cancel " + job.getFullName() + " #" + buildNumber + " on another controller",
                    e);
        }
    }
}
