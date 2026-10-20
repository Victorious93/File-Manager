package ai.victorious.filemanager.net

import ai.victorious.filemanager.core.net.LocalFtpShare
import ai.victorious.filemanager.core.net.LocalSftpShare
import ai.victorious.filemanager.core.net.ShareServerConfig
import ai.victorious.filemanager.core.net.ShareServerPins
import ai.victorious.filemanager.core.net.WifiTransferServer
import java.io.File
import java.util.concurrent.atomic.AtomicReference

data class ServerModeSession(
    val kind: String, // ftp | sftp | web
    val url: String,
    val bindHost: String,
    val port: Int,
    val username: String,
    val password: String,
)

/**
 * Built-in share servers — **off by default**. LAN bind only. Password auth (SFTP can add keys).
 */
class ServerModeController(private val wifi: WifiTransferController) {
    private val ftp = AtomicReference<LocalFtpShare?>(null)
    private val sftp = AtomicReference<LocalSftpShare?>(null)
    var session: ServerModeSession? = null
        private set

    fun stopAll() {
        ftp.getAndSet(null)?.stop()
        sftp.getAndSet(null)?.stop()
        wifi.stop()
        session = null
    }

    fun startFtp(root: File, port: Int = 2121, username: String = "vf"): ServerModeSession {
        stopAll()
        val host = WifiTransferServer.lanIpv4() ?: "127.0.0.1"
        val pass = ShareServerPins.randomPassword()
        val share = LocalFtpShare(ShareServerConfig(root, host, port, username, pass))
        share.start()
        ftp.set(share)
        return ServerModeSession("ftp", "ftp://$username@$host:$port/", host, port, username, pass).also { session = it }
    }

    fun startSftp(root: File, port: Int = 2222, username: String = "vf", authorizedKeys: List<String> = emptyList()): ServerModeSession {
        stopAll()
        val host = WifiTransferServer.lanIpv4() ?: "127.0.0.1"
        val pass = ShareServerPins.randomPassword()
        val share = LocalSftpShare(ShareServerConfig(root, host, port, username, pass, authorizedKeys))
        share.start()
        sftp.set(share)
        return ServerModeSession("sftp", "sftp://$username@$host:$port/", host, port, username, pass).also { session = it }
    }

    fun startWeb(rootPath: String, port: Int = 8765, requirePin: Boolean = true): ServerModeSession {
        stopAll()
        val w = wifi.start(rootPath, port, requirePin)
        return ServerModeSession("web", w.url, w.bindHost, port, "pin", w.pin ?: "").also { session = it }
    }

    fun qrContent(): String = session?.url.orEmpty()
}
