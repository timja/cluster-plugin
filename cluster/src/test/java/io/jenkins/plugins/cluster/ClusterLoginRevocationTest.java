package io.jenkins.plugins.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.User;
import hudson.security.HudsonPrivateSecurityRealm;
import hudson.security.TokenBasedRememberMeServices2;
import io.jenkins.plugins.cluster.spi.InMemoryClusterStore;
import io.jenkins.plugins.cluster.spi.Keys;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import jenkins.persistence.PersistenceStore;
import org.htmlunit.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.springframework.security.web.authentication.rememberme.AbstractRememberMeServices;

/**
 * Signing out on one controller ends that login on the others. A browser holding only the login's cookie, with a
 * session of its own, stands for the same login on another controller; {@code other} is another controller's store.
 */
@WithJenkins
class ClusterLoginRevocationTest {

    @BeforeAll
    static void cluster() {
        ClusterFixture.activate();
    }

    @AfterAll
    static void noCluster() {
        ClusterFixture.deactivate();
    }

    private JenkinsRule r;
    private InMemoryClusterStore store;
    private InMemoryClusterStore other;
    private ClusterLoginRevocation revocation;
    private boolean alwaysRemember;

    @BeforeEach
    void setUp(JenkinsRule r) throws Exception {
        this.r = r;
        store = ClusterFixture.store();
        other = store.as("other");
        revocation = ClusterLoginRevocation.get();
        alwaysRemember = TokenBasedRememberMeServices2.ALWAYS_REMEMBER;
        HudsonPrivateSecurityRealm realm = new HudsonPrivateSecurityRealm(false, false, null);
        r.jenkins.setSecurityRealm(realm);
        TokenBasedRememberMeServices2.ALWAYS_REMEMBER = true; // as a cluster runs
        realm.createAccount("alice", "alice-password-1");
        realm.createAccount("admin", "admin-password-1");
    }

    @AfterEach
    void tearDown() {
        TokenBasedRememberMeServices2.ALWAYS_REMEMBER = alwaysRemember;
    }

    private static Cookie rememberMe(JenkinsRule.WebClient wc) {
        Cookie c = wc.getCookieManager().getCookie(AbstractRememberMeServices.SPRING_SECURITY_REMEMBER_ME_COOKIE_KEY);
        assertNotNull(c, "signed in with the remember-me cookie");
        return c;
    }

    /** A browser with only this cookie: the same login, in a session of its own (as on another controller). */
    private JenkinsRule.WebClient withCookie(Cookie cookie) {
        JenkinsRule.WebClient wc = r.createWebClient();
        wc.getCookieManager().addCookie(cookie);
        return wc;
    }

    private static String whoAmI(JenkinsRule.WebClient wc) throws Exception {
        String json =
                wc.goTo("whoAmI/api/json", "application/json").getWebResponse().getContentAsString();
        return json.contains("\"name\":\"alice\"")
                ? "alice"
                : json.contains("\"name\":\"anonymous\"") ? "anonymous" : json;
    }

    /** The login id core gives a cookie. */
    private static String loginId(Cookie cookie) throws Exception {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256")
                        .digest(cookie.getValue().getBytes(StandardCharsets.US_ASCII)));
    }

    private void applied() throws Exception {
        ClusterFixture.awaitApplied();
        revocation.flush();
    }

    @Test
    void signingOutEndsThatLoginOnEveryControllerButNotTheUsersOtherLogins() throws Exception {
        JenkinsRule.WebClient browser = r.createWebClient().login("alice", "alice-password-1");
        Cookie cookie = rememberMe(browser);
        JenkinsRule.WebClient elsewhere = withCookie(cookie);
        assertEquals("alice", whoAmI(elsewhere));
        JenkinsRule.WebClient otherBrowser = r.createWebClient().login("alice", "alice-password-1");

        browser.goTo("logout");

        PersistenceStore.Entry entry = store.read(Keys.revokedLogin(loginId(cookie)));
        assertNotNull(entry, "recorded for the other controllers");
        long until = ClusterLoginRevocation.until(entry, System.currentTimeMillis());
        assertTrue(
                until
                        >= Long.parseLong(new String(
                                                Base64.getDecoder().decode(cookie.getValue()), StandardCharsets.UTF_8)
                                        .split(":")[1])
                                + TimeUnit.DAYS.toMillis(1)
                                - TimeUnit.MINUTES.toMillis(1),
                "kept until the cookie expires, and a day longer");
        assertEquals("anonymous", whoAmI(elsewhere), "its session elsewhere is signed out");
        assertEquals("anonymous", whoAmI(withCookie(cookie)), "its cookie no longer signs in");
        assertEquals("alice", whoAmI(otherBrowser), "the user's other browser stays signed in");
        assertEquals(1, store.children(ClusterLoginRevocation.REVOKED).size());
    }

    @Test
    void aLoginSignedOutOfOnAnotherControllerEndsHere() throws Exception {
        JenkinsRule.WebClient browser = r.createWebClient().login("alice", "alice-password-1");
        Cookie cookie = rememberMe(browser);
        assertEquals("alice", whoAmI(browser));
        String key = Keys.revokedLogin(loginId(cookie));

        other.write(key, record(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(2)), PersistenceStore.ANY);
        applied();

        assertTrue(revocation.isRevoked(loginId(cookie)));
        assertEquals("anonymous", whoAmI(browser), "its session here is signed out");
        assertEquals("anonymous", whoAmI(withCookie(cookie)), "its cookie no longer signs in");

        // its record deleted elsewhere (early, by a controller whose clock runs fast): still signed out here
        other.delete(key, PersistenceStore.ANY);
        applied();
        assertTrue(revocation.isRevoked(loginId(cookie)));
        assertEquals("anonymous", whoAmI(withCookie(cookie)));
    }

    private static byte[] record(long until) {
        return ("user=alice\nuntil=" + until + "\n").getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void recordsAreReadAtStartAndForgottenAndDeletedOnceExpired() throws Exception {
        String live = "A".repeat(43);
        String expired = "B".repeat(43);
        String forever = "C".repeat(43);
        long now = System.currentTimeMillis();
        // written before this controller started: no event for them
        store.write(Keys.revokedLogin(live), record(now + TimeUnit.HOURS.toMillis(1)), PersistenceStore.ANY);
        store.write(Keys.revokedLogin(expired), record(now - 1), PersistenceStore.ANY);
        store.write(Keys.revokedLogin(forever), record(Long.MAX_VALUE), PersistenceStore.ANY);
        store.write(ClusterLoginRevocation.REVOKED + "/not-a-login-id", record(now + 60_000), PersistenceStore.ANY);
        revocation.refresh();
        assertTrue(revocation.isRevoked(live));
        assertFalse(revocation.isRevoked(expired));
        assertFalse(revocation.isRevoked("not-a-login-id"));
        assertTrue(
                ClusterLoginRevocation.until(store.read(Keys.revokedLogin(forever)), now)
                        <= now + ClusterLoginRevocation.MAX_LOGIN.toMillis() + ClusterLoginRevocation.grace(),
                "a record kept for longer than any login lasts is bounded");

        assertEquals(1, ClusterLoginRevocation.deleteExpired(store));
        assertNull(store.read(Keys.revokedLogin(expired)));
        assertNotNull(store.read(Keys.revokedLogin(live)));
        assertNotNull(store.read(Keys.revokedLogin(forever)));
    }

    @Test
    void deletingAUserSignsOutTheirSessionsEverywhere() throws Exception {
        JenkinsRule.WebClient browser = r.createWebClient().login("alice", "alice-password-1");
        Cookie cookie = rememberMe(browser);
        JenkinsRule.WebClient elsewhere = withCookie(cookie);
        assertEquals("alice", whoAmI(elsewhere));

        User.getById("alice", false).delete();

        assertNull(User.getById("alice", false), "deleted");
        assertNotNull(
                store.read(Keys.deletedUser(ClusterLoginRevocation.userSegment("alice"))),
                "recorded for the other controllers");
        assertEquals("anonymous", whoAmI(browser));
        assertEquals("anonymous", whoAmI(elsewhere));
        assertEquals("anonymous", whoAmI(withCookie(cookie)));
        assertTrue(revocation.isRevoked(loginId(cookie)));

        // a new user of that name signs in as usual
        ((HudsonPrivateSecurityRealm) r.jenkins.getSecurityRealm()).createAccount("alice", "alice-password-2");
        JenkinsRule.WebClient again = r.createWebClient().login("alice", "alice-password-2");
        assertEquals("alice", whoAmI(again));
        assertEquals("anonymous", whoAmI(withCookie(cookie)), "not the old login");
    }

    @Test
    void aUserDeletedOnAnotherControllerIsSignedOutHere() throws Exception {
        JenkinsRule.WebClient browser = r.createWebClient().login("alice", "alice-password-1");
        assertEquals("alice", whoAmI(browser));
        other.write(
                Keys.deletedUser(ClusterLoginRevocation.userSegment("alice")),
                record(System.currentTimeMillis() + 60_000),
                PersistenceStore.ANY);
        applied();
        assertEquals("alice", whoAmI(browser), "the user still exists here");
        // deleted there: core applies it here, and no listener here is told
        String key = "users/" + User.getById("alice", false).getUserFolder().getName() + "/config.xml";
        assertNotNull(store.read(key));
        other.delete(key, PersistenceStore.ANY);
        ClusterFixture.await("the deletion to be applied", () -> User.getById("alice", false) == null);
        assertEquals("anonymous", whoAmI(browser));
        assertEquals(1, store.children(ClusterLoginRevocation.REVOKED).size(), "its login is signed out of too");
    }
}
