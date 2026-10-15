package ai.victorious.filemanager.core.apk

import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** APK packaging helpers: extract, rebuild (zipalign-style), alignment checks. */
object ApkBuilder {
    /** Fixed DOS timestamp so rebuilds are reproducible (1981-01-01). */
    private const val FIXED_TIME = 347155200000L
    const val DEFAULT_ALIGN = 4
    const val NATIVE_LIB_ALIGN = 16384
    private const val ALIGN_EXTRA_ID = 0xD935

    /** Files that Android requires (or strongly prefers) to be stored uncompressed. */
    fun shouldStore(path: String): Boolean =
        path == "resources.arsc" || (path.startsWith("lib/") && path.endsWith(".so"))

    fun alignmentFor(path: String): Int = if (path.endsWith(".so")) NATIVE_LIB_ALIGN else DEFAULT_ALIGN

    /** Old signature files that must be dropped when re-signing a rebuilt APK. */
    fun isSignatureFile(path: String): Boolean {
        if (!path.startsWith("META-INF/") || path.count { it == '/' } != 1) return false
        val n = path.substringAfter('/').uppercase()
        return n == "MANIFEST.MF" || n.endsWith(".SF") || n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC")
    }

    /** Default output folder for "Extract APK": `<name>_extracted` next to the APK (unique). */
    fun extractDirFor(apk: File, parent: File = apk.absoluteFile.parentFile): File {
        val base = apk.nameWithoutExtension + "_extracted"
        var dir = File(parent, base)
        var i = 2
        while (dir.exists()) dir = File(parent, "${base}_${i++}")
        return dir
    }

    fun rebuiltNameFor(dir: File, parent: File = dir.absoluteFile.parentFile): File {
        val base = dir.name.removeSuffix("_extracted") + "_rebuilt"
        var f = File(parent, "$base.apk")
        var i = 2
        while (f.exists()) f = File(parent, "${base}_${i++}.apk")
        return f
    }

    /** Relative paths ('/'-separated) of every file under [dir], AndroidManifest.xml first, then sorted. */
    fun collect(dir: File, dropSignatures: Boolean = true): List<String> =
        dir.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(dir).invariantSeparatorsPath }
            .filterNot { dropSignatures && isSignatureFile(it) }
            .sortedWith(compareBy<String> { it != "AndroidManifest.xml" }.thenBy { it })
            .toList()

    /**
     * Zips [dir] into an APK-structured [out]: AndroidManifest.xml first, resources.arsc and
     * native libs STORED and aligned (4 / 16 KiB) via an alignment extra field, everything else
     * DEFLATED. Old v1 signature files are dropped. Returns number of entries written.
     */
    fun rebuild(dir: File, out: File, dropSignatures: Boolean = true): Int {
        require(dir.isDirectory) { "Not a folder: $dir" }
        val paths = collect(dir, dropSignatures)
        require("AndroidManifest.xml" in paths) { "Folder has no AndroidManifest.xml at its root" }
        out.absoluteFile.parentFile?.mkdirs()
        val counting = CountingStream(out.outputStream().buffered())
        ZipOutputStream(counting).use { zip ->
            for (path in paths) {
                val file = File(dir, path)
                val entry = ZipEntry(path)
                entry.time = FIXED_TIME
                if (shouldStore(path)) {
                    val bytes = file.readBytes()
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = bytes.size.toLong()
                    entry.crc = CRC32().apply { update(bytes) }.value
                    entry.extra = alignExtra(counting.count, path.toByteArray(Charsets.UTF_8).size, alignmentFor(path))
                    zip.putNextEntry(entry)
                    zip.write(bytes)
                } else {
                    entry.method = ZipEntry.DEFLATED
                    zip.putNextEntry(entry)
                    file.inputStream().use { it.copyTo(zip) }
                }
                zip.closeEntry()
            }
        }
        return paths.size
    }

    /** Builds an 0xD935 extra field so the entry data begins at a multiple of [align]. */
    fun alignExtra(headerOffset: Long, nameLen: Int, align: Int): ByteArray {
        val base = headerOffset + 30 + nameLen + 6
        val pad = ((align - base % align) % align).toInt()
        val extra = ByteBuffer.allocate(6 + pad).order(ByteOrder.LITTLE_ENDIAN)
        extra.putShort(ALIGN_EXTRA_ID.toShort())
        extra.putShort((2 + pad).toShort())
        extra.putShort(align.toShort())
        return extra.array()
    }

    data class EntryOffset(val name: String, val method: Int, val dataOffset: Long)

    /** Reads the central directory and reports where each entry's data starts. */
    fun dataOffsets(apk: File): List<EntryOffset> = RandomAccessFile(apk, "r").use { raf ->
        val len = raf.length()
        val tailLen = minOf(len, 65557L).toInt()
        val tail = ByteArray(tailLen)
        raf.seek(len - tailLen); raf.readFully(tail)
        val tb = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
        val eocd = (tailLen - 22 downTo 0).firstOrNull { tb.getInt(it) == 0x06054b50 } ?: error("No EOCD")
        val count = tb.getShort(eocd + 10).toInt() and 0xFFFF
        val cdSize = tb.getInt(eocd + 12).toLong() and 0xFFFFFFFFL
        val cdOff = tb.getInt(eocd + 16).toLong() and 0xFFFFFFFFL
        val cd = ByteArray(cdSize.toInt()); raf.seek(cdOff); raf.readFully(cd)
        val cb = ByteBuffer.wrap(cd).order(ByteOrder.LITTLE_ENDIAN)
        var p = 0
        val lh = ByteArray(30)
        (0 until count).map {
            val method = cb.getShort(p + 10).toInt() and 0xFFFF
            val n = cb.getShort(p + 28).toInt() and 0xFFFF
            val e = cb.getShort(p + 30).toInt() and 0xFFFF
            val c = cb.getShort(p + 32).toInt() and 0xFFFF
            val local = cb.getInt(p + 42).toLong() and 0xFFFFFFFFL
            val name = String(cd, p + 46, n, Charsets.UTF_8)
            raf.seek(local); raf.readFully(lh)
            val lb = ByteBuffer.wrap(lh).order(ByteOrder.LITTLE_ENDIAN)
            val dataOff = local + 30 + (lb.getShort(26).toInt() and 0xFFFF) + (lb.getShort(28).toInt() and 0xFFFF)
            p += 46 + n + e + c
            EntryOffset(name, method, dataOff)
        }
    }

    /** Names of STORED entries whose data is not aligned as Android expects (empty = OK). */
    fun misaligned(apk: File): List<String> = dataOffsets(apk)
        .filter { it.method == ZipEntry.STORED && !it.name.endsWith("/") }
        .filter { it.dataOffset % (if (it.name.endsWith(".so")) 4096 else DEFAULT_ALIGN) != 0L }
        .map { it.name }

    fun hasManifest(apk: File): Boolean = runCatching { ZipFile(apk).use { it.getEntry("AndroidManifest.xml") != null } }.getOrDefault(false)

    private class CountingStream(out: OutputStream) : FilterOutputStream(out) {
        var count = 0L
            private set
        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
    }
}
