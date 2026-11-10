package ai.victorious.filemanager.core.apps

import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Passphrase encryption for app backups ("VFBE1"), designed for this app from standard JCA parts.
 * It is not App Manager's OpenPGP / RSA / ECC scheme.
 *
 * File layout (all integers big-endian):
 *   "VFBE" | version=1 | kdf=1 (PBKDF2-HMAC-SHA256) | iterations u32 | saltLen u8 | salt | noncePrefix[8] | chunkSize u32
 *   then chunks: cipherLen u32 | AES-256-GCM(ciphertext + 16-byte tag)
 * Chunk i uses nonce = noncePrefix || i (u32) and AAD = header || i (u64) || isLast (u8).
 * The last chunk is always written (it may be empty), so a cut-off, reordered or extended file fails
 * to decrypt instead of restoring a partial APK. The passphrase and derived key are never stored or logged.
 */
object BackupCrypto {
    const val EXTENSION = ".vfbe"
    const val FORMAT = "VFBE1"
    const val DEFAULT_ITERATIONS = 600_000
    const val MAX_ITERATIONS = 10_000_000
    const val CHUNK = 1 shl 20
    private val MAGIC = byteArrayOf('V'.code.toByte(), 'F'.code.toByte(), 'B'.code.toByte(), 'E'.code.toByte())
    private val rng = SecureRandom()

    class WrongPassphraseOrDamaged : Exception("Wrong passphrase, or the backup file is damaged")

    fun isEncrypted(head: ByteArray): Boolean = head.size >= 4 && head.copyOf(4).contentEquals(MAGIC)

    fun encrypt(input: InputStream, output: OutputStream, passphrase: CharArray, iterations: Int = DEFAULT_ITERATIONS, chunkSize: Int = CHUNK) {
        require(passphrase.size >= 8) { "Use a passphrase of at least 8 characters" }
        require(iterations in 1..MAX_ITERATIONS && chunkSize in 16..(16 shl 20))
        val salt = ByteArray(16).also(rng::nextBytes)
        val prefix = ByteArray(8).also(rng::nextBytes)
        val header = ByteBuffer.allocate(4 + 1 + 1 + 4 + 1 + salt.size + 8 + 4)
            .put(MAGIC).put(1).put(1).putInt(iterations).put(salt.size.toByte()).put(salt).put(prefix).putInt(chunkSize).array()
        output.write(header)
        val key = derive(passphrase, salt, iterations)
        run {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            var cur = readChunk(input, chunkSize)
            var index = 0L
            while (true) {
                val next = if (cur.size == chunkSize) readChunk(input, chunkSize) else ByteArray(0)
                val last = next.isEmpty()
                cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce(prefix, index)))
                cipher.updateAAD(aad(header, index, last))
                val ct = cipher.doFinal(cur)
                output.write(ByteBuffer.allocate(4).putInt(ct.size).array()); output.write(ct)
                if (last) break
                cur = next; index++
            }
            output.flush()
        }
    }

    fun decrypt(input: InputStream, output: OutputStream, passphrase: CharArray) {
        val din = DataInputStream(input)
        val fixed = ByteArray(11)
        runCatching { din.readFully(fixed) }.onFailure { throw WrongPassphraseOrDamaged() }
        if (!isEncrypted(fixed) || fixed[4].toInt() != 1 || fixed[5].toInt() != 1) throw IllegalArgumentException("Not a VFBE1 encrypted backup")
        val iterations = ByteBuffer.wrap(fixed, 6, 4).int
        val saltLen = fixed[10].toInt() and 0xFF
        if (iterations !in 1..MAX_ITERATIONS || saltLen !in 8..64) throw WrongPassphraseOrDamaged()
        val rest = ByteArray(saltLen + 8 + 4)
        runCatching { din.readFully(rest) }.onFailure { throw WrongPassphraseOrDamaged() }
        val header = fixed + rest
        val salt = rest.copyOf(saltLen)
        val prefix = rest.copyOfRange(saltLen, saltLen + 8)
        val chunkSize = ByteBuffer.wrap(rest, saltLen + 8, 4).int
        if (chunkSize !in 16..(16 shl 20)) throw WrongPassphraseOrDamaged()
        val key = derive(passphrase, salt, iterations)
        run {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            var cur = readFrame(din, chunkSize) ?: throw WrongPassphraseOrDamaged()
            var index = 0L
            while (true) {
                val next = readFrame(din, chunkSize)
                val last = next == null
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce(prefix, index)))
                cipher.updateAAD(aad(header, index, last))
                val pt = try { cipher.doFinal(cur) } catch (e: AEADBadTagException) { throw WrongPassphraseOrDamaged() }
                output.write(pt)
                if (last) break
                cur = next!!; index++
            }
            output.flush()
        }
    }

    /** Encrypts [src] to `src.vfbe` and deletes the plain file only after the encrypted copy is complete. */
    fun encryptFile(src: File, passphrase: CharArray, iterations: Int = DEFAULT_ITERATIONS): File {
        val out = File(src.path + EXTENSION)
        val tmp = File(out.path + ".part")
        try {
            src.inputStream().buffered().use { i -> tmp.outputStream().buffered().use { o -> encrypt(i, o, passphrase, iterations) } }
            if (!tmp.renameTo(out)) error("Could not write ${out.name}")
        } catch (e: Throwable) { tmp.delete(); throw e }
        src.delete()
        return out
    }

    /** Decrypts `x.vfbe` into [dest]; on any failure the partial output is deleted. */
    fun decryptFile(src: File, dest: File, passphrase: CharArray): File {
        try {
            src.inputStream().buffered().use { i -> dest.outputStream().buffered().use { o -> decrypt(i, o, passphrase) } }
        } catch (e: Throwable) { dest.delete(); throw e }
        return dest
    }

    private fun derive(pass: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(pass, salt, iterations, 256)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES").also { bytes.fill(0) }
        } finally { spec.clearPassword() }
    }

    private fun nonce(prefix: ByteArray, i: Long): ByteArray {
        require(i in 0..0xFFFFFFFFL) { "Backup too large" }
        return ByteBuffer.allocate(12).put(prefix).putInt(i.toInt()).array()
    }

    private fun aad(header: ByteArray, i: Long, last: Boolean): ByteArray =
        ByteBuffer.allocate(header.size + 9).put(header).putLong(i).put(if (last) 1 else 0).array()

    private fun readChunk(input: InputStream, size: Int): ByteArray {
        val b = ByteArray(size); var n = 0
        while (n < size) { val r = input.read(b, n, size - n); if (r < 0) break; n += r }
        return if (n == size) b else b.copyOf(n)
    }

    /** Next length-prefixed frame, or null at a clean end of file. */
    private fun readFrame(din: DataInputStream, chunkSize: Int): ByteArray? {
        val first = din.read()
        if (first < 0) return null
        val len = try { (first shl 24) or (din.readUnsignedByte() shl 16) or (din.readUnsignedByte() shl 8) or din.readUnsignedByte() } catch (e: EOFException) { throw WrongPassphraseOrDamaged() }
        if (len < 16 || len > chunkSize + 16) throw WrongPassphraseOrDamaged()
        val b = ByteArray(len)
        try { din.readFully(b) } catch (e: EOFException) { throw WrongPassphraseOrDamaged() }
        return b
    }
}
