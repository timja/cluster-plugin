package io.jenkins.plugins.cluster.spi;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.ExtensionPoint;
import hudson.model.PeriodicWork;
import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.util.Timer;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * A catch-up of a cache fed by the store's change events. Events are not guaranteed: one can be lost (a listener that
 * was disconnected for longer than the store keeps events, a store restored to an earlier point), or fail to apply for
 * longer than its retries. So each such cache also re-reads, now and then, what it mirrors, and applies whatever
 * differs.
 *
 * <p>Every implementation runs every {@value #PERIOD_MINUTES} minutes (with jitter, so that the controllers of a
 * cluster do not all list the store at once), and at once when a store {@linkplain #request asks for it} because it
 * cannot rule out that events were lost. Runs are coalesced: one run serves every request made before it starts, and
 * never two run at once.
 */
public abstract class Resync implements ExtensionPoint {

    private static final Logger LOGGER = Logger.getLogger(Resync.class.getName());

    static final long PERIOD_MINUTES = 15;

    private static final AtomicBoolean pending = new AtomicBoolean();
    private static final Object running = new Object();

    /**
     * Re-reads from the store what this cache mirrors and applies whatever differs, as if the events of every change
     * had just arrived. Called on a {@link Timer} thread, never two at once; only with a {@link ClusterStore} active.
     */
    public abstract void catchUp() throws IOException;

    /**
     * Asks for a catch-up of every cache as soon as possible, because change events may have been lost.
     *
     * @param reason why, for the log
     */
    public static void request(@NonNull String reason) {
        LOGGER.warning(() -> "Catching up with the store, since " + reason);
        if (pending.compareAndSet(false, true)) {
            Timer.get().submit(Resync::runAll);
        }
    }

    /** Runs every catch-up once, now, on this thread. */
    @Restricted(NoExternalUse.class)
    public static void runAll() {
        synchronized (running) {
            pending.set(false); // a request made from now on runs again after this one
            if (ClusterStore.get() == null) {
                return;
            }
            for (Resync r : ExtensionList.lookup(Resync.class)) {
                try {
                    r.catchUp();
                } catch (IOException | RuntimeException e) {
                    LOGGER.log(
                            Level.WARNING, "Could not catch up " + r.getClass().getName() + " with the store", e);
                }
            }
        }
    }

    /** Drives the periodic catch-up. */
    @Extension
    @Restricted(NoExternalUse.class)
    public static final class Periodically extends PeriodicWork {
        @Override
        public long getRecurrencePeriod() {
            return TimeUnit.MINUTES.toMillis(PERIOD_MINUTES);
        }

        @Override
        public long getInitialDelay() {
            return ThreadLocalRandom.current().nextLong(getRecurrencePeriod()) + TimeUnit.MINUTES.toMillis(1);
        }

        @Override
        protected void doRun() {
            if (ClusterStore.get() != null) {
                runAll();
            }
        }
    }
}
