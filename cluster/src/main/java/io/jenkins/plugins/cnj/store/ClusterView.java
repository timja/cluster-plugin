package io.jenkins.plugins.cnj.store;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Api;
import hudson.model.ManagementLink;
import hudson.security.Permission;
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

    /** The name of this controller. */
    @Exported
    public String getSelf() {
        return PostgresPersistenceStore.NAME;
    }

    @Exported
    public List<MemberInfo> getMembers() throws IOException {
        List<MemberInfo> result = new ArrayList<>();
        PostgresPersistenceStore store = store();
        if (store != null) {
            for (PostgresPersistenceStore.Member m : store.members()) {
                result.add(new MemberInfo(m));
            }
        }
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
            store.channels().forEach((agent, controller) -> result.add(new Channel(agent, controller)));
        }
        return result;
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

        /** The generation of the cluster's plugin set this controller has applied (Target 13). */
        @Exported
        public String getPlugins() {
            return m.plugins();
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

        Channel(String agent, String controller) {
            this.agent = agent;
            this.controller = controller;
        }

        @Exported
        public String getAgent() {
            return agent;
        }

        @Exported
        public String getController() {
            return controller;
        }
    }
}
