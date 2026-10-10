package io.jenkins.plugins.cnj.store;

import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.util.PluginServletFilter;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.persistence.PersistenceStores;
import org.kohsuke.stapler.bind.BoundObjectTable;

/**
 * Forwards requests for another controller's bound objects to that controller.
 *
 * <p>Some UI pieces ({@code renderOnDemand}, progressive rendering, JavaScript proxies) are live objects held in the session
 * of the controller which rendered the page, called back at {@code /$stapler/bound/<id>}. Their ids start with that
 * controller's name ({@link BoundObjectTable#ID_PREFIX}); when such a request reaches another controller (browsers are not pinned),
 * it is forwarded, with its cookies, to the owner's internal URL (from the membership table), and the answer is relayed.
 */
public final class BoundRequestForwarder implements Filter {

    private static final Logger LOGGER = Logger.getLogger(BoundRequestForwarder.class.getName());
    static final String FORWARDED = "X-CNJ-Forwarded-From";
    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection",
            "content-length",
            "host",
            "transfer-encoding",
            "keep-alive",
            "upgrade",
            "te",
            "trailer",
            "proxy-connection",
            "expect");
    private static final AtomicLong forwarded = new AtomicLong();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /** Requests forwarded so far, for diagnostics. */
    public static long getForwardedCount() {
        return forwarded.get();
    }

    @Initializer(after = InitMilestone.PLUGINS_STARTED)
    public static void install() throws ServletException {
        String prefix = BoundObjectTable.ID_PREFIX;
        if (prefix != null && !prefix.isEmpty()) {
            PluginServletFilter.addFilter(new BoundRequestForwarder());
        }
    }

    /** The controller owning a bound object, from its id, or null if it is ours (or not prefixed). */
    static String ownerOf(String path) {
        // the last one: the script endpoint is /$stapler/bound/script/$stapler/bound/<id>
        int start = path.lastIndexOf(BoundObjectTable.PREFIX);
        if (start < 0) {
            return null;
        }
        String rest = path.substring(start + BoundObjectTable.PREFIX.length());
        int slash = rest.indexOf('/');
        String id = slash < 0 ? rest : rest.substring(0, slash);
        if (id.length() <= 37 || id.charAt(id.length() - 37) != '-') {
            return null; // not prefixed (or the script endpoint)
        }
        String owner = id.substring(0, id.length() - 37);
        return owner.equals(BoundObjectTable.ID_PREFIX) ? null : owner;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest req)
                || !(response instanceof HttpServletResponse rsp)
                || req.getHeader(FORWARDED) != null) {
            chain.doFilter(request, response);
            return;
        }
        String owner = ownerOf(req.getRequestURI());
        String url = null;
        if (owner != null && PersistenceStores.get() instanceof PostgresPersistenceStore store) {
            url = store.urlOf(owner);
        }
        if (url == null) {
            chain.doFilter(request, response);
            return;
        }
        String path = req.getRequestURI().substring(req.getContextPath().length());
        String target = url.replaceAll("/+$", "")
                + req.getContextPath()
                + path
                + (req.getQueryString() == null ? "" : "?" + req.getQueryString());
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(target)).timeout(Duration.ofSeconds(60));
            for (String name : Collections.list(req.getHeaderNames())) {
                if (!HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT))) {
                    for (String value : Collections.list(req.getHeaders(name))) {
                        b.header(name, value);
                    }
                }
            }
            b.header(FORWARDED, PostgresPersistenceStore.NAME);
            byte[] body;
            try (InputStream in = req.getInputStream()) {
                body = in.readAllBytes();
            }
            b.method(
                    req.getMethod(),
                    body.length == 0
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofByteArray(body));
            HttpResponse<InputStream> r = http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
            rsp.setStatus(r.statusCode());
            for (Map.Entry<String, List<String>> h : r.headers().map().entrySet()) {
                if (!HOP_BY_HOP.contains(h.getKey().toLowerCase(Locale.ROOT))
                        && !h.getKey().startsWith(":")) {
                    for (String value : h.getValue()) {
                        rsp.addHeader(h.getKey(), value);
                    }
                }
            }
            rsp.setHeader(FORWARDED, owner);
            try (InputStream in = r.body();
                    OutputStream out = rsp.getOutputStream()) {
                in.transferTo(out);
            }
            forwarded.incrementAndGet();
            LOGGER.fine(() -> "Forwarded " + req.getMethod() + " " + path + " to " + owner + ": " + r.statusCode());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to forward " + path + " to " + owner, e);
            rsp.sendError(HttpServletResponse.SC_BAD_GATEWAY, "Could not reach " + owner);
        }
    }
}
