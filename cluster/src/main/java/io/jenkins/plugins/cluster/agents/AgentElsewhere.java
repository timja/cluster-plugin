package io.jenkins.plugins.cluster.agents;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Util;
import hudson.model.Computer;
import hudson.model.Job;
import hudson.model.Queue;
import hudson.model.queue.SubTask;
import hudson.model.queue.WorkUnit;
import hudson.security.ACL;
import hudson.security.ACLContext;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import jenkins.agents.IOfflineCause;
import jenkins.model.IComputer;
import jenkins.model.IDisplayExecutor;
import jenkins.model.IExecutor;
import jenkins.model.Jenkins;
import jenkins.model.queue.ITask;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * An agent connected to another controller, as shown here: online, with its executors as they are there, each busy one
 * with the job and build it runs. Its URL, name and permissions are those of the agent here; it stays offline for this
 * controller's scheduling, which never sees this object.
 */
@Restricted(NoExternalUse.class)
public final class AgentElsewhere implements IComputer {

    private final Computer computer;
    private final AgentsElsewhere.Connection connection;

    AgentElsewhere(@NonNull Computer computer, @NonNull AgentsElsewhere.Connection connection) {
        this.computer = computer;
        this.connection = connection;
    }

    /** The agent here, offline. */
    @NonNull
    public Computer getComputer() {
        return computer;
    }

    /** The name of the controller it is connected to. */
    @NonNull
    public String getWhere() {
        return connection.where();
    }

    @NonNull
    @Override
    public String getName() {
        return computer.getName();
    }

    @Override
    public String getDisplayName() {
        return computer.getDisplayName();
    }

    @NonNull
    @Override
    public String getUrl() {
        return computer.getUrl();
    }

    @NonNull
    @Override
    public ACL getACL() {
        return computer.getACL();
    }

    @Override
    public boolean isOffline() {
        return false;
    }

    @Override
    public boolean isOnline() {
        return true;
    }

    @Override
    public boolean isAcceptingTasks() {
        return computer.isAcceptingTasks();
    }

    @CheckForNull
    @Override
    public IOfflineCause getOfflineCause() {
        return null;
    }

    @Override
    public String getTooltip() {
        return "Online, connected to " + connection.where(); // as text: the widget and list escape tooltips
    }

    @Override
    public boolean isConnecting() {
        return false;
    }

    @Override
    public int countBusy() {
        return Math.min(connection.countBusy(), countExecutors());
    }

    @Override
    public int countExecutors() {
        return Math.max(connection.executors(), connection.countBusy());
    }

    @Override
    public int countIdle() {
        return countExecutors() - countBusy();
    }

    @Override
    public boolean isLaunchSupported() {
        return false;
    }

    /** Never launched from here: it is connected to another controller. */
    @Override
    public Future<?> connect(boolean forceReconnect) {
        return CompletableFuture.completedFuture(null);
    }

    @NonNull
    @Override
    public List<? extends IDisplayExecutor> getDisplayExecutors() {
        List<Display> executors = new ArrayList<>();
        List<AgentsElsewhere.Busy> busy = connection.busy();
        for (int i = 0; i < countExecutors(); i++) {
            executors.add(new Display(new Executor(i, i < busy.size() ? busy.get(i) : null)));
        }
        return executors;
    }

    private static final class Display implements IDisplayExecutor {
        private final Executor executor;

        Display(Executor executor) {
            this.executor = executor;
        }

        @Override
        public String getDisplayName() {
            return Integer.toString(executor.getNumber() + 1);
        }

        @NonNull
        @Override
        public String getUrl() {
            return "executors/" + executor.getNumber();
        }

        @NonNull
        @Override
        public IExecutor getExecutor() {
            return executor;
        }
    }

    /** An executor of the agent there. Its job is looked up as the system, and shown only to those who may read it. */
    private final class Executor implements IExecutor {
        private final int number;

        @CheckForNull
        private final AgentsElsewhere.Busy busy;

        Executor(int number, @CheckForNull AgentsElsewhere.Busy busy) {
            this.number = number;
            this.busy = busy;
        }

        @CheckForNull
        private Job<?, ?> job() {
            if (busy == null || busy.job() == null) {
                return null;
            }
            try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
                // the widget checks the current user's permission on it (ITask#hasReadPermission), as for local ones
                return Jenkins.get().getItemByFullName(busy.job(), Job.class);
            }
        }

        @Override
        public boolean isIdle() {
            return busy == null;
        }

        @Override
        public IComputer getOwner() {
            return AgentElsewhere.this;
        }

        @CheckForNull
        @Override
        public Queue.Executable getCurrentExecutable() {
            Job<?, ?> job = job();
            return job instanceof SubTask task && busy.build() > 0 ? new BuildThere(job, task, busy.build()) : null;
        }

        @CheckForNull
        @Override
        public WorkUnit getCurrentWorkUnit() {
            return null;
        }

        /** The job it runs (or is about to run): pending while its build has not started. */
        @CheckForNull
        @Override
        public ITask getParentTask() {
            return job() instanceof SubTask task ? task : null;
        }

        @Override
        public String getDisplayName() {
            return "Executor #" + number;
        }

        @Override
        public boolean hasStopPermission() {
            return false;
        }

        @Override
        public int getNumber() {
            return number;
        }

        @Override
        public long getElapsedTime() {
            return busy == null || busy.startedAt() <= 0
                    ? 0
                    : Math.max(0, System.currentTimeMillis() - busy.startedAt());
        }

        @Override
        public String getEstimatedRemainingTime() {
            if (busy == null || busy.startedAt() <= 0 || busy.estimatedDuration() <= 0) {
                return "N/A";
            }
            long remaining = busy.estimatedDuration() - getElapsedTime();
            return remaining > 0 ? Util.getTimeSpanString(remaining) : "N/A";
        }

        @Override
        public boolean isLikelyStuck() {
            return false;
        }

        @Override
        public int getProgress() {
            if (busy == null || busy.startedAt() <= 0 || busy.estimatedDuration() <= 0) {
                return -1;
            }
            return (int) Math.max(0, Math.min(99, getElapsedTime() * 100 / busy.estimatedDuration()));
        }
    }

    /**
     * A build the agent runs there, as an executor's current executable: its URL here is the build's, which a cluster
     * routes to the controller running it. Never run here.
     */
    @Restricted(NoExternalUse.class)
    public static final class BuildThere implements Queue.Executable {
        private final Job<?, ?> job;
        private final SubTask task;
        private final int number;

        BuildThere(Job<?, ?> job, SubTask task, int number) {
            this.job = job;
            this.task = task;
            this.number = number;
        }

        @NonNull
        @Override
        public SubTask getParent() {
            return task;
        }

        @Override
        public void run() {
            throw new UnsupportedOperationException(this + " runs on another controller");
        }

        public String getUrl() {
            return job.getUrl() + number + "/";
        }

        public String getDisplayName() {
            return "#" + number;
        }

        @Override
        public String toString() {
            return job.getFullName() + " #" + number;
        }
    }
}
