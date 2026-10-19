package ai.victorious.victorfiles.net

import ai.victorious.victorfiles.BuildConfig
import ai.victorious.victorfiles.core.cloud.CloudBackendFactory
import ai.victorious.victorfiles.core.cloud.CloudProvider
import ai.victorious.victorfiles.core.cloud.CloudTokenStore
import ai.victorious.victorfiles.core.cloud.CombinedBackendFactory
import ai.victorious.victorfiles.core.cloud.OAuthTokens
import ai.victorious.victorfiles.core.net.AuthKind
import ai.victorious.victorfiles.core.net.ConnectionDraft
import ai.victorious.victorfiles.core.net.DefaultRemoteBackendFactory
import ai.victorious.victorfiles.core.net.RemoteBackend
import ai.victorious.victorfiles.core.net.RemoteBackendFactory
import ai.victorious.victorfiles.core.net.RemoteProtocol
import ai.victorious.victorfiles.core.net.SavedConnection
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ConnectionRepository(
    context: Context,
    factory: RemoteBackendFactory? = null,
) {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val metaPrefs: SharedPreferences =
        appContext.getSharedPreferences("fm_network_connections", Context.MODE_PRIVATE)
    private val secretPrefs: SharedPreferences by lazy { encryptedPrefs() }
    private val openBackends = ConcurrentHashMap<String, RemoteBackend>()

    /** Public OAuth client IDs from build config (blank = not configured). */
    val clientIds: Map<CloudProvider, String> = mapOf(
        CloudProvider.GOOGLE_DRIVE to BuildConfig.GDRIVE_CLIENT_ID,
        CloudProvider.DROPBOX to BuildConfig.DROPBOX_APP_KEY,
        CloudProvider.ONEDRIVE to BuildConfig.ONEDRIVE_CLIENT_ID,
    )

    fun isConfigured(provider: CloudProvider): Boolean = clientIds[provider].orEmpty().isNotBlank()
    fun clientId(provider: CloudProvider): String = clientIds[provider].orEmpty()

    /** Encrypted token storage for OAuth connections. Tokens are never logged. */
    val tokenStore: CloudTokenStore = object : CloudTokenStore {
        override fun loadTokens(connectionId: String): OAuthTokens? =
            secretPrefs.getString(tokenKey(connectionId), null)?.let {
                runCatching { json.decodeFromString(OAuthTokens.serializer(), it) }.getOrNull()
            }
        override fun saveTokens(connectionId: String, tokens: OAuthTokens) {
            secretPrefs.edit().putString(tokenKey(connectionId), json.encodeToString(OAuthTokens.serializer(), tokens)).apply()
        }
        override fun clear(connectionId: String) {
            secretPrefs.edit().remove(tokenKey(connectionId)).apply()
        }
    }

    private val effectiveFactory: RemoteBackendFactory = factory ?: CombinedBackendFactory(
        base = DefaultRemoteBackendFactory(),
        cloud = CloudBackendFactory(tokenStore, clientIds),
    )

    private fun encryptedPrefs(): SharedPreferences {
        val master = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            appContext,
            "fm_network_secrets",
            master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun list(): List<SavedConnection> {
        val raw = metaPrefs.getString(KEY_LIST, "[]") ?: "[]"
        return runCatching {
            json.decodeFromString(ListSerializer(SavedConnection.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    fun save(draft: ConnectionDraft, existingId: String? = null): SavedConnection {
        val id = existingId ?: UUID.randomUUID().toString().replace("-", "").take(12)
        val saved = SavedConnection(
            id = id,
            name = draft.name.ifBlank { draft.host.ifBlank { draft.protocol.name } },
            protocol = draft.protocol,
            host = draft.host.trim(),
            port = draft.port ?: draft.protocol.defaultPort,
            username = draft.username,
            initialPath = draft.initialPath.ifBlank { "/" },
            authKind = draft.authKind,
            keyLabel = draft.keyLabel,
            share = draft.share,
            webDavHttps = draft.webDavHttps,
            region = draft.region,
            bucket = draft.bucket,
            endpoint = draft.endpoint,
            s3PathStyle = draft.s3PathStyle,
        )
        val secret = when {
            draft.protocol == RemoteProtocol.S3 -> draft.s3SecretKey
            draft.authKind == AuthKind.PASSWORD -> draft.password
            draft.authKind == AuthKind.PRIVATE_KEY -> draft.privateKeyPem
            else -> null
        }
        if (!secret.isNullOrEmpty()) {
            secretPrefs.edit().putString(secretKey(id), secret).apply()
        }
        val next = list().filterNot { it.id == id } + saved
        persist(next)
        closeBackend(id)
        return saved
    }

    fun remove(id: String) {
        persist(list().filterNot { it.id == id })
        secretPrefs.edit().remove(secretKey(id)).remove(tokenKey(id)).apply()
        closeBackend(id)
    }

    fun secretFor(id: String): String? = secretPrefs.getString(secretKey(id), null)

    fun backend(id: String): RemoteBackend {
        openBackends[id]?.let { return it }
        val conn = list().firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Unknown connection $id")
        val backend = effectiveFactory.open(conn, secretFor(id))
        openBackends[id] = backend
        return backend
    }

    fun backendOrNull(id: String): RemoteBackend? = runCatching { backend(id) }.getOrNull()

    fun closeBackend(id: String) {
        openBackends.remove(id)?.close()
    }

    fun closeAll() {
        openBackends.keys.toList().forEach { closeBackend(it) }
    }

    private fun persist(items: List<SavedConnection>) {
        metaPrefs.edit()
            .putString(KEY_LIST, json.encodeToString(ListSerializer(SavedConnection.serializer()), items))
            .apply()
    }

    private fun secretKey(id: String) = "cred.$id"
    private fun tokenKey(id: String) = "tokens.$id"

    companion object {
        private const val KEY_LIST = "connections_json"
    }
}
