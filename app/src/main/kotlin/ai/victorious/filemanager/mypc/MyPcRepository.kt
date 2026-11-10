package ai.victorious.filemanager.mypc

import ai.victorious.filemanager.core.mypc.HostKeys
import ai.victorious.filemanager.core.mypc.MyPcConnector
import ai.victorious.filemanager.core.mypc.MyPcIds
import ai.victorious.filemanager.core.mypc.MyPcSftpBackend
import ai.victorious.filemanager.core.mypc.PcProfile
import ai.victorious.filemanager.core.mypc.SshKeyFormat
import ai.victorious.filemanager.core.net.AuthKind
import ai.victorious.filemanager.core.net.RemoteBackend
import ai.victorious.filemanager.core.net.RemoteProtocol
import ai.victorious.filemanager.core.net.SavedConnection
import android.content.Context
import androidx.core.content.edit
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * My PC profiles and sessions. Profiles hold public data only (host, port, user, pinned host
 * keys, display name) in plain app-private prefs; the login key stays in Android Keystore.
 *
 * TODO(P3, blocked on VictorSuite PR 8): read paired PCs from VictorSuite's read-only
 * `content://com.termux.victor.pc/profiles`, enroll this app's key through `PcEnrollService`
 * and handle `OPEN_PC` (mode=sftp). Until then profiles are added by hand.
 */
class MyPcRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("fm_mypc", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(PcProfile.serializer())
    private val open = ConcurrentHashMap<String, MyPcSftpBackend>()
    private val _profiles = MutableStateFlow(load())
    val profiles: StateFlow<List<PcProfile>> = _profiles

    /** Why the last automatic (pane) connect failed, by profile id, so the screen can explain it. */
    val lastError = MutableStateFlow<Map<String, Throwable>>(emptyMap())

    private val connector = MyPcConnector(
        keyPair = { p -> MyPcKeys.keyPair(p.keyAlias) },
        onConnected = { p, host -> markSeen(p.profileId, host) },
    )

    /** This install's device id, used only in the authorized_keys comment. Not a secret. */
    val deviceId: String by lazy {
        prefs.getString(KEY_DEVICE, null)?.takeIf(MyPcIds::isDeviceId) ?: MyPcIds.newDeviceId().also {
            prefs.edit { putString(KEY_DEVICE, it) }
        }
    }

    private fun load(): List<PcProfile> =
        runCatching { json.decodeFromString(serializer, prefs.getString(KEY_LIST, "[]") ?: "[]") }.getOrDefault(emptyList())

    @Synchronized
    private fun persist(list: List<PcProfile>) {
        prefs.edit { putString(KEY_LIST, json.encodeToString(serializer, list)) }
        _profiles.value = list
    }

    fun list(): List<PcProfile> = _profiles.value
    fun get(profileId: String): PcProfile? = list().firstOrNull { it.profileId == profileId }

    /** Adds a PC by hand and creates its Keystore key. */
    fun addManual(name: String, user: String, hosts: List<String>, port: Int, pins: List<String>, startPath: String): PcProfile {
        val p = PcProfile(
            profileId = MyPcIds.newProfileId(),
            name = name.trim(),
            user = user.trim(),
            hosts = hosts,
            port = port,
            hostKeys = pins,
            startPath = startPath.trim(),
            pairedAt = System.currentTimeMillis(),
        )
        MyPcKeys.ensure(p.keyAlias)
        synchronized(this) { persist(list() + p) }
        return p
    }

    /** Updates name, hosts, port, user or start folder. Host-key pins are never changed here. */
    fun update(profileId: String, edit: (PcProfile) -> PcProfile) = synchronized(this) {
        val cur = get(profileId) ?: return@synchronized
        val next = edit(cur).copy(profileId = cur.profileId, hostKeys = cur.hostKeys)
        persist(list().map { if (it.profileId == profileId) next else it })
        close(profileId)
    }

    /**
     * Pins the first host key of a manual profile after the user compared it with the PC. Refused
     * once anything is pinned: a changed key is a hard stop, never a re-pin.
     */
    fun pinFirstKey(profileId: String, pin: String) = synchronized(this) {
        require(HostKeys.isValidPin(pin)) { "Bad host key" }
        val cur = get(profileId) ?: return@synchronized
        check(cur.hostKeys.isEmpty()) { "This PC already has a pinned host key. Remove it and add it again to re-pair." }
        persist(list().map { if (it.profileId == profileId) it.copy(hostKeys = listOf(pin)) else it })
    }

    fun remove(profileId: String) {
        close(profileId)
        val p = get(profileId) ?: return
        runCatching { MyPcKeys.delete(p.keyAlias) }
        synchronized(this) { persist(list().filterNot { it.profileId == profileId }) }
    }

    private fun markSeen(profileId: String, host: String) = synchronized(this) {
        val now = System.currentTimeMillis()
        persist(list().map { if (it.profileId == profileId) it.copy(lastSeenHost = host, lastSeenAt = now) else it })
    }

    /** `restrict ecdsa-sha2-nistp256 AAAA… victorpc:<deviceId>:filemanager`, for ~/.ssh/authorized_keys. */
    fun authorizedKeysLine(profile: PcProfile): String =
        SshKeyFormat.authorizedKeysLine(MyPcKeys.ensure(profile.keyAlias), deviceId)

    /** The shared session a pane uses. Throws the typed errors from [MyPcConnector]. */
    fun connect(profileId: String): MyPcSftpBackend {
        open[profileId]?.takeIf { it.isOpen }?.let { return it }
        val p = get(profileId) ?: throw IllegalArgumentException("Unknown PC")
        return synchronized(lockFor(profileId)) {
            open[profileId]?.takeIf { it.isOpen } ?: try {
                connector.open(p).also {
                    open[profileId] = it
                    lastError.value = lastError.value - profileId
                }
            } catch (t: Throwable) {
                lastError.value = lastError.value + (profileId to t)
                throw t
            }
        }
    }

    /** A separate session for background transfers, so they never share the pane's SFTP channel. */
    fun openDetached(profileId: String): MyPcSftpBackend {
        val p = get(profileId) ?: throw IllegalArgumentException("This PC was removed")
        return connector.open(p)
    }

    /** For [ai.victorious.filemanager.core.net.CompositeFileIo]: `mypc-<id>` connection ids. */
    fun backendOrNull(connectionId: String): RemoteBackend? {
        val id = MyPcIds.profileIdOf(connectionId) ?: return null
        return runCatching { connect(id) }.getOrNull()
    }

    /** My PC entries for the `/remote` root listing. */
    fun asConnections(): List<SavedConnection> = list().map {
        SavedConnection(
            id = it.connectionId,
            name = "My PC · ${it.name}",
            protocol = RemoteProtocol.SFTP,
            host = it.hosts.firstOrNull().orEmpty(),
            port = it.port,
            username = it.user,
            initialPath = it.startPath.ifBlank { "/" },
            authKind = AuthKind.PRIVATE_KEY,
            keyLabel = it.keyAlias,
        )
    }

    fun close(profileId: String) {
        open.remove(profileId)?.close()
    }

    private val locks = ConcurrentHashMap<String, Any>()
    private fun lockFor(id: String) = locks.getOrPut(id) { Any() }

    companion object {
        private const val KEY_LIST = "profiles_json"
        private const val KEY_DEVICE = "device_id"
    }
}
