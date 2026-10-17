package io.jenkins.plugins.cluster;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.util.PluginServletFilter;
import io.jenkins.plugins.cluster.spi.ClusterStore;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.persistence.PersistenceStores;
import org.kohsuke.stapler.bind.BoundObjectTable;

/**
 * Forwards requests for another controller's bound objects to that controller.
 *
 * <p>Some UI pieces ({@code renderOnDemand}, progressive rendering, JavaScript proxies) are live objects held in the session
 * of the controller which rendered the page, called back at {@code /$stapler/bound/<id>}. With Stapler's bound id prefix
 * ({@code -Dorg.kohsuke.stapler.bind.BoundObjectTable.idPrefix=<controller name>}), their ids start with that controller's
 * name; when such a request reaches another controller (browsers are not pinned), it is forwarded, with its cookies, to
 * the owner's internal URL (from the membership table), and the answer is relayed. The server id in a URL is chosen by
 * the client: it only routes the request (with the client's own cookies), and authorizes nothing.
 *
 * <p>Stapler's {@code BoundObjectTable.getIdPrefix()} and {@code serverIdOf(String)} are looked up at runtime: with a
 * Stapler that lacks them (ids are never prefixed), nothing is forwarded.
 */
public final class BoundRequestForwarder implements Filter {

    private static final Logger LOGGER = Logger.getLogger(BoundRequestForwarder.class.getName());
    private static final AtomicLong forwarded = new AtomicLong();

    /** {@code BoundObjectTable.getIdPrefix()}, or null if this Stapler has none. */
    @CheckForNull
    private static final MethodHandle ID_PREFIX = lookup("getIdPrefix", MethodType.methodType(String.class));

    /** {@code BoundObjectTable.serverIdOf(String)}, or null if this Stapler has none. */
    @CheckForNull
    private static final MethodHandle SERVER_ID_OF =
            lookup("serverIdOf", MethodType.methodType(String.class, String.class));

    @CheckForNull
    private static MethodHandle lookup(String name, MethodType type) {
        try {
            return MethodHandles.publicLookup().findStatic(BoundObjectTable.class, name, type);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            return null;
        }
    }

    /** Requests forwarded so far, for diagnostics. */
    public static long getForwardedCount() {
        return forwarded.get();
    }

    /** This controller's bound id prefix, or empty if ids are not prefixed (or this Stapler cannot). */
    @NonNull
    static String idPrefix() {
        if (ID_PREFIX == null || SERVER_ID_OF == null) {
            return "";
        }
        try {
            return (String) ID_PREFIX.invokeExact();
        } catch (Throwable e) {
            LOGGER.log(Level.WARNING, "Cannot read the bound id prefix", e);
            return "";
        }
    }

    @CheckForNull
    private static String serverIdOf(String id) {
        try {
            return SERVER_ID_OF == null ? null : (String) SERVER_ID_OF.invokeExact(id);
        } catch (Throwable e) {
            LOGGER.log(Level.FINE, "Cannot parse the bound id " + id, e);
            return null;
        }
    }

    @Initializer(after = InitMilestone.PLUGINS_STARTED)
    public static void install() throws ServletException {
        if (!idPrefix().isEmpty()) {
            PluginServletFilter.addFilter(new BoundRequestForwarder());
        }
    }

    /** The controller owning a bound object, from its id, or null if it is ours (or not prefixed). */
    @CheckForNull
    static String ownerOf(String path) {
        return ownerOf(path, idPrefix(), BoundRequestForwarder::serverIdOf);
    }

    @CheckForNull
    static String ownerOf(String path, String ours, Function<String, String> serverIdOf) {
        // the last one: the script endpoint is /$stapler/bound/script/$stapler/bound/<id>
        int start = path.lastIndexOf(BoundObjectTable.PREFIX);
        if (start < 0) {
            return null;
        }
        String rest = path.substring(start + BoundObjectTable.PREFIX.length());
        int slash = rest.indexOf('/');
        String owner = serverIdOf.apply(slash < 0 ? rest : rest.substring(0, slash));
        return owner == null || owner.equals(ours) ? null : owner;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest req)
                || !(response instanceof HttpServletResponse rsp)
                || Forwarding.wasForwarded(req)) {
            chain.doFilter(request, response);
            return;
        }
        String owner = ownerOf(req.getRequestURI());
        String url = null;
        if (owner != null && PersistenceStores.get() instanceof ClusterStore store) {
            url = store.urlOf(owner);
        }
        if (url == null) {
            chain.doFilter(request, response);
            return;
        }
        if (Forwarding.forward(req, rsp, owner, url)) {
            forwarded.incrementAndGet();
        } else {
            LOGGER.warning(() -> "Failed to forward " + req.getRequestURI() + " to " + owner);
            rsp.sendError(HttpServletResponse.SC_BAD_GATEWAY, "Could not reach " + owner);
        }
    }
}
