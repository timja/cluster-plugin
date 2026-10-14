package io.jenkins.plugins.cnj.store;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.util.PluginServletFilter;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import jenkins.persistence.PersistenceStores;

/**
 * The install progress page and its polling go to the controller running the install (Target 15).
 *
 * <p>Update-center jobs live in the memory of the controller that runs them, so behind a load balancer without
 * stickiness every other refresh of the progress page reached a controller without them, and showed nothing. Now
 * an install through the plugin manager sets a cookie naming the controller ({@link ClusterPluginSet}), and these
 * pages are forwarded to it while it is alive and has run an install recently; for a browser without the cookie,
 * to the controller with installs running. The other controllers apply the change from the cluster's plugin set,
 * which the cluster view and the notice on the plugin manager pages show.
 */
public final class UpdateCenterRouting implements Filter {

    private static final Logger LOGGER = Logger.getLogger(UpdateCenterRouting.class.getName());

    static final String COOKIE = "CNJ-UC";
    /** How long after its last install a controller is still the one to show progress. */
    static final long RECENT_MILLIS = TimeUnit.HOURS.toMillis(1);

    static final Pattern PROGRESS = Pattern.compile("^(?:/manage)?/(?:"
            + "pluginManager/updates(?:/(?:body|api/.*)?)?"
            + "|updateCenter(?:/(?:body|installStatus|incompleteInstallStatus|api/.*)?)?"
            + ")/?$");

    @Initializer(after = InitMilestone.PLUGINS_STARTED)
    public static void install() throws ServletException {
        PluginServletFilter.addFilter(new UpdateCenterRouting());
    }

    /**
     * Which controller should answer: the one the browser installed on (its cookie lasts {@link #RECENT_MILLIS}), if
     * it is alive; else the live one with installs running (the most recent); else none (answer locally). The cookie
     * is trusted as is: right after the install, the others have not seen that controller's next heartbeat yet.
     */
    @CheckForNull
    static ClusterStore.Member target(List<ClusterStore.Member> members, @CheckForNull String preferred) {
        ClusterStore.Member best = null;
        long bestAt = -1;
        for (ClusterStore.Member m : members) {
            if (!m.alive() || m.url() == null) {
                continue;
            }
            if (preferred != null && preferred.equals(m.name())) {
                return m;
            }
            MemberStatus.Info info = MemberStatus.Info.parse(m.info());
            if (info.installing() > 0 && info.installAt() > bestAt) {
                best = m;
                bestAt = info.installAt();
            }
        }
        return best;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        if (request instanceof HttpServletRequest req
                && response instanceof HttpServletResponse rsp
                && !Forwarding.wasForwarded(req)
                && PersistenceStores.get() instanceof ClusterStore store
                && PROGRESS.matcher(req.getRequestURI().substring(req.getContextPath().length())).matches()) {
            try {
                ClusterStore.Member t = target(store.members(), cookie(req));
                if (t != null && !ClusterMember.NAME.equals(t.name()) && Forwarding.forward(req, rsp, t.name(), t.url())) {
                    return;
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.FINE, "Could not route the install progress; answering it here", e);
            }
        }
        chain.doFilter(request, response);
    }

    @CheckForNull
    private static String cookie(HttpServletRequest req) {
        Cookie[] cookies = req.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (COOKIE.equals(c.getName())) {
                    return c.getValue();
                }
            }
        }
        return null;
    }

    /** Remembers, for this browser, that the install it just requested runs on this controller. */
    static void installedHere(HttpServletRequest req, HttpServletResponse rsp) {
        MemberStatus.lastInstallAt = System.currentTimeMillis();
        MemberStatus.publish();
        Cookie c = new Cookie(COOKIE, ClusterMember.NAME);
        c.setPath(req.getContextPath().isEmpty() ? "/" : req.getContextPath());
        c.setMaxAge((int) TimeUnit.MILLISECONDS.toSeconds(RECENT_MILLIS));
        c.setHttpOnly(true);
        rsp.addCookie(c);
    }
}
