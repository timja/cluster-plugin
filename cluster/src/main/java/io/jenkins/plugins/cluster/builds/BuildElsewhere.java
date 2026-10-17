package io.jenkins.plugins.cluster.builds;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ExtensionList;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.Queue;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.queue.SubTask;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import jenkins.model.ExternalBuild;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;
import org.kohsuke.stapler.interceptor.RequirePOST;

/**
 * A build running on another controller, at its own URL here ({@code job/x/N/}): {@code index}, {@code console} and the
 * build history entry. A cluster routing such requests to the controller running the build answers them first;
 * without one, this shows the build as running there, until its record arrives and the build itself is shown.
 * Also the start value of a queue item another controller took ({@link Queue.HandOff#started}): it does not run here.
 */
@Restricted(NoExternalUse.class)
@ExportedBean(defaultVisibility = 2)
public final class BuildElsewhere implements ExternalBuild, Queue.Executable {

    private final Job<?, ?> job;
    private final RunningBuilds.Placeholder placeholder;

    BuildElsewhere(@NonNull Job<?, ?> job, @NonNull RunningBuilds.Placeholder placeholder) {
        this.job = job;
        this.placeholder = placeholder;
    }

    /**
     * A view of a build of a job started on another controller: as its placeholder says, else (not written yet, or not
     * read yet) as started by that controller, now. May ask the store.
     *
     * @param origin the {@link jenkins.persistence.PersistenceStore#getOrigin origin} of the controller running it
     */
    @NonNull
    public static BuildElsewhere view(@NonNull Job<?, ?> job, int number, @CheckForNull String origin) {
        for (RunningBuilds.Placeholder p : RunningBuilds.elsewhere(job)) {
            if (p.getNumber() == number) {
                return new BuildElsewhere(job, p);
            }
        }
        String prefix = BuildRecords.prefix(job);
        String where = origin == null ? "another controller" : origin;
        return new BuildElsewhere(
                job,
                new RunningBuilds.Placeholder(
                        prefix == null ? "" : prefix,
                        number,
                        where,
                        where,
                        "#" + number,
                        System.currentTimeMillis(),
                        0,
                        java.util.Map.of(),
                        jenkins.persistence.PersistenceStore.ANY));
    }

    public Job<?, ?> getJob() {
        return job;
    }

    public RunningBuilds.Placeholder getPlaceholder() {
        return placeholder;
    }

    @Exported
    @Override
    public String getDisplayName() {
        return job.getFullDisplayName() + " " + placeholder.getDisplayName();
    }

    @Exported
    @Override
    public int getNumber() {
        return placeholder.getNumber();
    }

    @Exported
    @Override
    public long getStartTimeInMillis() {
        return placeholder.getStartTime();
    }

    @Override
    public long getEstimatedDuration() {
        return job.getEstimatedDuration();
    }

    @Exported
    @NonNull
    @Override
    public String getLocation() {
        return placeholder.getController();
    }

    @Exported
    @Override
    public boolean isBuilding() {
        return true;
    }

    @CheckForNull
    @Override
    public Result getResult() {
        return null;
    }

    @Exported
    @NonNull
    @Override
    public String getUrl() {
        return job.getUrl() + placeholder.getNumber() + "/";
    }

    public boolean isStoppable() {
        return job.hasPermission(Item.CANCEL);
    }

    @NonNull
    @Override
    public SubTask getParent() {
        if (job instanceof SubTask task) {
            return task;
        }
        throw new IllegalStateException(job + " is not a task");
    }

    /** Never: it runs on another controller. */
    @Override
    public void run() {
        throw new UnsupportedOperationException(this + " runs on " + getLocation());
    }

    /**
     * Copies its log from an offset: as its owner ships it while it runs, then the rest from its record once it is
     * published here. Waits a little for the record of a build no longer running elsewhere.
     */
    @Override
    public long writeLogTo(long offset, @NonNull OutputStream out) throws IOException {
        for (RunningBuilds.RemoteLog l : ExtensionList.lookup(RunningBuilds.RemoteLog.class)) {
            long next = l.copy(job, getNumber(), offset, out);
            if (next >= 0) {
                if (next > offset || RunningBuilds.isRunningElsewhere(job, getNumber())) {
                    return next;
                }
                break; // nothing more shipped, and it no longer runs elsewhere: the rest is in its record
            }
        }
        Run<?, ?> done = awaitRecord(job, getNumber(), RunningBuilds.isRunningElsewhere(job, getNumber()) ? 0 : 60);
        if (done == null) {
            // still running elsewhere, its log not readable here (not shipped yet): nothing more for now
            return RunningBuilds.isRunningElsewhere(job, getNumber()) ? offset : -1;
        }
        try (InputStream in = done.getLogInputStream()) {
            in.skipNBytes(offset);
            in.transferTo(out);
        } catch (EOFException e) {
            // the record's log is shorter than what was shipped (e.g. a lost build's)
        }
        return -1;
    }

    /** The record of a build which completed on another controller, once published here (waiting up to {@code seconds}). */
    @CheckForNull
    private static Run<?, ?> awaitRecord(Job<?, ?> job, int number, int seconds) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (true) {
            Run<?, ?> run = job.getBuildByNumber(number);
            if (run != null && !run.isLogUpdated()) {
                return run;
            }
            if (System.nanoTime() >= deadline) {
                return null;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new java.io.InterruptedIOException();
            }
        }
    }

    @Override
    public void requestStop(@NonNull String by) throws IOException {
        RunningBuilds.requestStop(job, getNumber(), by);
    }

    /** What it has printed so far, as shipped by the controller running it, or null if unknown. */
    @CheckForNull
    public String getLog() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (RunningBuilds.RemoteLog l : ExtensionList.lookup(RunningBuilds.RemoteLog.class)) {
            if (l.copy(job, getNumber(), 0, out) >= 0) {
                return out.toString(StandardCharsets.UTF_8);
            }
            out.reset();
        }
        return null;
    }

    /** {@link #getLog} as plain text, as a build's own {@code consoleText} is. */
    public void doConsoleText(StaplerResponse2 rsp) throws IOException {
        job.checkPermission(Item.READ);
        rsp.setContentType("text/plain;charset=UTF-8");
        String log = getLog();
        rsp.getWriter().write(log == null ? "" : log);
    }

    /** Stops it: asks the controller running it to. */
    @RequirePOST
    public HttpResponse doStop() throws IOException {
        job.checkPermission(Item.CANCEL);
        requestStop(Jenkins.getAuthentication2().getName());
        return HttpResponses.redirectTo(".");
    }

    @Override
    public String toString() {
        return job.getFullName() + " #" + getNumber() + " (on " + getLocation() + ")";
    }
}
