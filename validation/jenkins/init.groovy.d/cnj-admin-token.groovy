// Gives the harness admin a known API token, so scripts authenticate with `-u admin:<token>` and never need crumbs or
// session cookies. With users shared through the store (Target 6), controllers booting at once all rewrite the admin
// user (JCasC), and one rewrite can land after another controller added the token; so the stored record is checked for
// a while after boot, and the token added again if it went missing.
import hudson.model.User
import jenkins.security.ApiTokenProperty

def token = System.getenv('CNJ_ADMIN_TOKEN') ?: '1100000000000000000000000000c0ffee'
def ensure = {
    def user = User.getById('admin', true)
    def property = user.getProperty(ApiTokenProperty)
    if (property == null) {
        property = new ApiTokenProperty()
        user.addProperty(property)
    }
    boolean stored = user.getConfigFile().exists() && user.getConfigFile().asString().contains('<name>harness</name>')
    if (!stored || !property.tokenStore.tokenListSortedByName.any { it.name == 'harness' }) {
        if (!property.tokenStore.tokenListSortedByName.any { it.name == 'harness' }) {
            property.tokenStore.addFixedNewToken('harness', token, null)
        }
        user.save()
    }
}
ensure()
Thread.start('cnj-admin-token') {
    for (int i = 0; i < 15; i++) {
        sleep(2000)
        try {
            ensure()
        } catch (Exception e) {
            println "cnj-admin-token: ${e}"
        }
    }
}
