package ai.victorious.victorfiles.core.secure

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Chunked AES-256-GCM container. Each chunk has its own random 12-byte IV and 128-bit tag,
 * so large files stream in bounded memory and any tampering fails authentication.
 *
 * Layout: MAGIC(6) | chunkSize(int) | { len(int) | iv(12) | ciphertext+tag(len) }* | len=0
 * The chunk index is bound in as AAD so chunks cannot be reordered or truncated silently.
 */
object VaultCrypto {
    private val MAGIC = byteArrayOf('F'.code.toByte(), 'M'.code.toByte(), 'V'.code.toByte(), 'L'.code.toByte(), 'T'.code.toByte(), 1)
    const val DEFAULT_CHUNK = 64 * 1024
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private val rnd = SecureRandom()

    /** Supplies a fresh cipher; on Android the Keystore picks the IV, so we let the cipher choose. */
    fun interface CipherFactory {
        fun encryptCipher(key: SecretKey): Cipher
    }

    val defaultFactory = CipherFactory { key ->
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            val iv = ByteArray(IV_LEN).also(rnd::nextBytes)
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        }
    }

    fun encrypt(key: SecretKey, input: InputStream, output: OutputStream, chunkSize: Int = DEFAULT_CHUNK, factory: CipherFactory = defaultFactory) {
        require(chunkSize in 1024..(4 * 1024 * 1024))
        output.write(MAGIC)
        writeInt(output, chunkSize)
        val buf = ByteArray(chunkSize)
        var index = 0L
        while (true) {
            val n = readFully(input, buf)
            if (n <= 0) break
            val c = factory.encryptCipher(key)
            c.updateAAD(aad(index, final = false))
            val ct = c.doFinal(buf, 0, n)
            val iv = c.iv
            require(iv.size == IV_LEN) { "Unexpected IV length" }
            writeInt(output, ct.size)
            output.write(iv)
            output.write(ct)
            index++
            if (n < chunkSize) break
        }
        // Authenticated terminator so truncation is detected.
        val c = factory.encryptCipher(key)
        c.updateAAD(aad(index, final = true))
        val endTag = c.doFinal(ByteArray(0))
        writeInt(output, -endTag.size)
        output.write(c.iv)
        output.write(endTag)
        output.flush()
    }

    fun decrypt(key: SecretKey, input: InputStream, output: OutputStream) {
        val din = DataInputStream(input)
        val magic = ByteArray(MAGIC.size)
        try { din.readFully(magic) } catch (e: EOFException) { throw IOException("Not a vault file") }
        if (!magic.contentEquals(MAGIC)) throw IOException("Not a vault file")
        val chunkSize = din.readInt()
        if (chunkSize !in 1024..(4 * 1024 * 1024)) throw IOException("Corrupt vault header")
        var index = 0L
        while (true) {
            val len = try { din.readInt() } catch (e: EOFException) { throw IOException("Vault file truncated") }
            val final = len < 0
            val size = if (final) -len else len
            if (size !in 16..(chunkSize + 16)) throw IOException("Corrupt vault chunk")
            val iv = ByteArray(IV_LEN).also(din::readFully)
            val ct = ByteArray(size).also(din::readFully)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            c.updateAAD(aad(index, final))
            val pt = try { c.doFinal(ct) } catch (e: Exception) { throw IOException("Vault authentication failed (wrong key or tampered)") }
            if (final) {
                if (pt.isNotEmpty()) throw IOException("Corrupt vault terminator")
                break
            }
            output.write(pt)
            index++
        }
        output.flush()
    }

    fun encryptBytes(key: SecretKey, data: ByteArray, factory: CipherFactory = defaultFactory): ByteArray =
        ByteArrayOutputStream().also { encrypt(key, data.inputStream(), it, factory = factory) }.toByteArray()

    fun decryptBytes(key: SecretKey, data: ByteArray): ByteArray =
        ByteArrayOutputStream().also { decrypt(key, data.inputStream(), it) }.toByteArray()

    private fun aad(index: Long, final: Boolean): ByteArray {
        val b = ByteArray(9)
        for (i in 0 until 8) b[i] = (index ushr (56 - 8 * i)).toByte()
        b[8] = if (final) 1 else 0
        return b
    }

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val r = input.read(buf, off, buf.size - off)
            if (r < 0) break
            off += r
        }
        return off
    }

    private fun writeInt(out: OutputStream, v: Int) {
        out.write(v ushr 24 and 0xFF); out.write(v ushr 16 and 0xFF); out.write(v ushr 8 and 0xFF); out.write(v and 0xFF)
    }
}

@Serializable
data class VaultEntry(
    val id: String,
    val originalName: String,
    val originalPath: String,
    val sizeBytes: Long,
    val addedEpochMs: Long,
)

/**
 * Encrypted folder: blobs are opaque random names; the index (real names/paths) is itself
 * encrypted. Keys come from the caller (Android Keystore on device, a test key on the JVM).
 */
class SecureVault(
    private val root: File,
    private val key: () -> SecretKey,
    private val factory: VaultCrypto.CipherFactory = VaultCrypto.defaultFactory,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val indexFile get() = File(root, "index.vlt")
    private val blobs get() = File(root, "blobs")

    fun init() {
        blobs.mkdirs()
        // Keeps any media scanner away even if the folder is ever exposed.
        File(root, ".nomedia").takeIf { !it.exists() }?.createNewFile()
    }

    fun list(): List<VaultEntry> {
        if (!indexFile.exists()) return emptyList()
        val plain = VaultCrypto.decryptBytes(key(), indexFile.readBytes())
        return json.decodeFromString(ListSerializer(VaultEntry.serializer()), plain.decodeToString())
    }

    private fun save(entries: List<VaultEntry>) {
        val bytes = json.encodeToString(ListSerializer(VaultEntry.serializer()), entries).encodeToByteArray()
        val tmp = File(root, "index.vlt.tmp")
        tmp.writeBytes(VaultCrypto.encryptBytes(key(), bytes, factory))
        if (!tmp.renameTo(indexFile)) {
            indexFile.delete()
            if (!tmp.renameTo(indexFile)) throw IOException("Could not write vault index")
        }
    }

    /** Encrypts [source] into the vault. The caller deletes the original after success (move-in). */
    fun add(source: File): VaultEntry {
        init()
        require(source.isFile) { "Only files can be moved into the secure folder" }
        val id = UUID.randomUUID().toString().replace("-", "")
        val blob = File(blobs, id)
        try {
            source.inputStream().buffered().use { input ->
                blob.outputStream().buffered().use { out -> VaultCrypto.encrypt(key(), input, out, factory = factory) }
            }
            val entry = VaultEntry(id, source.name, source.absolutePath, source.length(), now())
            save(list() + entry)
            return entry
        } catch (t: Throwable) {
            blob.delete()
            throw t
        }
    }

    /** Decrypts an entry to [destDir]; removes it from the vault when [remove] (move-out). */
    fun export(id: String, destDir: File, remove: Boolean): File {
        val entries = list()
        val e = entries.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Not in vault")
        destDir.mkdirs()
        val dest = uniqueFile(destDir, e.originalName)
        try {
            File(blobs, id).inputStream().buffered().use { input ->
                dest.outputStream().buffered().use { out -> VaultCrypto.decrypt(key(), input, out) }
            }
        } catch (t: Throwable) {
            dest.delete()
            throw t
        }
        if (remove) {
            save(entries.filterNot { it.id == id })
            File(blobs, id).delete()
        }
        return dest
    }

    fun delete(id: String) {
        save(list().filterNot { it.id == id })
        File(blobs, id).delete()
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (f.exists()) { f = File(dir, "$base ($i)$ext"); i++ }
        return f
    }
}

/**
 * Lock-state logic for the secure folder, kept pure for tests. The folder locks when the app
 * has been in the background, or idle, for longer than the timeout (0 = lock immediately).
 */
/**
 * App-side lock state. [timeoutMs] is the idle/background timeout (0 = lock as soon as the
 * app is backgrounded). [maxSessionMs] is a hard cap since unlock, independent of activity:
 * the Keystore key is only usable for a fixed window after authentication, so the app must
 * not keep claiming "unlocked" past it (operations would fail with UserNotAuthenticated).
 */
class VaultLock(
    private val timeoutMs: () -> Long,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxSessionMs: () -> Long = { Long.MAX_VALUE },
) {
    private var unlockedAt: Long? = null
    private var lastActivity: Long = 0
    private var backgroundedAt: Long? = null

    val isUnlocked: Boolean
        get() {
            evaluate()
            return unlockedAt != null
        }

    fun unlock() {
        val t = now()
        unlockedAt = t
        lastActivity = t
        backgroundedAt = null
    }

    fun lock() {
        unlockedAt = null
        backgroundedAt = null
    }

    fun touch() {
        if (isUnlocked) lastActivity = now()
    }

    fun onBackground() {
        if (unlockedAt != null) backgroundedAt = now()
        if (timeoutMs() <= 0L) lock()
    }

    fun onForeground() {
        evaluate()
        backgroundedAt = null
    }

    private fun evaluate() {
        val since = unlockedAt ?: return
        val t = now()
        if (t - since >= maxSessionMs()) { lock(); return }
        val timeout = timeoutMs()
        val bg = backgroundedAt
        if (bg != null && t - bg >= timeout) { lock(); return }
        if (timeout > 0 && t - lastActivity >= timeout) lock()
    }
}
