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
import ai.victorious.filemanager.core.net.CompositeFileIo
import ai.victorious.filemanager.net.ConnectionRepository
import ai.victorious.filemanager.net.WifiTransferController
import ai.victorious.filemanager.terminal.TerminalSessionManager
import ai.victorious.filemanager.ai.AiSettings
import ai.victorious.filemanager.plugins.PluginRepository
import ai.victorious.filemanager.secure.SecureFolderManager
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
    lateinit var connections: ConnectionRepository
        private set
    lateinit var wifiTransfer: WifiTransferController
        private set
    lateinit var aiSettings: AiSettings
        private set
    lateinit var plugins: PluginRepository
        private set
    lateinit var secureFolder: SecureFolderManager
        private set

    override fun onCreate() {
        super.onCreate()
        privilegeSettings = PrivilegeSettings(this)
        privilege = PrivilegeManager(privilegeSettings)
        normalIo = NioFileIo()
        connections = ConnectionRepository(this)
        val escalating = EscalatingFileIo(normalIo, privilege)
        io = CompositeFileIo(
            local = escalating,
            backends = { id -> connections.backendOrNull(id) },
            listConnections = { connections.list() },
        )
        trash = TrashStore(io, File(filesDir, "trash").absolutePath)
        bookmarks = BookmarkStore(File(filesDir, "bookmarks.json").absolutePath)
        queue = OperationQueue(io, trash)
        search = FileSearch(io)
        nodeId = loadOrCreateNodeId()
        fleetSettings = FleetSettings(this)
        terminals = TerminalSessionManager(privilege)
        wifiTransfer = WifiTransferController(io)
        aiSettings = AiSettings(this).also { runCatching { it.load() } }
        plugins = PluginRepository(this, privilege)
        secureFolder = SecureFolderManager(this)
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
