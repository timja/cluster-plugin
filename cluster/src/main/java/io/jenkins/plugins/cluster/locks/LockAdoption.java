package io.jenkins.plugins.cluster.locks;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Result;
import hudson.model.Run;
import io.jenkins.plugins.cluster.spi.ClusterStore;
import java.io.IOException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.CauseOfInterruption;
import jenkins.persistence.ConflictException;
import jenkins.persistence.PersistenceStore;
import org.jenkins.plugins.lockableresources.LockableResource;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionListener;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * A build resumed here, after a restart or adopted after its controller died, takes over the locks it holds. If one
 * of them went to another holder meanwhile, the build is stopped: it must not go on as if it still held it.
 */
@Extension(optional = true)
@Restricted(NoExternalUse.class)
public final class LockAdoption extends FlowExecutionListener {

    private static final Logger LOGGER = Logger.getLogger(LockAdoption.class.getName());

    @Override
    public void onResumed(@NonNull FlowExecution execution) {
        ClusterStore store = ClusterLocks.store();
        if (store == null) {
            return;
        }
        Run<?, ?> run;
        try {
            run = execution.getOwner().getExecutable() instanceof Run<?, ?> r ? r : null;
        } catch (IOException e) {
            return;
        }
        List<String> names = run == null ? List.of() : LockableResourcesManager.getResourceNamesHeldBy(run);
        if (names.isEmpty()) {
            return;
        }
        LockableResourcesManager lrm = LockableResourcesManager.get();
        ClusterLocks.Holder mine = ClusterLocks.Holder.lock(run, store.getOrigin());
        synchronized (LockableResourcesManager.syncResources) {
            for (String name : names) {
                String key = ClusterLocks.key(name);
                try {
                    PersistenceStore.Entry entry = store.read(key);
                    ClusterLocks.Holder h = ClusterLocks.Holder.parse(entry);
                    long expected;
                    if (entry == null) {
                        expected = PersistenceStore.ABSENT; // released as gone meanwhile, and nobody took it
                    } else if (h != null && h.isLockOf(run)) {
                        expected = entry.version();
                    } else {
                        lost(execution, run, name, h == null ? "an unreadable holder" : h.describe());
                        return;
                    }
                    store.write(key, mine.toBytes(), expected);
                    LockableResource r = ClusterLocks.resource(lrm, name);
                    if (r != null) {
                        r.setHeldElsewhereBy(null);
                        r.setBuild(run);
                    }
                    LOGGER.fine(() -> run + " took over its lock on " + name);
                } catch (ConflictException e) {
                    lost(execution, run, name, "another holder");
                    return;
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Failed to take over the lock on " + name + " for " + run, e);
                }
            }
        }
    }

    private static void lost(FlowExecution execution, Run<?, ?> run, String name, String holder) {
        LOGGER.warning(
                () -> run + " lost its lock on " + name + " to " + holder + " while it was not running; stopping it");
        try {
            execution.interrupt(Result.FAILURE, new LockLost(name, holder));
        } catch (IOException | InterruptedException e) {
            LOGGER.log(Level.WARNING, "Failed to stop " + run, e);
        }
    }

    /** Why a build was stopped. */
    public static final class LockLost extends CauseOfInterruption {
        private static final long serialVersionUID = 1L;
        private final String resource;
        private final String holder;

        LockLost(String resource, String holder) {
            this.resource = resource;
            this.holder = holder;
        }

        @Override
        public String getShortDescription() {
            return "Lost the lock on " + resource + " to " + holder + " while the build was not running";
        }
    }
}
