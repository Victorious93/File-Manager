package ai.victorious.filemanager

import ai.victorious.filemanager.core.bookmarks.BookmarkStore
import ai.victorious.filemanager.core.fleet.BridgeExecutor
import ai.victorious.filemanager.core.fleet.CallerPolicy
import ai.victorious.filemanager.core.fs.FileIo
import ai.victorious.filemanager.core.fs.NioFileIo
import ai.victorious.filemanager.core.ops.OperationQueue
import ai.victorious.filemanager.core.search.FileSearch
import ai.victorious.filemanager.core.trash.TrashStore
import ai.victorious.filemanager.fleet.FleetSettings
import ai.victorious.filemanager.priv.EscalatingFileIo
import ai.victorious.filemanager.priv.PrivilegeManager
import ai.victorious.filemanager.priv.PrivilegeSettings
import ai.victorious.filemanager.terminal.TerminalSessionManager
import android.app.Application
import java.io.File
import java.security.SecureRandom

class FileManagerApplication : Application() {
    lateinit var normalIo: NioFileIo
        private set
    lateinit var io: FileIo
        private set
    lateinit var trash: TrashStore
        private set
    lateinit var bookmarks: BookmarkStore
        private set
    lateinit var queue: OperationQueue
        private set
    lateinit var search: FileSearch
        private set
    lateinit var nodeId: String
        private set
    lateinit var bridge: BridgeExecutor
        private set
    lateinit var fleetSettings: FleetSettings
        private set
    lateinit var privilegeSettings: PrivilegeSettings
        private set
    lateinit var privilege: PrivilegeManager
        private set
    lateinit var terminals: TerminalSessionManager
        private set

    override fun onCreate() {
        super.onCreate()
        privilegeSettings = PrivilegeSettings(this)
        privilege = PrivilegeManager(privilegeSettings)
        normalIo = NioFileIo()
        io = EscalatingFileIo(normalIo, privilege)
        trash = TrashStore(io, File(filesDir, "trash").absolutePath)
        bookmarks = BookmarkStore(File(filesDir, "bookmarks.json").absolutePath)
        queue = OperationQueue(io, trash)
        search = FileSearch(io)
        nodeId = loadOrCreateNodeId()
        fleetSettings = FleetSettings(this)
        terminals = TerminalSessionManager(privilege)
        bridge = BridgeExecutor(
            nodeId = nodeId,
            policy = CallerPolicy(certPins = CallerPolicy.parsePins(BuildConfig.FLEET_CALLER_CERT_PINS)),
            externalControlEnabled = fleetSettings::isExternalControlEnabled,
        )
    }

    private fun loadOrCreateNodeId(): String {
        val prefs = getSharedPreferences("fleet", MODE_PRIVATE)
        val existing = prefs.getString("node_id", null)
        if (!existing.isNullOrBlank()) return existing
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        val id = "fm-" + bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString("node_id", id).apply()
        return id
    }
}
