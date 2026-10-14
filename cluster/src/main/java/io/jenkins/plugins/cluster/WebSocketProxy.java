package io.jenkins.plugins.cluster;

import io.jenkins.plugins.cluster.spi.ClusterMember;
import edu.umd.cs.findbugs.annotations.NonNull;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.websocket.WebSocketSession;
import jenkins.websocket.WebSockets;

/**
 * Relays a WebSocket request for something only another controller has (a build running there, an agent connected
 * there) to that controller (Target 15), as {@link Forwarding} does for plain HTTP: the client's connection is accepted
 * here, a second one is opened to the owner, and frames are copied both ways until either side closes.
 *
 * <p>Inbound agents are not relayed: an agent connects to whichever controller its URL reaches and stays there
 * (Target 7); this is for WebSocket endpoints under a build's or an agent's URL.
 */
final class WebSocketProxy {

    private static final Logger LOGGER = Logger.getLogger(WebSocketProxy.class.getName());

    /** Request headers not passed on: the WebSocket handshake's own, and hop-by-hop ones. */
    private static final Set<String> NOT_PASSED = Set.of("connection", "upgrade", "host", "content-length", "sec-websocket-key",
            "sec-websocket-version", "sec-websocket-extensions", "sec-websocket-accept", "sec-websocket-protocol");

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private WebSocketProxy() {}

    static boolean isUpgrade(@NonNull HttpServletRequest req) {
        String upgrade = req.getHeader("Upgrade");
        return upgrade != null && upgrade.toLowerCase(Locale.ROOT).contains("websocket");
    }

    /**
     * @return false if the owner could not be reached and nothing was written, so the caller may serve it locally
     */
    static boolean proxy(@NonNull HttpServletRequest req, @NonNull HttpServletResponse rsp, @NonNull String owner, @NonNull String url)
            throws IOException, ServletException {
        if (!WebSockets.isSupported()) {
            return false;
        }
        String path = req.getRequestURI().substring(req.getContextPath().length());
        String target = url.replaceAll("/+$", "").replaceFirst("^http", "ws") + req.getContextPath() + path
                + (req.getQueryString() == null ? "" : "?" + req.getQueryString());
        Front front = new Front();
        WebSocket.Builder b = HTTP.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10));
        for (String name : java.util.Collections.list(req.getHeaderNames())) {
            if (!NOT_PASSED.contains(name.toLowerCase(Locale.ROOT))) {
                for (String value : java.util.Collections.list(req.getHeaders(name))) {
                    try {
                        b.header(name, value);
                    } catch (IllegalArgumentException restricted) {
                        // a header java.net.http sets itself
                    }
                }
            }
        }
        b.header(Forwarding.FORWARDED, ClusterMember.NAME);
        String protocols = req.getHeader("Sec-WebSocket-Protocol");
        if (protocols != null && !protocols.isBlank()) {
            String[] p = protocols.split("\\s*,\\s*");
            b.subprotocols(p[0], java.util.Arrays.copyOfRange(p, 1, p.length));
        }
        WebSocket backend;
        try {
            backend = b.buildAsync(URI.create(target), new Back(front)).get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOGGER.log(Level.FINE, e, () -> "Could not open a WebSocket to " + owner + " for " + path);
            return false;
        }
        front.backend = backend;
        LOGGER.fine(() -> "Relaying WebSocket " + path + " to " + owner);
        WebSockets.upgradeResponse(front, req, rsp);
        return true;
    }

    /** The client's side, accepted here. */
    private static final class Front extends WebSocketSession {
        volatile WebSocket backend;
        final CountDownLatch open = new CountDownLatch(1);

        @Override
        protected void opened() {
            open.countDown();
        }

        @Override
        protected void text(String message) {
            backend.sendText(message, true).join();
        }

        @Override
        protected void binary(byte[] payload, int offset, int len) {
            backend.sendBinary(ByteBuffer.wrap(payload, offset, len), true).join();
        }

        @Override
        protected void closed(int statusCode, String reason) {
            WebSocket w = backend;
            if (w != null && !w.isOutputClosed()) {
                w.sendClose(statusCode >= 1000 && statusCode < 5000 && statusCode != 1005 && statusCode != 1006 ? statusCode : WebSocket.NORMAL_CLOSURE,
                        reason == null ? "" : reason);
            }
        }

        @Override
        protected void error(Throwable cause) {
            LOGGER.log(Level.FINE, "WebSocket relay to the owner failed", cause);
            WebSocket w = backend;
            if (w != null) {
                w.abort();
            }
        }

        void awaitOpen() {
            try {
                open.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        void sendTextHere(String text) throws IOException {
            awaitOpen();
            sendText(text);
        }

        void sendBinaryHere(ByteBuffer data, boolean last) throws IOException {
            awaitOpen();
            sendBinary(data, last);
        }

        void closeHere() throws IOException {
            awaitOpen();
            close();
        }
    }

    /** The owner's side, opened from here. */
    private static final class Back implements WebSocket.Listener {
        private final Front front;
        private final StringBuilder text = new StringBuilder();

        Back(Front front) {
            this.front = front;
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            text.append(data);
            if (last) {
                String message = text.toString();
                text.setLength(0);
                try {
                    front.sendTextHere(message);
                } catch (IOException e) {
                    ws.abort();
                }
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
            ByteBuffer copy = ByteBuffer.allocate(data.remaining());
            copy.put(data).flip();
            try {
                front.sendBinaryHere(copy, last);
            } catch (IOException e) {
                ws.abort();
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            try {
                front.closeHere();
            } catch (IOException e) {
                LOGGER.log(Level.FINE, "Could not close the client's WebSocket", e);
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            LOGGER.log(Level.FINE, "The owner's WebSocket failed", error);
            try {
                front.closeHere();
            } catch (IOException e) {
                // already gone
            }
        }

        @Override
        public void onOpen(WebSocket ws) {
            ws.request(1);
        }
    }
}
