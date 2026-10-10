package io.jenkins.plugins.cnj.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
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
}
