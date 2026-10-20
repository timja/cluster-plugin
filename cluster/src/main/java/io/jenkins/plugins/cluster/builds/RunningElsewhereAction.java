package io.jenkins.plugins.cluster.builds;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Action;
import hudson.model.Item;
import hudson.model.Job;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import jenkins.model.ExternalBuild;
import jenkins.model.Jenkins;
import jenkins.model.TransientActionFactory;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;
import org.kohsuke.stapler.interceptor.RequirePOST;

/**
 * Lists the builds of a job running on other controllers, on the job page and in its REST API ({@code buildsElsewhere}
 * of this action), only while there are some.
 */
@Restricted(NoExternalUse.class)
@ExportedBean
public final class RunningElsewhereAction implements Action {
    private final Job<?, ?> job;

    RunningElsewhereAction(Job<?, ?> job) {
        this.job = job;
    }

    public Job<?, ?> getJob() {
        return job;
    }

    /** The builds, newest first, with the main properties a {@code Run} would export. */
    @Exported(name = "buildsElsewhere", inline = true)
    public List<RemoteBuild> getBuilds() {
        List<RemoteBuild> builds = new ArrayList<>();
        for (ExternalBuild b : new ClusterExternalBuilds().running(job)) {
            builds.add(new RemoteBuild(job, (BuildElsewhere) b));
        }
        return builds;
    }

    @Override
    public String getIconFileName() {
        return null;
    }

    @Override
    public String getDisplayName() {
        return "Running on other controllers";
    }

    @Override
    public String getUrlName() {
        return "running-elsewhere";
    }

    /**
     * Stops a build running on another controller.
     */
    @RequirePOST
    public HttpResponse doStop(@QueryParameter int number) throws IOException {
        job.checkPermission(Item.CANCEL);
        RunningBuilds.requestStop(job, number, Jenkins.getAuthentication2().getName());
        return HttpResponses.redirectTo("..");
    }

    /**
     * A build running on another controller, as the REST API shows it: the main properties, with the same visibility,
     * of a {@code Run} that is building. Its actions (parameters, causes, …) are known to the controller running it
     * only; its own URL (and API), which that controller answers, has them.
     */
    @ExportedBean
    public static final class RemoteBuild {
        private final Job<?, ?> job;
        private final BuildElsewhere b;

        RemoteBuild(Job<?, ?> job, BuildElsewhere b) {
            this.job = job;
            this.b = b;
        }

        @Exported(visibility = 2)
        public int getNumber() {
            return b.getNumber();
        }

        @Exported(visibility = 2)
        public String getUrl() {
            return Jenkins.get().getRootUrlFromRequest() + b.getUrl();
        }

        @Exported
        public String getId() {
            return String.valueOf(b.getNumber());
        }

        @Exported
        public String getDisplayName() {
            return b.getPlaceholder().getDisplayName();
        }

        @Exported
        public String getFullDisplayName() {
            return b.getDisplayName();
        }

        @Exported
        public boolean isBuilding() {
            return true;
        }

        @Exported
        public boolean isInProgress() {
            return true;
        }

        @Exported
        public long getTimestamp() {
            return b.getStartTimeInMillis();
        }

        /** {@link #getTimestamp} as a date, for views. */
        public java.util.Date getStartDate() {
            return b.getPlaceholder().getStartDate();
        }

        @Exported
        public long getEstimatedDuration() {
            return b.getEstimatedDuration();
        }

        /** Which controller runs it (not on a {@code Run}). */
        @Exported
        public String getController() {
            return b.getLocation();
        }
    }

    @Restricted(NoExternalUse.class)
    @Extension
    public static final class Factory extends TransientActionFactory<Job> {

        @Override
        public Class<Job> type() {
            return Job.class;
        }

        @NonNull
        @Override
        public Collection<? extends Action> createFor(@NonNull Job target) {
            return new ClusterExternalBuilds().running(target).isEmpty()
                    ? List.of()
                    : List.of(new RunningElsewhereAction(target));
        }
    }
}
