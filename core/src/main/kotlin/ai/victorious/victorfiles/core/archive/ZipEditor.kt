package ai.victorious.victorfiles.core.archive

import ai.victorious.victorfiles.core.apk.ApkBuilder
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

sealed interface ZipEdit {
    /** Replaces the content of an existing entry, or adds it when absent. */
    data class Put(val name: String, val bytes: ByteArray) : ZipEdit
    data class Delete(val name: String) : ZipEdit
    data class Rename(val from: String, val to: String) : ZipEdit
}

/**
 * Edits entries of a ZIP/JAR/APK without extracting it (MT Manager-style): rewrites the archive to a
 * temp file and swaps it in only on success, so a failure never corrupts the original.
 * Entries that were STORED, plus `resources.arsc` and native libs, stay STORED and aligned.
 * Editing invalidates any APK signature; v1 signature files are dropped by default so the result
 * is plainly unsigned rather than subtly broken. Sign it again afterwards.
 */
object ZipEditor {
    fun apply(zip: File, edits: List<ZipEdit>, dropSignatures: Boolean = true): Int {
        require(zip.isFile) { "Not a file: ${zip.path}" }
        require(edits.isNotEmpty()) { "Nothing to change" }
        val tmp = File(zip.absoluteFile.parentFile, ".${zip.name}.edit.tmp")
        try {
            val written = ZipFile(zip).use { src -> write(src, tmp, edits, dropSignatures) }
            Files.move(tmp.toPath(), zip.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return written
        } finally {
            tmp.delete()
        }
    }

    private fun write(src: ZipFile, out: File, edits: List<ZipEdit>, dropSignatures: Boolean): Int {
        val names = src.entries().asSequence().map { it.name }.toSet()
        val deletes = edits.filterIsInstance<ZipEdit.Delete>().map { it.name }.toSet()
        val renames = edits.filterIsInstance<ZipEdit.Rename>().associate { it.from to it.to }
        val puts = edits.filterIsInstance<ZipEdit.Put>().associate { it.name to it.bytes }
        for (d in deletes) require(d in names) { "No such entry: $d" }
        for ((from, to) in renames) {
            require(from in names) { "No such entry: $from" }
            require(to.isNotBlank() && !to.startsWith("/") && ".." !in to.split('/')) { "Invalid entry name: $to" }
            require(to !in names || to in deletes || to in renames.keys) { "Entry already exists: $to" }
        }
        for (n in puts.keys) require(n.isNotBlank() && !n.startsWith("/") && ".." !in n.split('/')) { "Invalid entry name: $n" }

        val finalNames = mutableSetOf<String>()
        var count = 0
        val counting = CountingStream(out.outputStream().buffered())
        ZipOutputStream(counting).use { zo ->
            fun emit(name: String, time: Long, stored: Boolean, bytes: () -> ByteArray, copy: ((OutputStream) -> Unit)?) {
                require(finalNames.add(name)) { "Duplicate entry: $name" }
                val e = ZipEntry(name)
                if (time >= 0) e.time = time
                if (stored || name.endsWith("/")) {
                    val data = bytes()
                    e.method = ZipEntry.STORED
                    e.size = data.size.toLong()
                    e.compressedSize = data.size.toLong()
                    e.crc = CRC32().apply { update(data) }.value
                    if (!name.endsWith("/")) {
                        e.extra = ApkBuilder.alignExtra(counting.count, name.toByteArray(Charsets.UTF_8).size, ApkBuilder.alignmentFor(name))
                    }
                    zo.putNextEntry(e)
                    zo.write(data)
                } else {
                    e.method = ZipEntry.DEFLATED
                    zo.putNextEntry(e)
                    if (copy != null) copy(zo) else zo.write(bytes())
                }
                zo.closeEntry()
                count++
            }

            for (entry in src.entries().asSequence()) {
                val old = entry.name
                if (old in deletes) continue
                if (dropSignatures && ApkBuilder.isSignatureFile(old)) continue
                val name = renames[old] ?: old
                val stored = entry.method == ZipEntry.STORED || ApkBuilder.shouldStore(name)
                val replacement = puts[old]
                if (replacement != null) {
                    emit(name, entry.time, stored, { replacement }, null)
                } else if (stored && !entry.isDirectory) {
                    emit(name, entry.time, true, { src.getInputStream(entry).use { it.readBytes() } }, null)
                } else {
                    emit(name, entry.time, entry.isDirectory, { ByteArray(0) }) { o -> src.getInputStream(entry).use { it.copyTo(o) } }
                }
            }
            for ((name, bytes) in puts) {
                if (name in names) continue
                emit(name, System.currentTimeMillis(), ApkBuilder.shouldStore(name), { bytes }, null)
            }
        }
        return count
    }

    private class CountingStream(out: OutputStream) : FilterOutputStream(out) {
        var count = 0L
            private set
        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
    }
}
