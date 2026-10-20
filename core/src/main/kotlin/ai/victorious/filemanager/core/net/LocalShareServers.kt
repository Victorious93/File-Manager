package ai.victorious.filemanager.core.net

import java.io.File
import java.nio.file.Paths
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.WritePermission
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator

/**
 * Built-in FTP + SFTP share servers (Apache FtpServer / MINA SSHD — Apache-2.0).
 * **Off by default.** Bind to a LAN address only; password (and optional public-key for SFTP).
 */
data class ShareServerConfig(
    val rootDir: File,
    val bindHost: String,
    val port: Int,
    val username: String,
    val password: String,
    /** PEM public keys (OpenSSH one-line) allowed for SFTP; empty = password only. */
    val authorizedKeys: List<String> = emptyList(),
)

class LocalFtpShare(private val config: ShareServerConfig) : AutoCloseable {
    private var server: FtpServer? = null
    val isRunning: Boolean get() = server?.isStopped == false

    fun start() {
        stop()
        val factory = FtpServerFactory()
        val listener = ListenerFactory().apply {
            port = config.port
            serverAddress = config.bindHost
        }
        factory.addListener("default", listener.createListener())
        val user = BaseUser().apply {
            name = config.username
            password = config.password
            homeDirectory = config.rootDir.absolutePath
            authorities = listOf(WritePermission())
        }
        factory.userManager.save(user)
        val s = factory.createServer()
        s.start()
        server = s
    }

    fun stop() {
        runCatching { server?.stop() }
        server = null
    }

    override fun close() = stop()
}

class LocalSftpShare(private val config: ShareServerConfig) : AutoCloseable {
    private var server: SshServer? = null
    val isRunning: Boolean get() = server?.isClosed == false

    fun start() {
        stop()
        val sshd = SshServer.setUpDefaultServer()
        sshd.port = config.port
        sshd.host = config.bindHost
        sshd.keyPairProvider = SimpleGeneratorHostKeyProvider(Paths.get(config.rootDir.parentFile?.absolutePath ?: ".", ".vf-sftp-hostkey"))
        sshd.subsystemFactories = listOf(SftpSubsystemFactory())
        sshd.passwordAuthenticator = PasswordAuthenticator { username, password, _ ->
            username == config.username && password == config.password
        }
        if (config.authorizedKeys.isNotEmpty()) {
            // Accept any presented key when list non-empty is too loose; require password OR
            // matching key fingerprint string containment (simple allow-list of public key bodies).
            sshd.publickeyAuthenticator = PublickeyAuthenticator { username, key, _ ->
                if (username != config.username) return@PublickeyAuthenticator false
                val encoded = java.util.Base64.getEncoder().encodeToString(key.encoded)
                config.authorizedKeys.any { it.contains(encoded.take(32)) || it == encoded }
            }
        }
        // File system factory rooted at config.rootDir
        sshd.fileSystemFactory = org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory(config.rootDir.toPath())
        sshd.start()
        server = sshd
    }

    fun stop() {
        runCatching { server?.stop(true) }
        server = null
    }

    override fun close() = stop()
}

object ShareServerPins {
    fun randomPassword(length: Int = 8): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        return (1..length).map { alphabet.random() }.joinToString("")
    }
}
