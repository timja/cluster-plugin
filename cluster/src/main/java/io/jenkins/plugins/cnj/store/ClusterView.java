package io.jenkins.plugins.cnj.store;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Api;
import hudson.model.ManagementLink;
import hudson.security.Permission;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import jenkins.persistence.PersistenceStores;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

/**
 * The cluster as the store knows it (Target 8): controllers, running builds and their owners, agent channels and their
 * holders. Any controller answers, at {@code /manage/cluster} and {@code /manage/cluster/api/json}.
 */
@Extension
@ExportedBean
public final class ClusterView extends ManagementLink {

    private static final Pattern RUNNING = Pattern.compile("jobs/(.+)/running/([0-9]+)\\.xml");

    @Override
    public String getIconFileName() {
        return PersistenceStores.get() instanceof PostgresPersistenceStore ? "symbol-computer" : null;
    }

    @Override
    public String getDisplayName() {
        return "Cluster";
    }

    @Override
    public String getDescription() {
        return "Controllers sharing this Jenkins, the builds they run and the agents connected to them.";
    }

    @Override
    public String getUrlName() {
        return "cluster";
    }

    @NonNull
    @Override
    public Permission getRequiredPermission() {
        return Jenkins.SYSTEM_READ;
    }

    @NonNull
    @Override
    public Category getCategory() {
        return Category.STATUS;
    }

    /** What this controller writes under its home that the cluster does not share (Target 11). */
    public HomeWrites getCompatibility() {
        Jenkins.get().checkPermission(Jenkins.SYSTEM_READ);
        return new HomeWrites();
    }

    public Api getApi() {
        Jenkins.get().checkPermission(Jenkins.SYSTEM_READ);
        return new Api(this);
    }

    @CheckForNull
    private static PostgresPersistenceStore store() {
        return PersistenceStores.get() instanceof PostgresPersistenceStore p ? p : null;
    }

    /**
     * Each controller's progress applying the cluster's plugin set, as an HTML fragment: shown on the plugin manager's
     * pages ({@link ClusterPluginManagerNotice}) and refreshed from here.
     */
    public void doPluginStatus(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException, ServletException {
        Jenkins.get().checkPermission(Jenkins.SYSTEM_READ);
        rsp.setHeader("Cache-Control", "no-cache");
        req.getView(this, "pluginStatus.jelly").forward(req, rsp);
    }

    /** The name of this controller. */
    @Exported
    public String getSelf() {
        return PostgresPersistenceStore.NAME;
    }

    /**
     * The live controllers, plus, for a name no live controller has (one that is down), its last incarnation if it
     * was seen in the last hour. Earlier incarnations of a controller (every start is a new member) are left out.
     */
    @Exported
    public List<MemberInfo> getMembers() throws IOException {
        List<MemberInfo> result = new ArrayList<>();
        PostgresPersistenceStore store = store();
        if (store != null) {
            for (PostgresPersistenceStore.Member m : visible(store.members(), System.currentTimeMillis())) {
                result.add(new MemberInfo(m));
            }
        }
        return result;
    }

    /** Plugins installed, upgraded, uninstalled or disabled at runtime and applied to every controller (Target 13). */
    @Exported
    public List<PluginChange> getPluginChanges() throws IOException {
        List<PluginChange> result = new ArrayList<>();
        if (store() != null) {
            ClusterPlugins.read().plugins().forEach((name, e) -> result.add(new PluginChange(name, e)));
        }
        result.sort(java.util.Comparator.comparingLong(PluginChange::getAt).reversed()); // newest first
        return result;
    }

    @ExportedBean(defaultVisibility = 2)
    public static final class PluginChange {
        private final String name;
        private final ClusterPlugins.Entry e;

        PluginChange(String name, ClusterPlugins.Entry e) {
            this.name = name;
            this.e = e;
        }

        @Exported
        public String getName() {
            return name;
        }

        @Exported
        public String getState() {
            return e.state().name();
        }

        public String getStateText() {
            return switch (e.state()) {
                case installed -> e.version() == null ? "Enabled" : "Installed";
                case uninstalled -> "Uninstalled";
                case disabled -> "Disabled";
            };
        }

        @Exported
        public String getVersion() {
            return e.version();
        }

        @Exported
        public String getBy() {
            return e.by();
        }

        @Exported
        public long getAt() {
            return e.at();
        }

        public String getAgo() {
            return hudson.Util.getTimeSpanString(Math.max(0, System.currentTimeMillis() - e.at()));
        }
    }

    static final long GONE_SHOWN_MILLIS = java.util.concurrent.TimeUnit.HOURS.toMillis(1);

    /** @param members newest first */
    static List<PostgresPersistenceStore.Member> visible(List<PostgresPersistenceStore.Member> members, long now) {
        java.util.Set<String> named = new java.util.HashSet<>();
        for (PostgresPersistenceStore.Member m : members) {
            if (m.alive()) {
                named.add(m.name());
            }
        }
        List<PostgresPersistenceStore.Member> result = new ArrayList<>();
        for (PostgresPersistenceStore.Member m : members) {
            if (m.alive()) {
                result.add(m);
            } else if (named.add(m.name()) && now - m.heartbeatAt() < GONE_SHOWN_MILLIS) {
                result.add(m); // the newest incarnation of a controller that is down
            }
        }
        result.sort(java.util.Comparator.comparing(PostgresPersistenceStore.Member::name));
        return result;
    }

    @Exported
    public List<RunningBuild> getRunningBuilds() throws IOException {
        List<RunningBuild> result = new ArrayList<>();
        PostgresPersistenceStore store = store();
        if (store != null) {
            for (Map.Entry<String, byte[]> e : store.runningPlaceholders().entrySet()) {
                Matcher m = RUNNING.matcher(e.getKey());
                if (!m.matches()) {
                    continue;
                }
                Properties p = new Properties();
                p.load(new StringReader(new String(e.getValue(), StandardCharsets.UTF_8)));
                result.add(new RunningBuild(m.group(1).replace("/jobs/", "/"), Integer.parseInt(m.group(2)),
                        p.getProperty("controller", "?"), Long.parseLong(p.getProperty("startTime", "0"))));
            }
        }
        return result;
    }

    @Exported
    public List<Channel> getChannels() throws IOException {
        List<Channel> result = new ArrayList<>();
        PostgresPersistenceStore store = store();
        if (store != null) {
            // each agent's executors, as reported by the controller holding its channel
            java.util.Map<String, int[]> executors = new java.util.HashMap<>();
            for (PostgresPersistenceStore.Member m : store.members()) {
                if (m.alive()) {
                    MemberStatus.Info.parse(m.info()).agents().forEach((agent, counts) -> executors.put(m.name() + "/" + agent, counts));
                }
            }
            store.channels().forEach((agent, controller) -> result.add(new Channel(agent, controller, executors.get(controller + "/" + agent))));
        }
        return result;
    }

    /** Background schema migrations and how far they got (Target 15). */
    public List<SchemaMigrations.Progress> getMigrations() {
        PostgresPersistenceStore store = store();
        try {
            return store == null ? List.of() : store.migrations.progress();
        } catch (java.sql.SQLException | RuntimeException e) {
            return List.of();
        }
    }

    /** Shared-queue items waiting for a controller to start them, and claimed by one (Target 15). */
    @Exported
    public java.util.Map<String, Integer> getQueue() throws IOException {
        PostgresPersistenceStore store = store();
        return store == null ? java.util.Map.of() : store.queueCounts();
    }

    public String getQueueSummary() throws IOException {
        java.util.Map<String, Integer> q = getQueue();
        return q.getOrDefault("WAITING", 0) + " waiting, " + q.getOrDefault("CLAIMED", 0) + " being started";
    }

    @ExportedBean(defaultVisibility = 2)
    public static final class MemberInfo {
        private final PostgresPersistenceStore.Member m;

        MemberInfo(PostgresPersistenceStore.Member m) {
            this.m = m;
        }

        @Exported
        public String getName() {
            return m.name();
        }

        @Exported
        public String getUrl() {
            return m.url();
        }

        @Exported
        public long getHeartbeat() {
            return m.heartbeatAt();
        }

        @Exported
        public long getStarted() {
            return m.startedAt();
        }

        public String getStartedAgo() {
            return hudson.Util.getTimeSpanString(Math.max(0, System.currentTimeMillis() - m.startedAt()));
        }

        public String getHeartbeatAgo() {
            return hudson.Util.getTimeSpanString(Math.max(0, System.currentTimeMillis() - m.heartbeatAt()));
        }

        @Exported
        public boolean isAlive() {
            return m.alive();
        }

        @Exported
        public String getVersion() {
            return m.version();
        }

        @Exported
        public int getProtocol() {
            return m.protocol();
        }

        /** What this controller has applied of the cluster's plugin set (Target 13), as published: generation and flags. */
        @Exported
        public String getPlugins() {
            return m.plugins();
        }

        /** Busy executors on this controller's online computers (agents it holds, and the built-in node). */
        @Exported
        public int getBusyExecutors() {
            return MemberStatus.Info.parse(m.info()).busy();
        }

        @Exported
        public int getTotalExecutors() {
            return MemberStatus.Info.parse(m.info()).total();
        }

        public String getExecutorsSummary() {
            if (!m.alive()) {
                return "";
            }
            if (m.info() == null) {
                return "Unknown (an older version)";
            }
            MemberStatus.Info info = MemberStatus.Info.parse(m.info());
            return info.busy() + " of " + info.total() + " busy";
        }

        /** {@link #getPlugins} for people: up to date, applying, restart pending. */
        public String getPluginsSummary() {
            long current = 0;
            try {
                current = ClusterPlugins.read().generation();
            } catch (IOException | RuntimeException e) {
                // shown as published
            }
            return m.alive() ? ClusterPlugins.describe(m.plugins(), current) : "";
        }
    }

    @ExportedBean(defaultVisibility = 2)
    public static final class RunningBuild {
        private final String job;
        private final int number;
        private final String controller;
        private final long startTime;

        RunningBuild(String job, int number, String controller, long startTime) {
            this.job = job;
            this.number = number;
            this.controller = controller;
            this.startTime = startTime;
        }

        @Exported
        public String getJob() {
            return job;
        }

        @Exported
        public int getNumber() {
            return number;
        }

        @Exported
        public String getController() {
            return controller;
        }

        @Exported
        public long getStartTime() {
            return startTime;
        }
    }

    @ExportedBean(defaultVisibility = 2)
    public static final class Channel {
        private final String agent;
        private final String controller;
        private final int[] executors;

        Channel(String agent, String controller, int[] executors) {
            this.agent = agent;
            this.controller = controller;
            this.executors = executors;
        }

        @Exported
        public String getAgent() {
            return agent;
        }

        @Exported
        public String getController() {
            return controller;
        }

        /** Busy executors of this agent, as its controller reports them; -1 if unknown. */
        @Exported
        public int getBusy() {
            return executors == null ? -1 : executors[0];
        }

        @Exported
        public int getExecutors() {
            return executors == null ? -1 : executors[1];
        }

        public String getExecutorsSummary() {
            return executors == null ? "" : executors[0] + " of " + executors[1] + " busy";
        }
    }
}
