package io.jenkins.plugins.cluster;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.Computer;
import hudson.model.Job;
import hudson.util.PluginServletFilter;
import io.jenkins.plugins.cluster.agents.LaunchLease;
import io.jenkins.plugins.cluster.builds.RunningBuilds;
import io.jenkins.plugins.cluster.spi.ClusterMember;
import io.jenkins.plugins.cluster.spi.ClusterStore;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.persistence.PersistenceStores;

/**
 * Sends requests for what another controller holds to that controller, by ordered {@link Rule}s:
 * <ul>
 * <li>a build running elsewhere ({@code /job/a/job/b/<N>/…}: its page, live console, {@code input}, stop, replay, REST API);
 * <li>a bound object of another controller ({@link BoundRequestForwarder});
 * <li>a remote lock of another controller ({@link RemoteLockForwarder});
 * <li>the install progress pages ({@link UpdateCenterRouting});
 * <li>an agent another controller connects ({@code /computer/<name>/…}: its page, log, script console), for users who
 * may read this controller;
 * <li>a job's {@code lastBuild} while its newest build runs elsewhere (redirected to that build), and its SCM polling log
 * (on the controller which polled).
 * </ul>
 * Each rule names the controller by what it holds (a build's running placeholder, an id, the channel table, a launch
 * lease), and the request is always sent to that controller's internal URL from the membership table. A request is
 * answered locally when nothing of it is elsewhere, when the controller holding it is not alive, when the request was
 * itself forwarded ({@link Forwarding#wasForwarded}), or when its path does not read as it is written
 * ({@link JobPath#segments}).
 */
public final class OwnerRoutingFilter implements Filter {

    private static final Logger LOGGER = Logger.getLogger(OwnerRoutingFilter.class.getName());
    private static final AtomicLong builds = new AtomicLong();
    private static final AtomicLong computers = new AtomicLong();

    /** Requests for builds running elsewhere forwarded so far, for diagnostics. */
    public static long getForwardedBuildRequests() {
        return builds.get();
    }

    /** Requests for agents connected elsewhere forwarded so far, for diagnostics. */
    public static long getForwardedComputerRequests() {
        return computers.get();
    }

    /** Where a request goes instead of being answered here. */
    sealed interface Route permits Forward, Redirect {}

    /**
     * To another controller.
     *
     * @param owner the controller's name, as told in logs and errors
     * @param url its internal URL, from the membership table
     * @param mustReach whether the request fails (502) when the controller cannot be reached, rather than being answered
     *     here: what only that controller has (a bound object, a lock) is not here either
     * @param counter counted when forwarded, if anything
     */
    record Forward(
            @NonNull String owner,
            @NonNull String url,
            boolean mustReach,
            @CheckForNull AtomicLong counter) implements Route {}

    /** To another URL of this controller (the same path below the context, then). */
    record Redirect(@NonNull String location) implements Route {}

    /** Where requests of some shape go. */
    @FunctionalInterface
    interface Rule {
        /**
         * Where a request goes, or null to leave it to the next rule.
         *
         * @param path the request's path below the context path, which reads as it is written
         */
        @CheckForNull
        Route route(@NonNull HttpServletRequest req, @NonNull String path, @NonNull ClusterStore store)
                throws IOException;
    }

    /** In order: the first rule which routes a request decides. */
    private static final List<Rule> RULES = List.of(
            OwnerRoutingFilter::build,
            BoundRequestForwarder::route,
            RemoteLockForwarder::route,
            UpdateCenterRouting::route,
            OwnerRoutingFilter::computer,
            OwnerRoutingFilter::lastBuild,
            OwnerRoutingFilter::pollLog);

    @Initializer(after = InitMilestone.PLUGINS_STARTED)
    public static void install() throws ServletException {
        // always: the store may not be created yet at this point; each request checks for it
        PluginServletFilter.addFilter(new OwnerRoutingFilter());
    }

    /** Names the controller answering, on every response (behind any load balancer). */
    static final String SERVED_BY = "X-Jenkins-Cluster-Controller";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (response instanceof HttpServletResponse r) {
            r.setHeader(SERVED_BY, ClusterMember.NAME); // a forwarded answer overrides it with the owner's
        }
        if (request instanceof HttpServletRequest req
                && response instanceof HttpServletResponse rsp
                && !Forwarding.wasForwarded(req)
                && PersistenceStores.get() instanceof ClusterStore store) {
            String path = req.getRequestURI().substring(req.getContextPath().length());
            Route route = JobPath.segments(path) == null ? null : route(req, path, store);
            if (route instanceof Redirect redirect) {
                rsp.sendRedirect(req.getContextPath()
                        + redirect.location()
                        + (req.getQueryString() == null ? "" : "?" + req.getQueryString()));
                return;
            }
            if (route instanceof Forward to) {
                if (relay(req, rsp, to.owner(), to.url())) {
                    if (to.counter() != null) {
                        to.counter().incrementAndGet();
                    }
                    return;
                }
                if (to.mustReach()) {
                    LOGGER.warning(() -> "Failed to forward " + path + " to " + to.owner());
                    rsp.sendError(HttpServletResponse.SC_BAD_GATEWAY, "Could not reach " + to.owner());
                    return;
                }
            }
        }
        chain.doFilter(request, response);
    }

    @CheckForNull
    private static Route route(HttpServletRequest req, String path, ClusterStore store) {
        try {
            for (Rule rule : RULES) {
                Route route = rule.route(req, path, store);
                if (route != null) {
                    return route;
                }
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not route " + path + "; answering it here", e);
        }
        return null;
    }

    /** Forwards a request to its owner: a WebSocket through {@link WebSocketProxy}, anything else through {@link Forwarding}. */
    private static boolean relay(HttpServletRequest req, HttpServletResponse rsp, String owner, String url)
            throws IOException, ServletException {
        return WebSocketProxy.isUpgrade(req)
                ? WebSocketProxy.proxy(req, rsp, owner, url)
                : Forwarding.forward(req, rsp, owner, url);
    }

    /** A build addressed by a URL: the job's full name and the build number. */
    record BuildPath(String job, int number) {}

    /**
     * The build a path (below the context path) addresses, if it has the shape {@code [/view/v…]/job/a[/job/b…]/<N>[/…]}.
     */
    @CheckForNull
    static BuildPath buildPath(@NonNull String path) {
        JobPath p = JobPath.parse(path);
        String n = p == null ? null : p.next();
        if (n == null || !n.matches("[1-9][0-9]{0,9}")) {
            return null;
        }
        long number = Long.parseLong(n);
        return number > Integer.MAX_VALUE ? null : new BuildPath(p.fullName(), (int) number);
    }

    /** A build running on another controller, which this one does not have: to the controller running it. */
    @CheckForNull
    private static Route build(HttpServletRequest req, String path, ClusterStore store) throws IOException {
        BuildPath build = buildPath(path);
        Job<?, ?> job = build == null ? null : Jenkins.get().getItemByFullName(build.job(), Job.class);
        // null unless the user may read it
        if (job == null || job.getBuildByNumber(build.number()) != null) {
            return null;
        }
        for (RunningBuilds.Placeholder p : RunningBuilds.elsewhere(job)) {
            if (p.getNumber() == build.number()) {
                // by origin: a controller replacing the owner under the same name (a restarted pod, a restored
                // cluster) does not have the build
                String url = store.urlOfOrigin(p.getOrigin());
                return url == null ? null : new Forward(p.getController(), url, false, builds);
            }
        }
        return null;
    }

    /** A job's {@code lastBuild} while its newest build runs on another controller: to that build. */
    @CheckForNull
    private static Route lastBuild(HttpServletRequest req, String path, ClusterStore store) {
        JobPath p = JobPath.parse(path);
        if (p == null
                || !"lastBuild".equals(p.next())
                || !(Jenkins.get().getItemByFullName(p.fullName(), Job.class) instanceof Job<?, ?> job)) {
            return null;
        }
        int n = RunningBuilds.newerElsewhere(job);
        return n > 0 ? new Redirect(p.before() + "/" + n + "/" + p.afterNext()) : null;
    }

    static final String SCM_TRIGGER = "hudson.triggers.SCMTrigger";

    /** A job's SCM polling log: polling runs once per tick, on whichever controller claimed it; its log is there. */
    @CheckForNull
    private static Route pollLog(HttpServletRequest req, String path, ClusterStore store) throws IOException {
        JobPath p = JobPath.parse(path);
        if (p == null
                || !"scmPollLog".equals(p.next())
                || Jenkins.get().getItemByFullName(p.fullName(), Job.class) == null) {
            return null;
        }
        String claimant = store.lastClaimant(p.fullName(), SCM_TRIGGER);
        String url = claimant == null || claimant.equals(store.getOrigin()) ? null : store.urlOfOrigin(claimant);
        return url == null ? null : new Forward("the controller that polled", url, false, null);
    }

    /** The agent a path addresses, if it has the shape {@code /computer/<name>[/…]} and reads as it is written. */
    @CheckForNull
    static String computerPath(@NonNull String path) {
        List<String> segments = JobPath.segments(path);
        if (segments == null) {
            return null;
        }
        int i = 0;
        while (i < segments.size() && segments.get(i).isEmpty()) {
            i++;
        }
        if (i + 1 < segments.size() && segments.get(i).equals("computer")) {
            String name = segments.get(i + 1);
            return name.isEmpty() || name.equals("(built-in)") || name.equals("api") ? null : name;
        }
        return null;
    }

    /** An agent offline here which another controller connects: to that controller. */
    @CheckForNull
    private static Route computer(HttpServletRequest req, String path, ClusterStore store) throws IOException {
        String agent = computerPath(path);
        String[] holder = agent == null ? null : computerHolder(store, agent);
        return holder == null ? null : new Forward(holder[0], holder[1], false, computers);
    }

    /**
     * The name and internal URL of the live controller connecting an agent that is offline here: the one holding its
     * channel, or else (while it launches it, before the channel is up) its launch lease. Null for an agent online
     * here, or that this controller does not have (nothing to route to).
     */
    @CheckForNull
    static String[] computerHolder(@NonNull ClusterStore store, @NonNull String agent) throws IOException {
        Jenkins jenkins = Jenkins.get();
        if (!jenkins.hasPermission(Jenkins.READ)) {
            return null; // answered here (with a login prompt): which agents exist, and where, is not told
        }
        Computer c = jenkins.getComputer(agent);
        if (c == null || c.isOnline()) {
            return null;
        }
        String[] holder = store.channelHolder(agent);
        if (holder != null) {
            return holder[1] == null ? null : holder;
        }
        String origin = LaunchLease.holderElsewhere(store, c);
        String url = origin == null ? null : store.urlOfOrigin(origin);
        return url == null ? null : new String[] {LaunchLease.holderName(store, agent), url};
    }
}
