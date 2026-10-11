package io.jenkins.plugins.cnj.store;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.Computer;
import hudson.model.UpdateCenter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.persistence.PersistenceStores;
import jenkins.util.Timer;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

/**
 * What each controller reports to the cluster besides being alive (Target 15), published with every heartbeat as
 * {@link PostgresPersistenceStore#memberInfo}: its executors (busy and total, per agent it holds) for the cluster view,
 * and its update-center install jobs, so that the install progress page is answered by the controller running them
 * ({@link UpdateCenterRouting}).
 *
 * <p>Computed on a timer rather than on the heartbeat thread, so a slow computation never delays a heartbeat.
 */
public final class MemberStatus {

    private static final Logger LOGGER = Logger.getLogger(MemberStatus.class.getName());

    /** Agents listed one by one; beyond this only the totals (the member row stays small). */
    static final int MAX_AGENTS = 200;

    /** When an install was last requested here (through the plugin manager), {@link System#currentTimeMillis}. */
    static volatile long lastInstallAt;

    /** A member's report, as parsed back. */
    record Info(int busy, int total, Map<String, int[]> agents, int installing, long installAt) {
        static final Info NONE = new Info(0, 0, Map.of(), 0, 0);

        String toJson() {
            JSONObject o = new JSONObject();
            o.put("busy", busy);
            o.put("total", total);
            JSONObject a = new JSONObject();
            agents.forEach((name, counts) -> a.put(name, JSONArray.fromObject(new int[] {counts[0], counts[1]})));
            o.put("agents", a);
            o.put("installing", installing);
            o.put("installAt", installAt);
            return o.toString();
        }

        @NonNull
        static Info parse(@CheckForNull String json) {
            if (json == null || json.isBlank()) {
                return NONE; // an older member
            }
            try {
                JSONObject o = JSONObject.fromObject(json);
                Map<String, int[]> agents = new LinkedHashMap<>();
                JSONObject a = o.optJSONObject("agents");
                if (a != null) {
                    for (Object name : a.keySet()) {
                        JSONArray counts = a.getJSONArray((String) name);
                        agents.put((String) name, new int[] {counts.getInt(0), counts.getInt(1)});
                    }
                }
                return new Info(o.optInt("busy"), o.optInt("total"), Collections.unmodifiableMap(agents),
                        o.optInt("installing"), o.optLong("installAt"));
            } catch (RuntimeException e) {
                return NONE;
            }
        }
    }

    @Initializer(after = InitMilestone.JOB_LOADED)
    public static void start() {
        if (!(PersistenceStores.get() instanceof PostgresPersistenceStore)) {
            return;
        }
        Timer.get().scheduleWithFixedDelay(MemberStatus::publish, 0, 2, TimeUnit.SECONDS);
    }

    static void publish() {
        try {
            PostgresPersistenceStore.memberInfo = compute().toJson();
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, "Could not compute this controller's status", e);
        }
    }

    static Info compute() {
        Jenkins j = Jenkins.get();
        int busy = 0;
        int total = 0;
        Map<String, int[]> agents = new LinkedHashMap<>();
        for (Computer c : j.getComputers()) {
            // agents held by another controller are offline here: count only what this controller can run on
            if (!c.isOnline()) {
                continue;
            }
            int b = c.countBusy();
            int t = c.countExecutors();
            busy += b;
            total += t;
            if (agents.size() < MAX_AGENTS) {
                agents.put(c.getName().isEmpty() ? "(built-in)" : c.getName(), new int[] {b, t});
            }
        }
        return new Info(busy, total, agents, activeInstalls(j.getUpdateCenter()), lastInstallAt);
    }

    /** Update-center downloads not finished yet. */
    static int activeInstalls(UpdateCenter uc) {
        int n = 0;
        for (UpdateCenter.UpdateCenterJob job : uc.getJobs()) {
            if (job instanceof UpdateCenter.DownloadJob dj
                    && (dj.status instanceof UpdateCenter.DownloadJob.Pending || dj.status instanceof UpdateCenter.DownloadJob.Installing)) {
                n++;
            }
        }
        return n;
    }

    private MemberStatus() {}
}
