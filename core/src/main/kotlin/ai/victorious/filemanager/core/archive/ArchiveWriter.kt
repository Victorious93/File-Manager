package ai.victorious.filemanager.core.archive

import java.io.File
import java.io.OutputStream
import java.util.Date
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipParameters
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream

/** Formats the app can *create*. RAR is never offered (see [Archives] licence note). */
enum class CreateFormat(val extension: String, val label: String, val supportsPassword: Boolean) {
    ZIP("zip", "ZIP", true),
    SEVEN_Z("7z", "7z (LZMA2)", true),
    TAR("tar", "TAR (no compression)", false),
    TAR_GZ("tar.gz", "TAR.GZ", false),
    TAR_BZ2("tar.bz2", "TAR.BZ2", false),
    TAR_XZ("tar.xz", "TAR.XZ", false),
}

/** Fast = quick and larger; Best = slow and smaller. */
enum class CompressionLevel { STORE, FAST, NORMAL, BEST }

data class CreateResult(val output: File, val files: Int, val folders: Int, val bytesIn: Long, val encrypted: Boolean = false)

/**
 * Creates archives from files and folders (recursively), written from scratch on top of
 * java.util.zip and Apache Commons Compress (Apache-2.0) + XZ for Java (public domain).
 *
 * Entry names: each source is stored under its own name (`photos/a.jpg`), folder entries are kept
 * so empty folders survive, names always use `/`, and the output file itself is never added even if
 * it sits inside a source folder. Duplicate names (two sources called `a.txt`) keep the first.
 * Symlinks are stored as the file they point to (never followed outside a source on a loop).
 *
 * Passwords ([create] `password`): ZIP uses WinZip AES-256 (zip4j, Apache-2.0); 7z uses 7-Zip's own
 * AES-256 + SHA-256 coder (Commons Compress). Both encrypt file *contents*; like 7-Zip's default for
 * ZIP, file and folder *names* stay readable. TAR formats have no encryption and refuse a password.
 * The caller owns the password array and should wipe it afterwards.
 */
object ArchiveWriter {
    fun create(
        sources: List<File>,
        output: File,
        format: CreateFormat,
        level: CompressionLevel = CompressionLevel.NORMAL,
        password: CharArray? = null,
    ): CreateResult {
        require(sources.isNotEmpty()) { "Nothing to compress" }
        sources.forEach { require(it.exists()) { "Missing: ${it.name}" } }
        val pw = password?.takeIf { it.isNotEmpty() }
        require(pw == null || format.supportsPassword) { "${format.label} can't be password protected. Use 7z or ZIP." }
        val entries = collect(sources, output)
        val tmp = File(output.parentFile, ".${output.name}.part")
        try {
            when (format) {
                CreateFormat.ZIP -> if (pw != null) writeEncryptedZip(entries, tmp, level, pw) else writeZip(entries, tmp, level)
                CreateFormat.SEVEN_Z -> write7z(entries, tmp, level, pw)
                else -> tmp.outputStream().buffered().use { raw -> writeTar(entries, compressor(format, raw, level)) }
            }
            if (output.exists() && !output.delete()) throw java.io.IOException("Cannot replace ${output.name}")
            if (!tmp.renameTo(output)) { tmp.copyTo(output, overwrite = true); tmp.delete() }
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
        return CreateResult(output, entries.count { !it.isDir }, entries.count { it.isDir }, entries.filter { !it.isDir }.sumOf { it.file.length() }, pw != null)
    }

    /** "photos" + TAR_GZ -> photos.tar.gz, or photos (2).tar.gz if taken. */
    fun uniqueName(dir: File, base: String, format: CreateFormat): File {
        val clean = base.trim().ifBlank { "archive" }.replace('/', '_')
        var candidate = File(dir, "$clean.${format.extension}")
        var n = 2
        while (candidate.exists()) { candidate = File(dir, "$clean ($n).${format.extension}"); n++ }
        return candidate
    }

    /** Suggested base name: the single source's name without its extension, else "archive". */
    fun suggestedBase(sources: List<File>): String =
        if (sources.size == 1) sources.first().let { if (it.isDirectory) it.name else it.nameWithoutExtension }.ifBlank { "archive" } else "archive"

    internal data class Entry(val file: File, val name: String, val isDir: Boolean)

    internal fun collect(sources: List<File>, output: File): List<Entry> {
        val outCanon = output.canonicalFile
        val partCanon = File(output.parentFile, ".${output.name}.part").canonicalFile
        val out = ArrayList<Entry>()
        val seen = HashSet<String>()
        val visitedDirs = HashSet<String>()
        fun add(f: File, name: String) {
            val canon = f.canonicalFile
            if (canon == outCanon || canon == partCanon) return
            if (f.isDirectory) {
                if (!visitedDirs.add(canon.path)) return // symlink loop
                if (seen.add("$name/")) out += Entry(f, name, true)
                f.listFiles()?.sortedBy { it.name }?.forEach { add(it, "$name/${it.name}") }
            } else if (f.isFile && seen.add(name)) {
                out += Entry(f, name, false)
            }
        }
        sources.forEach { add(it, it.name) }
        return out
    }

    private fun writeZip(entries: List<Entry>, out: File, level: CompressionLevel) {
        ZipOutputStream(out.outputStream().buffered()).use { zos ->
            zos.setLevel(
                when (level) {
                    CompressionLevel.STORE -> Deflater.NO_COMPRESSION
                    CompressionLevel.FAST -> Deflater.BEST_SPEED
                    CompressionLevel.NORMAL -> Deflater.DEFAULT_COMPRESSION
                    CompressionLevel.BEST -> Deflater.BEST_COMPRESSION
                },
            )
            for (e in entries) {
                zos.putNextEntry(ZipEntry(if (e.isDir) "${e.name}/" else e.name).apply { time = e.file.lastModified() })
                if (!e.isDir) e.file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }

    /** WinZip AES-256 ZIP, entry by entry so names, folders and levels match the plain ZIP path. */
    private fun writeEncryptedZip(entries: List<Entry>, out: File, level: CompressionLevel, password: CharArray) {
        net.lingala.zip4j.io.outputstream.ZipOutputStream(out.outputStream().buffered(), password).use { zos ->
            for (e in entries) {
                val p = net.lingala.zip4j.model.ZipParameters().apply {
                    fileNameInZip = if (e.isDir) "${e.name}/" else e.name
                    lastModifiedFileTime = e.file.lastModified()
                    if (e.isDir || level == CompressionLevel.STORE) {
                        compressionMethod = net.lingala.zip4j.model.enums.CompressionMethod.STORE
                    } else {
                        compressionMethod = net.lingala.zip4j.model.enums.CompressionMethod.DEFLATE
                        compressionLevel = when (level) {
                            CompressionLevel.FAST -> net.lingala.zip4j.model.enums.CompressionLevel.FASTEST
                            CompressionLevel.BEST -> net.lingala.zip4j.model.enums.CompressionLevel.MAXIMUM
                            else -> net.lingala.zip4j.model.enums.CompressionLevel.NORMAL
                        }
                    }
                    // Folder entries carry no data, so there is nothing to encrypt.
                    isEncryptFiles = !e.isDir
                    if (!e.isDir) {
                        encryptionMethod = net.lingala.zip4j.model.enums.EncryptionMethod.AES
                        aesKeyStrength = net.lingala.zip4j.model.enums.AesKeyStrength.KEY_STRENGTH_256
                        if (compressionMethod == net.lingala.zip4j.model.enums.CompressionMethod.STORE) entrySize = e.file.length()
                    }
                }
                zos.putNextEntry(p)
                if (!e.isDir) e.file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }

    private fun write7z(entries: List<Entry>, out: File, level: CompressionLevel, password: CharArray? = null) {
        // With a password, Commons Compress appends 7-Zip's AES-256/SHA-256 coder after the methods below.
        (if (password != null) SevenZOutputFile(out, password) else SevenZOutputFile(out)).use { z ->
            val method = if (level == CompressionLevel.STORE) SevenZMethod.COPY else SevenZMethod.LZMA2
            // An Int here would be read as the dictionary size; pass a preset object instead.
            val opts: Any? = if (level == CompressionLevel.STORE) null else org.tukaani.xz.LZMA2Options(xzPreset(level))
            z.setContentMethods(listOf(SevenZMethodConfiguration(method, opts)))
            for (e in entries) {
                val entry = z.createArchiveEntry(e.file, e.name)
                entry.lastModifiedDate = Date(e.file.lastModified())
                z.putArchiveEntry(entry)
                if (!e.isDir) e.file.inputStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) { val n = input.read(buf); if (n < 0) break; z.write(buf, 0, n) }
                }
                z.closeArchiveEntry()
            }
            z.finish()
        }
    }

    /**
     * XZ / LZMA2 preset per level. Capped at 6 because presets 7-9 need ~186-674 MB of encoder
     * memory, which would crash on many phones; 6 needs ~94 MB, 4 ~48 MB, 1 ~9 MB.
     */
    internal fun xzPreset(level: CompressionLevel): Int = when (level) {
        CompressionLevel.STORE -> 0
        CompressionLevel.FAST -> 1
        CompressionLevel.NORMAL -> 4
        CompressionLevel.BEST -> 6
    }

    private fun compressor(format: CreateFormat, raw: OutputStream, level: CompressionLevel): OutputStream = when (format) {
        CreateFormat.TAR -> raw
        CreateFormat.TAR_GZ -> GzipCompressorOutputStream(raw, GzipParameters().apply {
            compressionLevel = when (level) {
                CompressionLevel.STORE -> Deflater.NO_COMPRESSION
                CompressionLevel.FAST -> Deflater.BEST_SPEED
                CompressionLevel.NORMAL -> Deflater.DEFAULT_COMPRESSION
                CompressionLevel.BEST -> Deflater.BEST_COMPRESSION
            }
        })
        CreateFormat.TAR_BZ2 -> BZip2CompressorOutputStream(raw, if (level == CompressionLevel.FAST) 1 else if (level == CompressionLevel.BEST) 9 else 6)
        CreateFormat.TAR_XZ -> XZCompressorOutputStream(raw, xzPreset(level))
        else -> error("Not a tar format: $format")
    }

    private fun writeTar(entries: List<Entry>, stream: OutputStream) {
        TarArchiveOutputStream(stream).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
            for (e in entries) {
                val entry = TarArchiveEntry(e.file, if (e.isDir) "${e.name}/" else e.name)
                tar.putArchiveEntry(entry)
                if (!e.isDir) e.file.inputStream().use { it.copyTo(tar) }
                tar.closeArchiveEntry()
            }
            tar.finish()
        }
    }
}
