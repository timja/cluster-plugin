package io.jenkins.plugins.cnj.store;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.Computer;
import hudson.model.Job;
import hudson.util.PluginServletFilter;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.persistence.PersistenceStores;
import jenkins.persistence.RunningBuilds;

/**
 * Forwards requests for things another controller holds to that controller (Target 7):
 * <ul>
 * <li>a build running elsewhere ({@code /job/a/job/b/<N>/…}: its page, live console, {@code input}, stop, replay, REST API);
 * <li>an agent whose channel another controller holds ({@code /computer/<name>/…}: its page, log, script console).
 * </ul>
 * The owner is named by the build's running placeholder, or by the channel table, and reached at its internal URL from the
 * membership table. A request is answered locally when the thing is here, when the owner is not alive, or when the request
 * was itself forwarded.
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

    @Initializer(after = InitMilestone.PLUGINS_STARTED)
    public static void install() throws ServletException {
        // always: the store may not be created yet at this point; each request checks for it
        PluginServletFilter.addFilter(new OwnerRoutingFilter());
    }

    /** A build addressed by a URL: the job's full name and the build number. */
    record BuildPath(String job, int number) {}

    /**
     * The build a path (below the context path) addresses, if it has the shape {@code [/view/v…]/job/a[/job/b…]/<N>[/…]}.
     */
    @CheckForNull
    static BuildPath buildPath(@NonNull String path) {
        String[] segments = path.split("/");
        int i = 0;
        while (i < segments.length && segments[i].isEmpty()) {
            i++;
        }
        while (i + 1 < segments.length && segments[i].equals("view")) {
            i += 2;
        }
        List<String> names = new ArrayList<>();
        while (i + 1 < segments.length && segments[i].equals("job")) {
            names.add(decode(segments[i + 1]));
            i += 2;
        }
        if (names.isEmpty() || i >= segments.length || !segments[i].matches("[1-9][0-9]{0,9}")) {
            return null;
        }
        long n = Long.parseLong(segments[i]);
        return n > Integer.MAX_VALUE ? null : new BuildPath(String.join("/", names), (int) n);
    }

    /** The agent a path addresses, if it has the shape {@code /computer/<name>[/…]}. */
    @CheckForNull
    static String computerPath(@NonNull String path) {
        String[] segments = path.split("/");
        int i = 0;
        while (i < segments.length && segments[i].isEmpty()) {
            i++;
        }
        if (i + 1 < segments.length && segments[i].equals("computer")) {
            String name = decode(segments[i + 1]);
            return name.isEmpty() || name.equals("(built-in)") || name.equals("api") ? null : name;
        }
        return null;
    }

    private static String decode(String segment) {
        return URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    /** Names the controller answering, on every response (behind any load balancer). */
    static final String SERVED_BY = "X-CNJ-Controller";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        if (response instanceof HttpServletResponse r) {
            r.setHeader(SERVED_BY, PostgresPersistenceStore.NAME); // a forwarded answer overrides it with the owner's
        }
        if (request instanceof HttpServletRequest req
                && response instanceof HttpServletResponse rsp
                && !Forwarding.wasForwarded(req)
                && PersistenceStores.get() instanceof PostgresPersistenceStore store) {
            String path = req.getRequestURI().substring(req.getContextPath().length());
            try {
                BuildPath build = buildPath(path);
                if (build != null) {
                    RunningBuilds.Placeholder p = ownerOf(build);
                    // by origin: a controller replacing the owner under the same name (a restarted pod, a restored
                    // cluster) does not have the build
                    String url = p == null ? null : store.urlOfOrigin(p.getOrigin());
                    String owner = p == null ? null : p.getController();
                    if (url != null && Forwarding.forward(req, rsp, owner, url)) {
                        builds.incrementAndGet();
                        return;
                    }
                } else {
                    String agent = computerPath(path);
                    String[] holder = agent == null || isOnlineHere(agent) ? null : store.channelHolder(agent);
                    if (holder != null && Forwarding.forward(req, rsp, holder[0], holder[1])) {
                        computers.incrementAndGet();
                        return;
                    }
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.WARNING, "Could not route " + path + "; answering it here", e);
            }
        }
        chain.doFilter(request, response);
    }

    /** The placeholder of a build running on another controller, which this one does not have, or null. */
    @CheckForNull
    private static RunningBuilds.Placeholder ownerOf(BuildPath build) {
        Job<?, ?> job = Jenkins.get().getItemByFullName(build.job(), Job.class); // null unless the user may read it
        if (job == null || job.getBuildByNumber(build.number()) != null) {
            return null;
        }
        for (RunningBuilds.Placeholder p : RunningBuilds.elsewhere(job)) {
            if (p.getNumber() == build.number()) {
                return p;
            }
        }
        return null;
    }

    private static boolean isOnlineHere(String agent) {
        Computer c = Jenkins.get().getComputer(agent);
        return c == null || c.isOnline(); // unknown here: nothing to route to; online here: ours
    }
}
