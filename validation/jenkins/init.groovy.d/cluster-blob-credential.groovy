// Target 15: the storage credential the artifact manager (JCasC: storageCredentialId cluster-blob) uses, made once if absent,
// from CNJ_BLOB_ACCOUNT_NAME, CNJ_BLOB_ACCOUNT_KEY and CNJ_BLOB_ENDPOINT. Not declared in JCasC: JCasC replaces the system
// credentials with what it declares, and in a cluster every starting controller applies it, through the shared store,
// which would delete credentials added at runtime.
import com.cloudbees.plugins.credentials.CredentialsScope
import com.cloudbees.plugins.credentials.SystemCredentialsProvider
import jenkins.model.Jenkins

def env = System.getenv()
def account = env['CNJ_BLOB_ACCOUNT_NAME']
if (!account) {
    return
}
def provider = SystemCredentialsProvider.getInstance()
def existing = provider.getCredentials().findAll { it.hasProperty('id') && it.id == 'cluster-blob' }
if (existing.size() > 1) {
    // left by controllers which started together before only one made it: keep one
    existing.drop(1).each { provider.getCredentials().remove(it) }
    provider.save()
    println "cluster: removed ${existing.size() - 1} duplicate(s) of the blob storage credential cluster-blob"
}
if (!existing.isEmpty()) {
    return
}
// controllers starting together would each find it absent and each add it (the credentials file is merged, so all
// additions are kept): only the one that claims making it does; the others get it when they apply that change
def cluster = null
try {
    cluster = Jenkins.get().pluginManager.uberClassLoader.loadClass('io.jenkins.plugins.cluster.spi.ClusterStore').get()
} catch (ClassNotFoundException ignored) {
    // not a cluster: make it here
}
// claimed per 10 minutes: should the controller making it die first, a later start makes it
if (cluster != null && !cluster.claim('_init', 'cluster-blob-credential', (long) (System.currentTimeMillis() / 600_000))) {
    println "cluster: another controller makes the blob storage credential cluster-blob"
    return
}
def cls = Jenkins.get().pluginManager.uberClassLoader.loadClass('com.microsoftopentechnologies.windowsazurestorage.helper.AzureStorageAccount')
def credential = cls.getConstructor(CredentialsScope, String, String, String, String, String, String)
        .newInstance(CredentialsScope.GLOBAL, 'cluster-blob', 'Blob storage for artifacts and stashes', account,
                env['CNJ_BLOB_ACCOUNT_KEY'], env['CNJ_BLOB_ENDPOINT'], '')
provider.getCredentials().add(credential)
provider.save()
println "cluster: added the blob storage credential cluster-blob for account ${account}"
