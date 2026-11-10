@file:Suppress("DEPRECATION") // sshlib 2.2.x marks its com.trilead API deprecated; see docs/MYPC.md "Library status".

package ai.victorious.filemanager.core.mypc

import ai.victorious.filemanager.core.fs.FileEntry
import ai.victorious.filemanager.core.net.RemoteBackend
import ai.victorious.filemanager.core.net.RemotePaths
import com.trilead.ssh2.ChannelCondition
import com.trilead.ssh2.Connection
import com.trilead.ssh2.SFTPException
import com.trilead.ssh2.SFTPv3Client
import com.trilead.ssh2.SFTPv3FileAttributes
import com.trilead.ssh2.SFTPv3FileHandle
import com.trilead.ssh2.ServerHostKeyVerifier
import com.trilead.ssh2.sftp.ErrorCodes
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyPair

/** The PC answered with a host key that is not pinned. Hard stop; the only fix is to re-pair. */
class HostKeyMismatchException(val host: String, val presented: String, val expected: List<String>) :
    IOException("Host key changed for $host. Connection blocked: re-pair this PC.")

/** Manual profile, nothing pinned yet: the user must confirm [presented] before anything else happens. */
class HostKeyUnpinnedException(val host: String, val presented: String) :
    IOException("First connection to $host: confirm its host key before continuing.")

/** The PC is the right one (key pinned) but it refused this app's key. */
class KeyRejectedException(val user: String, val host: String) :
    IOException("$host refused this app's key for user $user. Add the public key to ~/.ssh/authorized_keys.")

class PcUnreachableException(val tried: List<String>) :
    IOException("Could not reach the PC (" + tried.joinToString("; ") + ")")

/**
 * Opens My PC sessions with sshlib. Hosts are tried in [ConnectPlan] order; every host must show a
 * pinned key ([HostKeys.check]) before this app's key is offered. A mismatch on any host stops
 * the whole attempt instead of trying the next address.
 */
class MyPcConnector(
    private val keyPair: (PcProfile) -> KeyPair,
    private val onConnected: (PcProfile, String) -> Unit = { _, _ -> },
) {
    fun open(profile: PcProfile): MyPcSftpBackend {
        val (conn, host) = connect(profile)
        return try {
            MyPcSftpBackend(profile.connectionId, conn, SFTPv3Client(conn)).also { onConnected(profile, host) }
        } catch (t: Throwable) {
            conn.close()
            throw t
        }
    }

    @Suppress("DEPRECATION")
    fun connect(profile: PcProfile): Pair<Connection, String> {
        val tried = mutableListOf<String>()
        for (attempt in ConnectPlan.plan(profile)) {
            val conn = Connection(ConnectPlan.socketHost(attempt.host), profile.port)
            var verdict: HostKeyVerdict? = null
            val verifier = ServerHostKeyVerifier { _, _, algorithm, blob ->
                val v = runCatching { HostKeys.check(profile.hostKeys, algorithm, blob) }
                    .getOrElse { HostKeyVerdict.Mismatch("unreadable key", profile.hostKeys) }
                verdict = v
                v is HostKeyVerdict.Match
            }
            try {
                conn.connect(verifier, attempt.connectTimeoutMs, ConnectPlan.KEX_TIMEOUT_MS)
            } catch (e: IOException) {
                conn.close()
                when (val v = verdict) {
                    is HostKeyVerdict.Mismatch -> throw HostKeyMismatchException(attempt.host, v.presented, v.expected)
                    is HostKeyVerdict.Unpinned -> throw HostKeyUnpinnedException(attempt.host, v.presented)
                    else -> { tried += "${attempt.host}: ${e.cause?.message ?: e.message ?: "no answer"}"; continue }
                }
            }
            // Only reached with a pinned key (the verifier refuses anything else).
            val ok = try {
                conn.authenticateWithPublicKey(profile.user, keyPair(profile))
            } catch (e: IOException) {
                conn.close()
                throw e
            }
            if (!ok) {
                conn.close()
                throw KeyRejectedException(profile.user, attempt.host)
            }
            return conn to attempt.host
        }
        throw PcUnreachableException(tried)
    }
}

/**
 * My PC as a [RemoteBackend] (dual-pane browsing under `/remote/mypc-<id>/…`) and a
 * [TransferSide] (resumable background transfers). sshlib's SFTP client is not thread-safe, so
 * every call holds [lock].
 */
class MyPcSftpBackend internal constructor(
    override val connectionId: String,
    private val conn: Connection,
    private val sftp: SFTPv3Client,
) : RemoteBackend, TransferSide {
    private val lock = Any()
    @Volatile private var closed = false

    init {
        // A dropped session (Wi-Fi change, PC asleep) marks this backend dead so the next call reconnects.
        conn.addConnectionMonitor { closed = true }
    }

    val isOpen: Boolean get() = !closed

    /** The login user's home folder on the PC. */
    fun home(): String = synchronized(lock) { sftp.canonicalPath(".") }

    private fun clean(p: String) = p.ifBlank { "/" }

    private fun statOrNull(path: String): SFTPv3FileAttributes? = synchronized(lock) {
        try {
            sftp.stat(clean(path))
        } catch (e: SFTPException) {
            if (e.serverErrorCode == ErrorCodes.SSH_FX_NO_SUCH_FILE) null else throw e
        }
    }

    override fun list(remotePath: String): List<FileEntry> = synchronized(lock) {
        val dir = clean(remotePath)
        sftp.ls(dir).filter { it.filename != "." && it.filename != ".." }.map { e ->
            val child = if (dir == "/") "/${e.filename}" else "${dir.trimEnd('/')}/${e.filename}"
            val attrs = if (e.attributes?.isSymlink == true) runCatching { sftp.stat(child) }.getOrDefault(e.attributes) else e.attributes
            FileEntry(
                path = RemotePaths.toVirtual(connectionId, child),
                name = e.filename,
                isDirectory = attrs?.isDirectory == true,
                sizeBytes = if (attrs?.isDirectory == true) 0L else attrs?.size ?: 0L,
                modifiedEpochMs = (attrs?.mtime ?: 0L) * 1000L,
                isHidden = e.filename.startsWith('.'),
            )
        }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    override fun exists(remotePath: String): Boolean = statOrNull(remotePath) != null
    override fun isDirectory(remotePath: String): Boolean = statOrNull(remotePath)?.isDirectory == true
    override fun mkdir(remotePath: String) = synchronized(lock) { sftp.mkdir(remotePath, 0b111_101_101) }

    override fun delete(remotePath: String) {
        val st = statOrNull(remotePath) ?: throw IOException("Missing: $remotePath")
        synchronized(lock) { if (st.isDirectory) sftp.rmdir(remotePath) else sftp.rm(remotePath) }
    }

    override fun rename(from: String, to: String) {
        if (exists(to)) throw IOException("Destination already exists: $to")
        synchronized(lock) { sftp.mv(from, to) }
    }

    override fun openInput(remotePath: String): InputStream = openRead(remotePath, 0L)
    override fun openOutput(remotePath: String): OutputStream = openWrite(remotePath, 0L)
    override fun sizeOf(remotePath: String): Long = statOrNull(remotePath)?.let { if (it.isDirectory) 0L else it.size ?: 0L } ?: 0L

    // --- TransferSide ---

    override fun stat(path: String): TransferSide.Stat? =
        statOrNull(path)?.let { TransferSide.Stat(if (it.isDirectory) 0L else it.size ?: 0L, it.isDirectory) }

    override fun listNames(dir: String): List<String> = synchronized(lock) {
        sftp.ls(clean(dir)).map { it.filename }.filter { it != "." && it != ".." }.sorted()
    }

    override fun mkdirs(path: String) {
        var cur = ""
        for (part in path.trim('/').split('/').filter { it.isNotEmpty() }) {
            cur = "$cur/$part"
            val st = statOrNull(cur)
            if (st == null) mkdir(cur) else if (!st.isDirectory) throw IOException("Not a folder: $cur")
        }
    }

    override fun openRead(path: String, offset: Long): InputStream {
        val handle = synchronized(lock) { sftp.openFileRO(path) }
        return object : InputStream() {
            private var pos = offset
            private var done = false
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                if (done) return -1
                val n = synchronized(lock) { sftp.read(handle, pos, b, off, minOf(len, 32 * 1024)) }
                if (n < 0) { done = true; return -1 }
                pos += n
                return n
            }
            override fun close() = closeHandle(handle)
        }
    }

    override fun openWrite(path: String, offset: Long): OutputStream {
        val handle = synchronized(lock) {
            if (offset == 0L) sftp.createFileTruncate(path) else sftp.openFileRW(path)
        }
        if (offset > 0L) {
            // Drop anything past the resume point so a longer, stale tail cannot survive.
            synchronized(lock) { sftp.fsetstat(handle, SFTPv3FileAttributes().apply { size = offset }) }
        }
        return object : OutputStream() {
            private var pos = offset
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (len == 0) return
                synchronized(lock) { sftp.write(handle, pos, b, off, len) }
                pos += len
            }
            override fun close() = closeHandle(handle)
        }
    }

    private fun closeHandle(h: SFTPv3FileHandle) {
        if (h.isClosed) return
        synchronized(lock) { runCatching { sftp.closeFile(h) } }
    }

    /**
     * SHA-256 computed on the PC with `sha256sum` (coreutils, present on Ubuntu). Returns null when
     * the command is missing or the account cannot run commands, so callers fall back to size-only.
     */
    override fun sha256(path: String): String? = runCatching {
        val session = synchronized(lock) { conn.openSession() }
        try {
            session.execCommand("sha256sum -b -- " + shellQuote(path))
            val out = session.stdout.bufferedReader().readText()
            session.waitForCondition(ChannelCondition.EXIT_STATUS, 10_000)
            val hex = out.trim().substringBefore(' ').removePrefix("\\").lowercase()
            hex.takeIf { session.exitStatus == 0 && HEX64.matches(it) }
        } finally {
            session.close()
        }
    }.getOrNull()

    override fun close() {
        if (closed) return
        closed = true
        runCatching { synchronized(lock) { sftp.close() } }
        runCatching { conn.close() }
    }

    companion object {
        private val HEX64 = Regex("[0-9a-f]{64}")

        /** POSIX single-quote quoting; the path is data, never shell syntax. */
        fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
    }
}

/** Checks a key pair signs exactly the way sshlib's public-key login will (SHA256withECDSA → SSH form). */
object MyPcCrypto {
    fun signVerifies(pair: KeyPair, message: ByteArray = "victorpc-self-test".toByteArray()): Boolean {
        val pub = pair.public as? java.security.interfaces.ECPublicKey ?: return false
        val verifier = com.trilead.ssh2.signature.ECDSASHA2Verify.getVerifierForKey(pub) ?: return false
        val sig = verifier.generateSignature(message, pair.private, java.security.SecureRandom())
        return verifier.verifySignature(message, sig, pub)
    }
}
