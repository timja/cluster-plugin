package io.jenkins.plugins.cluster;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.security.csrf.CrumbIssuer;
import hudson.security.csrf.CrumbIssuerDescriptor;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;
import jenkins.model.Jenkins;
import jenkins.model.JenkinsLocationConfiguration;
import jenkins.security.HexStringConfidentialKey;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.Stapler;

/**
 * CSRF crumbs which every controller sharing the same users and keys accepts, so that a form rendered by one
 * controller can be submitted to another (browsers are not pinned to a controller).
 *
 * <p>Like the default issuer, a crumb is a hash of the user's name, a value bound to the browser and the shared salt.
 * The value is the signed login cookie (remember-me, see {@code TokenBasedRememberMeServices2.alwaysRemember}) when there is one,
 * otherwise a random cookie this issuer sets (e.g. for the login form), rather than the controller's own session id.
 * The UTC day is part of the hash, and a crumb is accepted on the day it was issued and the next, so that one which
 * leaked does not stay valid for as long as the login cookie.
 */
public class ClusterCrumbIssuer extends CrumbIssuer {

    static final String REMEMBER_ME_COOKIE = "remember-me";
    static final String NONCE_COOKIE = "jenkins-cluster-crumb-nonce";

    private static final SecureRandom RANDOM = new SecureRandom();

    /** How long the part of a crumb that changes is the same: a crumb is valid for one to two of these. */
    static final long WINDOW_MILLIS = TimeUnit.DAYS.toMillis(1);

    @DataBoundConstructor
    public ClusterCrumbIssuer() {}

    @CheckForNull
    private static String cookie(HttpServletRequest req, String name) {
        Cookie[] cookies = req.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (name.equals(c.getName())) {
                    return c.getValue();
                }
            }
        }
        return null;
    }

    /** The value a crumb is bound to, setting the nonce cookie if needed and possible. */
    @CheckForNull
    private static String binding(HttpServletRequest req, boolean issuing) {
        String signedIn = cookie(req, REMEMBER_ME_COOKIE);
        if (signedIn != null && !Jenkins.getAuthentication2().getName().equals("anonymous")) {
            return "login:" + signedIn;
        }
        String nonce = cookie(req, NONCE_COOKIE);
        if (nonce == null && issuing) {
            HttpServletResponse rsp = Stapler.getCurrentResponse2();
            if (rsp != null && !rsp.isCommitted()) {
                byte[] bytes = new byte[16];
                RANDOM.nextBytes(bytes);
                nonce = Util.toHexString(bytes);
                Cookie c = new Cookie(NONCE_COOKIE, nonce);
                c.setPath(req.getContextPath().isEmpty() ? "/" : req.getContextPath());
                c.setHttpOnly(true);
                c.setSecure(secure(req, JenkinsLocationConfiguration.get().getUrl()));
                rsp.addCookie(c);
            }
        }
        if (nonce != null) {
            return "nonce:" + nonce;
        }
        // no cookies (e.g. a client which does not keep them): only valid on this controller
        return req.getSession(true) == null
                ? null
                : "session:" + req.getSession().getId();
    }

    /**
     * Whether the nonce cookie is only sent over HTTPS: when the request came that way, or Jenkins is reached over HTTPS
     * (behind a load balancer ending TLS, the request itself may not say so).
     */
    static boolean secure(HttpServletRequest req, @CheckForNull String rootUrl) {
        return req.isSecure() || rootUrl != null && rootUrl.regionMatches(true, 0, "https:", 0, 6);
    }

    static long window(long now) {
        return Math.floorDiv(now, WINDOW_MILLIS);
    }

    @Override
    protected String issueCrumb(ServletRequest request, String salt) {
        return request instanceof HttpServletRequest req
                ? crumb(req, salt, true, window(System.currentTimeMillis()))
                : null;
    }

    @Override
    public boolean validateCrumb(ServletRequest request, String salt, String crumb) {
        return request instanceof HttpServletRequest req && validate(req, salt, crumb, System.currentTimeMillis());
    }

    /** Whether a crumb was issued for this request's user and browser in the current window or the one before. */
    static boolean validate(HttpServletRequest req, String salt, @CheckForNull String crumb, long now) {
        if (crumb == null) {
            return false;
        }
        for (long w = window(now); w >= window(now) - 1; w--) {
            String expected = crumb(req, salt, false, w);
            if (expected != null
                    && MessageDigest.isEqual(
                            expected.getBytes(StandardCharsets.US_ASCII), crumb.getBytes(StandardCharsets.US_ASCII))) {
                return true;
            }
        }
        return false;
    }

    @CheckForNull
    static String crumb(HttpServletRequest req, String salt, boolean issuing, long window) {
        String binding = binding(req, issuing);
        if (binding == null) {
            return null;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update((Jenkins.getAuthentication2().getName() + ";" + binding + ";" + window)
                    .getBytes(StandardCharsets.UTF_8));
            return Util.toHexString(md.digest(salt.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    @Extension
    @Symbol("cluster")
    public static final class DescriptorImpl extends CrumbIssuerDescriptor<ClusterCrumbIssuer> {
        public DescriptorImpl() {
            // the same salt as the default issuer: a confidential key, identical on every controller
            super(
                    new HexStringConfidentialKey(Jenkins.class, "crumbSalt", 16).get(),
                    System.getProperty("hudson.security.csrf.requestfield", CrumbIssuer.DEFAULT_CRUMB_NAME));
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "Crumbs valid on every controller (Jenkins cluster)";
        }
    }
}
