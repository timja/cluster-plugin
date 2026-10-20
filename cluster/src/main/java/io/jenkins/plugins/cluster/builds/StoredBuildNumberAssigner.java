package io.jenkins.plugins.cluster.builds;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.Job;
import io.jenkins.plugins.cluster.spi.ClusterStore;
import io.jenkins.plugins.cluster.spi.Keys;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import jenkins.persistence.ConflictException;
import jenkins.persistence.PersistenceStore;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Assigns build numbers of jobs whose builds are shared from one sequence in the store ({@link Keys#nextBuildNumber}),
 * so that every controller draws from it. The local {@code nextBuildNumber} file stays local, and is kept up to date.
 *
 * <p>Increments with a strict compare-and-set and retries on {@link ConflictException}: unlike configuration, a build
 * number must never be overwritten. The sequence never goes below this controller's own next build number (the
 * stored value and {@link Job#getNextBuildNumber} are combined by {@code max}), so that raising the number on any
 * controller ({@code updateNextBuildNumber}, a home imported with history) raises it for all. It is the only writer of
 * the sequence.
 */
@Restricted(NoExternalUse.class)
@Extension(ordinal = 100)
public final class StoredBuildNumberAssigner implements Job.BuildNumberAssigner {

    private static final int MAX_ATTEMPTS = 1000;

    @Override
    public int assignBuildNumber(Job<?, ?> job, SaveNextBuildNumber saveNextBuildNumber) throws IOException {
        String prefix = BuildRecords.prefix(job);
        if (prefix == null) {
            // not shared: whichever assigner would have been used without this one
            for (Job.BuildNumberAssigner other : ExtensionList.lookup(Job.BuildNumberAssigner.class)) {
                if (other != this) {
                    return other.assignBuildNumber(job, saveNextBuildNumber);
                }
            }
            throw new IllegalStateException("no other build number assigner");
        }
        // store calls under the queue lock: an executor creates its build (createExecutable, so this) inside
        // Queue.callWithLock. An exception to holding that lock as briefly as possible: the number is needed now, and
        // drawing it is one read and one compare-and-set write
        ClusterStore store = ClusterStore.get();
        String key = Keys.nextBuildNumber(prefix);
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            PersistenceStore.Entry entry = store.read(key);
            Integer stored = entry == null ? null : parse(entry.content());
            int number = Math.max(Math.max(1, job.getNextBuildNumber()), stored == null ? 0 : stored);
            if (entry == null) {
                // no sequence yet (the job's first build anywhere, or records stored without one): above any stored
                // build
                number = Math.max(
                        number,
                        BuildRecords.listStoredNumbers(job).stream()
                                        .mapToInt(Integer::intValue)
                                        .max()
                                        .orElse(0)
                                + 1);
            }
            try {
                store.write(
                        key,
                        ((number + 1) + "\n").getBytes(StandardCharsets.UTF_8),
                        entry == null ? PersistenceStore.ABSENT : entry.version());
            } catch (ConflictException e) {
                continue; // another controller (or thread) took a number first; try the next one
            }
            synchronized (job) {
                if (job.getNextBuildNumber() <= number) {
                    job.fastUpdateNextBuildNumber(number + 1);
                }
            }
            saveNextBuildNumber.call(); // the local file, as without a store
            return number;
        }
        throw new IOException(
                "Could not assign a build number for " + job.getFullName() + " after " + MAX_ATTEMPTS + " attempts");
    }

    /** Raises a job's stored sequence to at least {@code next} (never lowers it), as when a home is imported. */
    public static void raiseTo(@NonNull Job<?, ?> job, int next) throws IOException {
        String prefix = BuildRecords.prefix(job);
        if (prefix == null) {
            return;
        }
        ClusterStore store = ClusterStore.get();
        String key = Keys.nextBuildNumber(prefix);
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            PersistenceStore.Entry entry = store.read(key);
            Integer stored = entry == null ? null : parse(entry.content());
            if (stored != null && stored >= next) {
                return;
            }
            try {
                store.write(
                        key,
                        (next + "\n").getBytes(StandardCharsets.UTF_8),
                        entry == null ? PersistenceStore.ABSENT : entry.version());
                return;
            } catch (ConflictException e) {
                // changed meanwhile: look again
            }
        }
        throw new IOException("Could not raise the next build number of " + job.getFullName());
    }

    @CheckForNull
    static Integer parse(byte[] content) {
        try {
            return Integer.parseInt(new String(content, StandardCharsets.UTF_8).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
