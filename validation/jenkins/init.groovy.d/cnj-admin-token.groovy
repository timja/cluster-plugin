// Gives the harness admin a known API token on every controller, so scripts authenticate with
// `-u admin:<token>` and never need crumbs or session cookies. Tokens are per-controller user
// state (findings/E5, E8), hence provisioned on each boot rather than shared.
import hudson.model.User
import jenkins.security.ApiTokenProperty

def token = System.getenv('CNJ_ADMIN_TOKEN') ?: '1100000000000000000000000000c0ffee'
def user = User.getById('admin', true)
def property = user.getProperty(ApiTokenProperty)
if (property == null) {
    property = new ApiTokenProperty()
    user.addProperty(property)
}
if (!property.tokenStore.tokenListSortedByName.any { it.name == 'harness' }) {
    property.tokenStore.addFixedNewToken('harness', token, null)
    user.save()
}
