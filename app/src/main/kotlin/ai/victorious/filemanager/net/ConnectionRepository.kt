package ai.victorious.filemanager.net

import ai.victorious.filemanager.core.net.AuthKind
import ai.victorious.filemanager.core.net.ConnectionDraft
import ai.victorious.filemanager.core.net.DefaultRemoteBackendFactory
import ai.victorious.filemanager.core.net.RemoteBackend
import ai.victorious.filemanager.core.net.RemoteBackendFactory
import ai.victorious.filemanager.core.net.SavedConnection
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
    private val factory: RemoteBackendFactory = DefaultRemoteBackendFactory(),
) {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val metaPrefs: SharedPreferences =
        appContext.getSharedPreferences("fm_network_connections", Context.MODE_PRIVATE)
    private val secretPrefs: SharedPreferences by lazy { encryptedPrefs() }
    private val openBackends = ConcurrentHashMap<String, RemoteBackend>()

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
            name = draft.name.ifBlank { draft.host },
            protocol = draft.protocol,
            host = draft.host.trim(),
            port = draft.port ?: draft.protocol.defaultPort,
            username = draft.username,
            initialPath = draft.initialPath.ifBlank { "/" },
            authKind = draft.authKind,
            keyLabel = draft.keyLabel,
            share = draft.share,
            webDavHttps = draft.webDavHttps,
        )
        val secret = when (draft.authKind) {
            AuthKind.PASSWORD -> draft.password
            AuthKind.PRIVATE_KEY -> draft.privateKeyPem
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
        secretPrefs.edit().remove(secretKey(id)).apply()
        closeBackend(id)
    }

    fun secretFor(id: String): String? = secretPrefs.getString(secretKey(id), null)

    fun backend(id: String): RemoteBackend {
        openBackends[id]?.let { return it }
        val conn = list().firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Unknown connection $id")
        val backend = factory.open(conn, secretFor(id))
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

    companion object {
        private const val KEY_LIST = "connections_json"
    }
}
