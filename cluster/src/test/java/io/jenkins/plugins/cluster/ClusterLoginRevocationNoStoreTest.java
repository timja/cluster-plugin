package io.jenkins.plugins.cluster;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.User;
import hudson.security.HudsonPrivateSecurityRealm;
import hudson.security.TokenBasedRememberMeServices2;
import org.htmlunit.http.Cookie;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.springframework.security.web.authentication.rememberme.AbstractRememberMeServices;

/** Without a cluster store, signing out and deleting users behave as without the plugin. */
@WithJenkins
class ClusterLoginRevocationNoStoreTest {

    private static String whoAmI(JenkinsRule.WebClient wc) throws Exception {
        return wc.goTo("whoAmI/api/json", "application/json").getWebResponse().getContentAsString();
    }

    @Test
    void signingOutIsLocal(JenkinsRule r) throws Exception {
        boolean old = TokenBasedRememberMeServices2.ALWAYS_REMEMBER;
        try {
            HudsonPrivateSecurityRealm realm = new HudsonPrivateSecurityRealm(false, false, null);
            r.jenkins.setSecurityRealm(realm);
            TokenBasedRememberMeServices2.ALWAYS_REMEMBER = true;
            realm.createAccount("alice", "alice-password-1");
            JenkinsRule.WebClient browser = r.createWebClient().login("alice", "alice-password-1");
            Cookie cookie = browser.getCookieManager()
                    .getCookie(AbstractRememberMeServices.SPRING_SECURITY_REMEMBER_ME_COOKIE_KEY);
            assertNotNull(cookie);
            browser.goTo("logout");
            JenkinsRule.WebClient replayed = r.createWebClient();
            replayed.getCookieManager().addCookie(cookie);
            assertTrue(whoAmI(replayed).contains("\"name\":\"alice\""), "as upstream, the cookie still signs in");

            User.getById("alice", false).delete();
            assertFalse(ClusterLoginRevocation.get().isRevoked("A".repeat(43)));
        } finally {
            TokenBasedRememberMeServices2.ALWAYS_REMEMBER = old;
        }
    }
}
