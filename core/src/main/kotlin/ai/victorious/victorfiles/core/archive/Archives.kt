package ai.victorious.victorfiles.core.archive

import ai.victorious.victorfiles.core.viewer.FileKinds
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream

data class ArchiveItem(val name: String, val isDirectory: Boolean, val size: Long, val compressedSize: Long)

enum class ArchiveFormat { ZIP, SEVEN_Z, TAR, TAR_GZ, RAR, UNKNOWN }

class UnsupportedArchiveException(message: String) : IOException(message)

/**
 * Archive listing/extraction.
 * ZIP (also JAR/APK): java.util.zip. 7z: Apache Commons Compress + XZ (pure Java).
 * TAR / TAR.GZ: Commons Compress. RAR (RAR 1.5 – 7, incl. RAR5, solid, password): junrar,
 * extraction only. junrar is distributed under the UnRAR license: the code may not be used to
 * develop a RAR (WinRAR) compatible archiver. This app never creates RAR archives.
 */
object Archives {
    fun format(file: File): ArchiveFormat {
        val magic = runCatching { file.inputStream().use { s -> ByteArray(8).let { b -> b.copyOf(s.read(b).coerceAtLeast(0)) } } }
            .getOrDefault(ByteArray(0))
        fun starts(vararg sig: Int) = magic.size >= sig.size && sig.indices.all { (magic[it].toInt() and 0xFF) == sig[it] }
        return when {
            starts(0x50, 0x4B) -> ArchiveFormat.ZIP
            starts(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> ArchiveFormat.SEVEN_Z
            starts(0x52, 0x61, 0x72, 0x21) -> ArchiveFormat.RAR
            starts(0x1F, 0x8B) -> ArchiveFormat.TAR_GZ
            FileKinds.extension(file.name) == "tar" -> ArchiveFormat.TAR
            else -> ArchiveFormat.UNKNOWN
        }
    }

    fun list(file: File): List<ArchiveItem> = when (format(file)) {
        ArchiveFormat.ZIP -> ZipFile(file).use { zip ->
            zip.entries().asSequence().map { ArchiveItem(it.name, it.isDirectory, it.size, it.compressedSize) }.toList()
        }
        ArchiveFormat.SEVEN_Z -> SevenZFile.builder().setFile(file).get().use { z ->
            z.entries.map { ArchiveItem(it.name, it.isDirectory, if (it.hasStream()) it.size else 0, -1) }
        }
        ArchiveFormat.TAR, ArchiveFormat.TAR_GZ -> tarStream(file).use { tar ->
            generateSequence { tar.nextEntry }.map { ArchiveItem(it.name, it.isDirectory, it.size, -1) }.toList()
        }
        ArchiveFormat.RAR -> rar(file, null) { a ->
            if (a.hasBrokenHeaders() || (a.fileHeaders.isEmpty() && a.mainHeader == null)) throw IOException("Corrupt or truncated RAR")
            a.fileHeaders.map { h -> ArchiveItem(rarName(h), h.isDirectory, h.fullUnpackSize, h.fullPackSize) }
        }
        ArchiveFormat.UNKNOWN -> throw UnsupportedArchiveException("Unrecognised archive format")
    }

    class RarPasswordException(message: String) : IOException(message)

    /** True when a RAR needs a password (encrypted headers or encrypted entries). */
    fun isRarPasswordProtected(file: File): Boolean = runCatching {
        com.github.junrar.Archive(file).use { it.isPasswordProtected || it.isEncrypted || it.fileHeaders.any { h -> h.isEncrypted } }
    }.getOrElse { it is com.github.junrar.exception.UnsupportedRarEncryptedException || it.cause is com.github.junrar.exception.UnsupportedRarEncryptedException }

    private fun rarName(h: com.github.junrar.rarfile.FileHeader) = h.fileName.replace('\\', '/')

    private fun <T> rar(file: File, password: String?, block: (com.github.junrar.Archive) -> T): T = try {
        (if (password != null) com.github.junrar.Archive(file, password) else com.github.junrar.Archive(file)).use(block)
    } catch (e: com.github.junrar.exception.WrongPasswordException) {
        throw RarPasswordException("Wrong password")
    } catch (e: com.github.junrar.exception.UnsupportedRarEncryptedException) {
        throw RarPasswordException("Password required")
    } catch (e: com.github.junrar.exception.RarException) {
        throw IOException("RAR: ${e.message ?: e.javaClass.simpleName}", e)
    }

    /** RAR extraction (optionally with a password). Same zip-slip guard as the other formats. */
    fun extractRar(file: File, destDir: File, password: String? = null): Int {
        destDir.mkdirs()
        var count = 0
        rar(file, password) { a ->
            if (a.hasBrokenHeaders()) throw IOException("Corrupt or truncated RAR")
            for (h in a.fileHeaders) {
                if (write(destDir, rarName(h), h.isDirectory) { a.getInputStream(h) }) count++
            }
        }
        return count
    }

    /** Extracts everything into [destDir]; returns number of files written. Guards against zip-slip. */
    fun extract(file: File, destDir: File): Int {
        destDir.mkdirs()
        var count = 0
        when (format(file)) {
            ArchiveFormat.ZIP -> ZipFile(file).use { zip ->
                for (e in zip.entries()) {
                    if (write(destDir, e.name, e.isDirectory) { zip.getInputStream(e) }) count++
                }
            }
            ArchiveFormat.SEVEN_Z -> SevenZFile.builder().setFile(file).get().use { z ->
                var e = z.nextEntry
                while (e != null) {
                    val entry = e
                    if (write(destDir, entry.name, entry.isDirectory) { z.getInputStream(entry) }) count++
                    e = z.nextEntry
                }
            }
            ArchiveFormat.TAR, ArchiveFormat.TAR_GZ -> tarStream(file).use { tar ->
                var e = tar.nextEntry
                while (e != null) {
                    if (write(destDir, e.name, e.isDirectory) { NonClosing(tar) }) count++
                    e = tar.nextEntry
                }
            }
            ArchiveFormat.RAR -> count = extractRar(file, destDir)
            else -> list(file) // throws the right message
        }
        return count
    }

    fun safeTarget(destDir: File, entryName: String): File {
        val root = destDir.canonicalFile
        val target = File(root, entryName).canonicalFile
        if (target != root && !target.path.startsWith(root.path + File.separator)) {
            throw IOException("Blocked unsafe archive entry: $entryName")
        }
        return target
    }

    private fun write(destDir: File, name: String, isDir: Boolean, open: () -> InputStream): Boolean {
        val target = safeTarget(destDir, name)
        if (isDir) { target.mkdirs(); return false }
        target.parentFile?.mkdirs()
        open().use { input -> target.outputStream().use { input.copyTo(it) } }
        return true
    }

    private fun tarStream(file: File): TarArchiveInputStream {
        val raw = BufferedInputStream(file.inputStream())
        return if (format(file) == ArchiveFormat.TAR_GZ) TarArchiveInputStream(GzipCompressorInputStream(raw)) else TarArchiveInputStream(raw)
    }

    /**
     * Creates [output] containing [sources] (files or folders, recursively). Folder contents are
     * stored under the folder's own name. Returns the number of file entries written.
     */
    fun createZip(sources: List<File>, output: File): Int {
        require(sources.isNotEmpty()) { "Nothing to zip" }
        val outCanon = output.canonicalFile
        var count = 0
        val seen = HashSet<String>()
        ZipOutputStream(output.outputStream().buffered()).use { zos ->
            fun add(file: File, entryName: String) {
                if (file.canonicalFile == outCanon) return
                if (file.isDirectory) {
                    val dirName = "$entryName/"
                    if (seen.add(dirName)) { zos.putNextEntry(ZipEntry(dirName).apply { time = file.lastModified() }); zos.closeEntry() }
                    file.listFiles()?.sortedBy { it.name }?.forEach { add(it, "$entryName/${it.name}") }
                } else {
                    if (!seen.add(entryName)) return
                    zos.putNextEntry(ZipEntry(entryName).apply { time = file.lastModified() })
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                    count++
                }
            }
            sources.forEach { add(it, it.name) }
        }
        return count
    }

    /** "photos" -> photos.zip, or photos (2).zip if taken. */
    fun uniqueZipName(dir: File, base: String): File {
        var candidate = File(dir, "$base.zip")
        var n = 2
        while (candidate.exists()) { candidate = File(dir, "$base ($n).zip"); n++ }
        return candidate
    }

    private class NonClosing(private val inner: InputStream) : InputStream() {
        override fun read() = inner.read()
        override fun read(b: ByteArray, off: Int, len: Int) = inner.read(b, off, len)
        override fun close() {}
    }
}
