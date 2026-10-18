package io.jenkins.plugins.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class ForwardingTest {

    private static ServletInputStream stream(String content) {
        ByteArrayInputStream in = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        return new ServletInputStream() {
            @Override
            public int read() {
                return in.read();
            }

            @Override
            public boolean isFinished() {
                return in.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener l) {}
        };
    }

    @Test
    void bodyAsSent() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getInputStream()).thenReturn(stream("[1,2]"));
        when(req.getContentType()).thenReturn("application/x-stapler-method-invocation;charset=UTF-8");
        assertEquals("[1,2]", new String(Forwarding.body(req), StandardCharsets.UTF_8));
    }

    @Test
    void formBodyAlreadyParsedIsRebuilt() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getInputStream()).thenReturn(stream("")); // read by the crumb filter's getParameter
        when(req.getContentType()).thenReturn("application/x-www-form-urlencoded; charset=UTF-8");
        when(req.getQueryString()).thenReturn("delay=0sec&a=q");
        Map<String, String[]> params = new LinkedHashMap<>();
        params.put("delay", new String[] {"0sec"}); // query only
        params.put("a", new String[] {"q", "body a"}); // query first, then the body
        params.put("json", new String[] {"{\"parameter\": [{\"name\": \"WHO\", \"value\": \"x&y\"}]}"});
        params.put("proceed", new String[] {"Proceed"});
        when(req.getParameterMap()).thenReturn(params);
        assertEquals(
                "a=body+a&json=%7B%22parameter%22%3A+%5B%7B%22name%22%3A+%22WHO%22%2C+%22value%22%3A+%22x%26y%22%7D%5D%7D&proceed=Proceed",
                new String(Forwarding.body(req), StandardCharsets.US_ASCII));
    }

    private static HttpServletRequest request(String method) throws java.io.IOException {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getMethod()).thenReturn(method);
        when(req.getRequestURI()).thenReturn("/job/p/1/replay/run");
        when(req.getContextPath()).thenReturn("");
        when(req.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
        when(req.getInputStream()).thenReturn(stream(""));
        return req;
    }

    @Test
    void noAnswerFallsBackOnlyForRequestsThatChangeNothing() throws Exception {
        List<Socket> accepted = new CopyOnWriteArrayList<>();
        try (ServerSocket owner = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread t = new Thread(() -> {
                try {
                    while (true) {
                        accepted.add(owner.accept()); // and never answers, like a paused owner
                    }
                } catch (java.io.IOException closed) {
                    // done
                }
            });
            t.setDaemon(true);
            t.start();
            String url = "http://127.0.0.1:" + owner.getLocalPort();
            HttpServletResponse rsp = mock(HttpServletResponse.class);
            assertFalse(
                    Forwarding.forward(request("GET"), rsp, "cnj-1", url, Duration.ofMillis(500)), "served locally");
            verifyNoInteractions(rsp);
            rsp = mock(HttpServletResponse.class);
            assertTrue(
                    Forwarding.forward(request("POST"), rsp, "cnj-1", url, Duration.ofMillis(500)),
                    "not run here as well");
            verify(rsp).sendError(eq(HttpServletResponse.SC_GATEWAY_TIMEOUT), anyString());
        } finally {
            for (Socket s : accepted) {
                s.close();
            }
        }
    }

    @Test
    void anOwnerThatCannotBeConnectedToIsServedLocally() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            port = s.getLocalPort(); // closed again: connections are refused
        }
        HttpServletResponse rsp = mock(HttpServletResponse.class);
        assertFalse(
                Forwarding.forward(request("POST"), rsp, "cnj-1", "http://127.0.0.1:" + port, Duration.ofSeconds(5)));
        verify(rsp, never()).sendError(eq(HttpServletResponse.SC_GATEWAY_TIMEOUT), anyString());
    }

    private static HttpServletRequest withForwarded(String... values) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeaders(Forwarding.FORWARDED)).thenReturn(Collections.enumeration(List.of(values)));
        when(req.getHeader(Forwarding.FORWARDED)).thenReturn(values.length == 0 ? null : values[0]);
        return req;
    }

    @Test
    void onlyAControllerCanSayARequestWasForwarded() {
        assertTrue(Forwarding.wasForwarded(withForwarded(Forwarding.forwardedHeader())));
        assertFalse(Forwarding.wasForwarded(withForwarded()), "not forwarded");
        assertFalse(
                Forwarding.wasForwarded(withForwarded("cnj-1")),
                "a client's, to stop its request from reaching the owner");
        assertTrue(
                Forwarding.wasForwarded(withForwarded("cnj-1", Forwarding.forwardedHeader())),
                "whatever else the client sent");
        String real = Forwarding.forwardedHeader();
        assertFalse(
                Forwarding.wasForwarded(withForwarded(real.substring(0, real.length() - 2) + "00")), "a forged MAC");
        assertFalse(
                Forwarding.wasForwarded(withForwarded(real.replaceFirst("^[^;]*", "other"))),
                "another name with the MAC of this one");
        java.util.function.UnaryOperator<String> mac = m -> Integer.toHexString(m.hashCode());
        String old = Forwarding.forwardedHeader("cnj-2", 1_000, mac);
        assertEquals("cnj-2;1000;" + Integer.toHexString("cnj-2;1000".hashCode()), old);
        assertTrue(Forwarding.isAuthentic(old, 1_000 + Forwarding.MAX_AGE_MILLIS, (m, x) -> x.equals(mac.apply(m))));
        assertFalse(
                Forwarding.isAuthentic(old, 1_001 + Forwarding.MAX_AGE_MILLIS, (m, x) -> x.equals(mac.apply(m))),
                "too old: a replay");
        assertFalse(Forwarding.isAuthentic("a;b;c", 0, (m, x) -> true));
        assertFalse(Forwarding.isAuthentic("abc", 0, (m, x) -> true));
    }

    @Test
    void aClientsForwardedHeaderIsNotPassedOn() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        try (ServerSocket owner = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread t = new Thread(() -> {
                try (Socket s = owner.accept()) {
                    java.io.BufferedReader in = new java.io.BufferedReader(
                            new java.io.InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
                    String line;
                    while ((line = in.readLine()) != null && !line.isEmpty()) {
                        received.add(line);
                    }
                    s.getOutputStream()
                            .write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                    .getBytes(StandardCharsets.US_ASCII));
                } catch (java.io.IOException e) {
                    // done
                }
            });
            t.setDaemon(true);
            t.start();
            HttpServletRequest req = request("GET");
            when(req.getHeaderNames()).thenReturn(Collections.enumeration(List.of("Accept", Forwarding.FORWARDED)));
            when(req.getHeaders("Accept")).thenReturn(Collections.enumeration(List.of("text/html")));
            when(req.getHeaders(Forwarding.FORWARDED)).thenReturn(Collections.enumeration(List.of("forged")));
            HttpServletResponse rsp = mock(HttpServletResponse.class);
            when(rsp.getOutputStream()).thenReturn(mock(jakarta.servlet.ServletOutputStream.class));
            assertTrue(Forwarding.forward(
                    req, rsp, "cnj-1", "http://127.0.0.1:" + owner.getLocalPort(), Duration.ofSeconds(10)));
            t.join(10_000);
        }
        List<String> forwarded = received.stream()
                .filter(h -> h.toLowerCase(java.util.Locale.ROOT)
                        .startsWith(Forwarding.FORWARDED.toLowerCase(java.util.Locale.ROOT) + ":"))
                .map(h -> h.substring(h.indexOf(':') + 1).trim())
                .toList();
        assertEquals(1, forwarded.size(), received::toString);
        assertTrue(Forwarding.wasForwarded(withForwarded(forwarded.get(0))), forwarded::toString);
        assertTrue(
                received.contains("Accept: text/html") || received.contains("accept: text/html"), received::toString);
    }
}
