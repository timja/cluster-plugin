package io.jenkins.plugins.cluster.builds;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Item;
import hudson.model.Job;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.util.DaemonThreadFactory;
import hudson.util.NamingThreadFactory;
import io.jenkins.plugins.cluster.spi.Keys;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.ItemUuids;
import jenkins.persistence.PersistenceStores;
import jenkins.persistence.RemoteChangeHandler;
import jenkins.util.ThrowingRunnable;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Applies changes other controllers made to what the store keeps about items' builds ({@code _items/<uuid>/builds/…},
 * {@code …/running/…}): build records, permalinks, running placeholders and stop requests.
 *
 * <p>Changes are handed off the store's event thread (they read the store, and take a job's build index lock), to one
 * thread per item (of a few), so that the changes of an item are applied in order. A change for an item not known here
 * (not loaded yet) is ignored: its builds are listed from the store when it is loaded.
 */
@Restricted(NoExternalUse.class)
@Extension
public final class ItemEvents implements RemoteChangeHandler {

    private static final Logger LOGGER = Logger.getLogger(ItemEvents.class.getName());

    private static final ExecutorService[] WORKERS = new ExecutorService[4];

    static {
        for (int i = 0; i < WORKERS.length; i++) {
            WORKERS[i] = Executors.newSingleThreadExecutor(
                    new NamingThreadFactory(new DaemonThreadFactory(), "Build changes elsewhere " + i));
        }
    }

    /** Runs work for an item on the thread its changes are applied on, as a change elsewhere (nothing written back). */
    static void submit(@NonNull String uuid, @NonNull String what, @NonNull ThrowingRunnable<Exception> work) {
        WORKERS[Math.floorMod(uuid.hashCode(), WORKERS.length)].submit(() -> {
            try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
                PersistenceStores.applyingRemoteChange(() -> {
                    work.run();
                    return null;
                });
            } catch (Exception | LinkageError e) {
                LOGGER.log(Level.WARNING, "Failed to apply " + what, e);
            }
        });
    }

    /** The job with this identity here, or null. */
    @CheckForNull
    static Job<?, ?> job(@NonNull String uuid) {
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            Item item = ItemUuids.get(uuid);
            return item instanceof Job<?, ?> job ? job : null;
        }
    }

    @Override
    public boolean handles(@NonNull String key) {
        Keys.ItemKey k = Keys.parse(key);
        // not deliveries (running/<N>.deliver-<id>): the integrations which make them apply them
        return k != null
                && (k.rest().startsWith("builds/")
                        || k.rest().startsWith("running/") && !k.rest().contains(".deliver-"));
    }

    @Override
    public void changed(@NonNull String key, boolean deleted) {
        Keys.ItemKey k = Keys.parse(key);
        if (k == null) {
            return;
        }
        String prefix = Keys.itemByUuid(k.uuid());
        String[] rest = k.rest().split("/");
        if (rest.length == 2 && rest[0].equals("builds") && rest[1].equals("permalinks")) {
            StoredPermalinks.changedElsewhere(prefix); // no I/O: read again when next needed
            return;
        }
        if (rest.length == 3 && rest[0].equals("builds") && rest[2].equals(BuildRecords.RECORD)) {
            int number;
            try {
                number = Integer.parseInt(rest[1]);
            } catch (NumberFormatException e) {
                return;
            }
            submit(k.uuid(), key, () -> {
                Job<?, ?> job = job(k.uuid());
                if (job == null || !BuildRecords.isShared(job)) {
                    return; // not loaded (yet); its builds are listed from the store when it is
                }
                if (deleted) {
                    BuildRecords.deletedElsewhere(job, number);
                } else {
                    BuildRecords.writtenElsewhere(job, number);
                }
            });
            return;
        }
        if (rest.length == 2 && rest[0].equals("running")) {
            RunningBuilds.changedElsewhere(k.uuid(), prefix, rest[1], deleted);
        }
    }
}
