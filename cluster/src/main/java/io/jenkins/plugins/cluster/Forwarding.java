package io.jenkins.plugins.cluster;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.plugins.cluster.spi.ClusterMember;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.security.HMACConfidentialKey;

/**
 * Relays a request to another controller's internal URL and its answer back, with the browser's cookies and credentials,
 * so that the other controller authenticates the user itself (Target 6 sessions and crumbs work on every controller).
 */
final class Forwarding {

    private static final Logger LOGGER = Logger.getLogger(Forwarding.class.getName());

    /**
     * Set on forwarded requests, and on their answers (to the owner's name). On a request it is
     * {@code <forwarding controller's name>;<time>;<MAC>}, authenticated with a key every controller of the cluster
     * shares ({@link #KEY}): a client cannot set it, so it cannot stop its request from being forwarded to the owner.
     * The client's own header of that name is never passed on.
     */
    static final String FORWARDED = "X-Jenkins-Cluster-Forwarded-From";

    /** Shared by the controllers: confidential keys are in the store ({@code storesConfidentialKeys}). */
    private static final HMACConfidentialKey KEY = new HMACConfidentialKey(Forwarding.class, "forwardedFrom", 32);

    /** How old a forwarded request's header may be (clocks differ a little; a hop takes milliseconds). */
    static final long MAX_AGE_MILLIS = Long.getLong(Forwarding.class.getName() + ".maxAgeSeconds", 300) * 1000;

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

    /**
     * How long to wait for the owner's response headers (not its body: consoles stream). An owner that is paused or
     * overloaded accepts the connection and never answers; after this a GET or HEAD is served locally, where the build
     * is, by then, usually adopted. Any other request gets 504: the owner may have acted on it.
     */
    static final long TIMEOUT_SECONDS = Long.getLong(Forwarding.class.getName() + ".timeoutSeconds", 20);

    /** Requests that may be served again locally when the owner's answer does not come: they change nothing. */
    private static final Set<String> SAFE = Set.of("GET", "HEAD");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private Forwarding() {}

    /** Whether this request was itself forwarded by another controller (never forward it again). */
    static boolean wasForwarded(@NonNull HttpServletRequest req) {
        java.util.Enumeration<String> values = req.getHeaders(FORWARDED);
        if (values == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        for (String v : Collections.list(values)) {
            if (isAuthentic(v, now, KEY::checkMac)) {
                return true;
            }
            LOGGER.fine(() -> "Ignoring an unauthenticated " + FORWARDED + " header: " + v);
        }
        return false;
    }

    /** The header a controller sets on a request it forwards. */
    @NonNull
    static String forwardedHeader() {
        return forwardedHeader(ClusterMember.NAME, System.currentTimeMillis(), KEY::mac);
    }

    @NonNull
    static String forwardedHeader(
            @NonNull String name, long now, @NonNull java.util.function.UnaryOperator<String> mac) {
        String signed = name.replace(";", "_") + ";" + now;
        return signed + ";" + mac.apply(signed);
    }

    /** Whether a {@link #FORWARDED} header was set by a controller of this cluster, recently. */
    static boolean isAuthentic(
            @NonNull String header, long now, @NonNull java.util.function.BiPredicate<String, String> checkMac) {
        int last = header.lastIndexOf(';');
        int first = header.indexOf(';');
        if (first <= 0 || last <= first) {
            return false;
        }
        long at;
        try {
            at = Long.parseLong(header.substring(first + 1, last));
        } catch (NumberFormatException e) {
            return false;
        }
        return Math.abs(now - at) <= MAX_AGE_MILLIS
                && checkMac.test(header.substring(0, last), header.substring(last + 1));
    }

    /**
     * Relays the request to {@code url} (the owner's internal root URL) and copies the answer.
     *
     * @return false if the owner could not be connected to (or, for a GET or HEAD, did not answer) and nothing was
     *     written, so the caller may serve it locally
     */
    static boolean forward(
            @NonNull HttpServletRequest req,
            @NonNull HttpServletResponse rsp,
            @NonNull String owner,
            @NonNull String url)
            throws IOException {
        return forward(req, rsp, owner, url, Duration.ofSeconds(TIMEOUT_SECONDS));
    }

    static boolean forward(
            @NonNull HttpServletRequest req,
            @NonNull HttpServletResponse rsp,
            @NonNull String owner,
            @NonNull String url,
            @NonNull Duration timeout)
            throws IOException {
        String path = req.getRequestURI().substring(req.getContextPath().length());
        String target = url.replaceAll("/+$", "")
                + req.getContextPath()
                + path
                + (req.getQueryString() == null ? "" : "?" + req.getQueryString());
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(target)).timeout(timeout);
        for (String name : Collections.list(req.getHeaderNames())) {
            if (!HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT)) && !name.equalsIgnoreCase(FORWARDED)) {
                for (String value : Collections.list(req.getHeaders(name))) {
                    b.header(name, value);
                }
            }
        }
        b.header(FORWARDED, forwardedHeader());
        byte[] body = body(req);
        b.method(
                req.getMethod(),
                body.length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        HttpResponse<InputStream> r;
        try {
            r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        } catch (IOException e) {
            if (e instanceof ConnectException
                    || e instanceof HttpConnectTimeoutException
                    || SAFE.contains(req.getMethod())) {
                LOGGER.log(Level.FINE, e, () -> "Could not reach " + owner + " for " + path);
                return false;
            }
            // sent, but no answer: the owner may have acted on it (a replay, an input), so running it here could do it
            // twice
            LOGGER.log(Level.WARNING, e, () -> "No answer from " + owner + " for " + req.getMethod() + " " + path);
            rsp.sendError(
                    HttpServletResponse.SC_GATEWAY_TIMEOUT,
                    "The controller running this (" + owner
                            + ") did not answer in time. It may have acted on the request: check before retrying.");
            return true;
        }
        rsp.setStatus(r.statusCode());
        for (Map.Entry<String, List<String>> h : r.headers().map().entrySet()) {
            if (!HOP_BY_HOP.contains(h.getKey().toLowerCase(Locale.ROOT))
                    && !h.getKey().startsWith(":")) {
                boolean first = true;
                for (String value : h.getValue()) {
                    if (first) {
                        rsp.setHeader(
                                h.getKey(), value); // the owner's answer replaces any set here (e.g. X-CNJ-Controller)
                        first = false;
                    } else {
                        rsp.addHeader(h.getKey(), value);
                    }
                }
            }
        }
        rsp.setHeader(FORWARDED, owner);
        try (InputStream in = r.body();
                OutputStream out = rsp.getOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) >= 0) {
                out.write(buffer, 0, n);
                out.flush(); // live consoles and progressive rendering
            }
        }
        LOGGER.fine(() -> "Forwarded " + req.getMethod() + " " + path + " to " + owner + ": " + r.statusCode());
        return true;
    }

    /**
     * The request body. A form body may already have been parsed by an earlier filter (the crumb filter reads its
     * parameters), leaving the stream empty: it is then rebuilt from the parameters not in the query string.
     */
    static byte[] body(@NonNull HttpServletRequest req) throws IOException {
        byte[] raw;
        try (InputStream in = req.getInputStream()) {
            raw = in.readAllBytes();
        }
        String type = req.getContentType();
        if (raw.length > 0
                || type == null
                || !type.toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded")) {
            return raw;
        }
        Map<String, Integer> inQuery = new HashMap<>();
        if (req.getQueryString() != null) {
            for (String pair : req.getQueryString().split("&")) {
                if (!pair.isEmpty()) {
                    String name = URLDecoder.decode(pair.split("=", 2)[0], StandardCharsets.UTF_8);
                    inQuery.merge(name, 1, Integer::sum);
                }
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Map.Entry<String, String[]> p : req.getParameterMap().entrySet()) {
            int skip = inQuery.getOrDefault(p.getKey(), 0);
            for (String value : p.getValue()) {
                if (skip > 0) {
                    skip--; // query parameters come first in the parameter map, and are forwarded in the URL
                    continue;
                }
                if (out.size() > 0) {
                    out.write('&');
                }
                out.writeBytes((URLEncoder.encode(p.getKey(), StandardCharsets.UTF_8) + "="
                                + URLEncoder.encode(value, StandardCharsets.UTF_8))
                        .getBytes(StandardCharsets.US_ASCII));
            }
        }
        return out.toByteArray();
    }
}
