// Target 15: the storage credential the artifact manager (JCasC: storageCredentialId cnj-blob) uses, made once if absent,
// from CNJ_BLOB_ACCOUNT_NAME, CNJ_BLOB_ACCOUNT_KEY and CNJ_BLOB_ENDPOINT. Not declared in JCasC: JCasC replaces the system
// credentials with what it declares, and in a cluster every starting controller applies it, through the shared store,
// which would delete credentials added at runtime.
import com.cloudbees.plugins.credentials.CredentialsScope
import com.cloudbees.plugins.credentials.SystemCredentialsProvider

def env = System.getenv()
def account = env['CNJ_BLOB_ACCOUNT_NAME']
if (!account) {
    return
}
def provider = SystemCredentialsProvider.getInstance()
if (provider.getCredentials().any { it.hasProperty('id') && it.id == 'cnj-blob' }) {
    return
}
def cls = Jenkins.get().pluginManager.uberClassLoader.loadClass('com.microsoftopentechnologies.windowsazurestorage.helper.AzureStorageAccount')
def credential = cls.getConstructor(CredentialsScope, String, String, String, String, String, String)
        .newInstance(CredentialsScope.GLOBAL, 'cnj-blob', 'Blob storage for artifacts and stashes', account,
                env['CNJ_BLOB_ACCOUNT_KEY'], env['CNJ_BLOB_ENDPOINT'], '')
provider.getCredentials().add(credential)
provider.save()
println "cnj: added the blob storage credential cnj-blob for account ${account}"
