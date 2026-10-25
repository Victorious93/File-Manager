package ai.victorious.filemanager.core.archive

import java.io.File
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.EncryptionMethod

class WrongPasswordException(message: String) : java.io.IOException(message)

/** Password-protected ZIP support via zip4j (Apache-2.0). */
object EncryptedZip {
    fun isEncrypted(file: File): Boolean = runCatching { ZipFile(file).isEncrypted }.getOrDefault(false)

    /**
     * True when [password] decrypts this ZIP. Writes nothing: it reads the smallest file entry fully
     * (so the CRC / AES MAC is checked, not just the 1-2 byte quick check) and discards the bytes.
     * An archive with no file entries has nothing to verify and returns true.
     */
    fun verifyPassword(file: File, password: CharArray): Boolean {
        val zip = ZipFile(file, password)
        val probe = zip.fileHeaders.filter { !it.isDirectory }.minByOrNull { it.uncompressedSize } ?: return true
        return try {
            zip.getInputStream(probe).use { input ->
                val buf = ByteArray(8192)
                while (input.read(buf) >= 0) { /* discard */ }
            }
            true
        } catch (e: ZipException) {
            if (e.type == ZipException.Type.WRONG_PASSWORD || e.message?.contains("password", true) == true) false else throw e
        }
    }

    /** Extracts all entries, rejecting zip-slip paths. Returns number of files written. */
    fun extract(file: File, destDir: File, password: CharArray?): Int {
        val zip = ZipFile(file, password)
        destDir.mkdirs()
        var n = 0
        try {
            for (h in zip.fileHeaders) {
                val target = Archives.safeTarget(destDir, h.fileName)
                if (h.isDirectory) { target.mkdirs(); continue }
                target.parentFile?.mkdirs()
                zip.getInputStream(h).use { input -> target.outputStream().use { input.copyTo(it) } }
                n++
            }
        } catch (e: ZipException) {
            if (e.type == ZipException.Type.WRONG_PASSWORD || e.message?.contains("password", true) == true) {
                throw WrongPasswordException("Wrong password")
            }
            throw e
        }
        return n
    }

    fun create(sources: List<File>, output: File, password: CharArray?, aes: Boolean = true): Int {
        val params = ZipParameters().apply {
            if (password != null && password.isNotEmpty()) {
                isEncryptFiles = true
                encryptionMethod = if (aes) EncryptionMethod.AES else EncryptionMethod.ZIP_STANDARD
                if (aes) aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
            }
        }
        val zip = ZipFile(output, password?.takeIf { it.isNotEmpty() })
        for (s in sources) if (s.isDirectory) zip.addFolder(s, params) else zip.addFile(s, params)
        return zip.fileHeaders.count { !it.isDirectory }
    }
}
