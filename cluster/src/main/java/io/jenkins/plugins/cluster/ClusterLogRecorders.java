package io.jenkins.plugins.cluster;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.logging.LogRecorder;
import hudson.logging.LogRecorderManager;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.persistence.PersistenceStore;
import jenkins.persistence.PersistenceStores;
import jenkins.persistence.RemoteChangeHandler;

/**
 * Log recorders ({@code log/<name>.xml}) shared by the cluster: one made, changed or deleted on any controller is
 * made, changed or deleted on every controller, and survives them.
 *
 * <p>The store holds the files (static state). Core loads recorders at startup by listing {@code log/} on disk, which
 * on a fresh {@code JENKINS_HOME} is empty: this loads the stored ones, and applies changes made elsewhere. Each
 * controller records its own log records; only the configuration is shared.
 */
public final class ClusterLogRecorders {

    private static final Logger LOGGER = Logger.getLogger(ClusterLogRecorders.class.getName());

    static final String PREFIX = "log";

    private ClusterLogRecorders() {}

    /** Loads the recorders that are stored but not on disk here (core's {@code LogRecorderManager.init} ran before). */
    @Initializer(after = InitMilestone.EXTENSIONS_AUGMENTED)
    public static void loadStored() {
        PersistenceStore store = PersistenceStores.get();
        if (store == null) {
            return;
        }
        try {
            for (String child : store.children(PREFIX)) {
                if (child.endsWith(".xml") && ClusterRouting.isShared(PREFIX + "/" + child)) {
                    String name = child.substring(0, child.length() - ".xml".length());
                    if (find(name) == null) {
                        apply(name, false);
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to load the stored log recorders", e);
        }
    }

    /** Applies a recorder made, changed or deleted on another controller. */
    @Extension
    public static final class Changes implements RemoteChangeHandler {
        @Override
        public boolean handles(@NonNull String key) {
            return key.startsWith(PREFIX + "/") && key.endsWith(".xml") && key.indexOf('/', PREFIX.length() + 1) < 0;
        }

        @Override
        public void changed(@NonNull String key, boolean deleted) throws Exception {
            apply(key.substring(PREFIX.length() + 1, key.length() - ".xml".length()), deleted);
        }
    }

    static synchronized void apply(String name, boolean deleted) throws IOException {
        LogRecorderManager manager = Jenkins.get().getLog();
        LogRecorder existing = find(name);
        if (deleted) {
            if (existing != null) {
                // not LogRecorder.delete(), which deletes the stored file: only forget it here
                manager.getRecorders().remove(existing);
                existing.getLoggers().forEach(LogRecorder.Target::disable);
                reenableAll(manager);
            }
            return;
        }
        if (existing == null) {
            LogRecorder recorder = new LogRecorder(name);
            recorder.load(); // reads the stored file; enables its loggers
            manager.getRecorders().add(recorder);
            return;
        }
        List<LogRecorder.Target> before = new ArrayList<>(existing.getLoggers());
        existing.load();
        // loggers removed elsewhere stop recording here; others recording the same loggers keep their levels
        before.stream().filter(t -> !existing.getLoggers().contains(t)).forEach(LogRecorder.Target::disable);
        reenableAll(manager);
    }

    private static void reenableAll(LogRecorderManager manager) {
        manager.getRecorders().forEach(r -> r.getLoggers().forEach(LogRecorder.Target::enable));
    }

    private static LogRecorder find(String name) {
        for (LogRecorder r : Jenkins.get().getLog().getRecorders()) {
            if (r.getName().equals(name)) {
                return r;
            }
        }
        return null;
    }
}
