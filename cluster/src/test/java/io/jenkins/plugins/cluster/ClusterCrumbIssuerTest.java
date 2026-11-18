package io.jenkins.plugins.cluster;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

class ClusterCrumbIssuerTest {

    private static HttpServletRequest browser(String nonce) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getCookies()).thenReturn(new Cookie[] {new Cookie(ClusterCrumbIssuer.NONCE_COOKIE, nonce)});
        when(req.getContextPath()).thenReturn("");
        return req;
    }

    @Test
    void aCrumbIsValidOnTheDayItWasIssuedAndTheNextOnly() {
        long day = ClusterCrumbIssuer.WINDOW_MILLIS;
        long now = 100 * day + 5;
        HttpServletRequest req = browser("abc");
        String crumb = ClusterCrumbIssuer.crumb(req, "salt", false, ClusterCrumbIssuer.window(now));
        assertNotNull(crumb);
        assertTrue(ClusterCrumbIssuer.validate(req, "salt", crumb, now), "the day it was issued");
        assertTrue(ClusterCrumbIssuer.validate(req, "salt", crumb, now + day), "the day after");
        assertFalse(ClusterCrumbIssuer.validate(req, "salt", crumb, now + 2 * day), "not later");
        assertFalse(ClusterCrumbIssuer.validate(req, "salt", crumb, now - day), "nor before");
        assertFalse(ClusterCrumbIssuer.validate(browser("other"), "salt", crumb, now), "another browser's");
        assertNotEquals(crumb, ClusterCrumbIssuer.crumb(req, "salt", false, ClusterCrumbIssuer.window(now + day)));
    }

    @Test
    void theNonceCookieIsSecureWhenJenkinsIsReachedOverHttps() {
        HttpServletRequest behindTls = mock(HttpServletRequest.class);
        when(behindTls.isSecure()).thenReturn(false);
        assertTrue(ClusterCrumbIssuer.secure(behindTls, "https://jenkins.example.com/"));
        assertTrue(ClusterCrumbIssuer.secure(behindTls, "HTTPS://jenkins.example.com/"));
        assertFalse(ClusterCrumbIssuer.secure(behindTls, "http://jenkins.example.com/"));
        assertFalse(ClusterCrumbIssuer.secure(behindTls, null));
        HttpServletRequest tls = mock(HttpServletRequest.class);
        when(tls.isSecure()).thenReturn(true);
        assertTrue(ClusterCrumbIssuer.secure(tls, null));
    }
}
