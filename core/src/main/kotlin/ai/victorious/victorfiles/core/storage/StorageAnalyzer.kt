package ai.victorious.victorfiles.core.storage

import java.io.File
import java.nio.file.Files

data class SizeEntry(val file: File, val isDirectory: Boolean, val bytes: Long, val fileCount: Int)

data class FolderBreakdown(val dir: File, val total: Long, val entries: List<SizeEntry>) {
    fun fraction(e: SizeEntry): Float = if (total <= 0) 0f else e.bytes.toFloat() / total
}

/** Computes recursive folder sizes; children sorted largest first. Symlinks are not followed. */
object StorageAnalyzer {
    fun analyze(dir: File, isCancelled: () -> Boolean = { false }): FolderBreakdown {
        val children = dir.listFiles()?.toList().orEmpty()
        val entries = children.map { c ->
            if (c.isDirectory && !Files.isSymbolicLink(c.toPath())) {
                val (b, n) = sizeOf(c, isCancelled)
                SizeEntry(c, true, b, n)
            } else SizeEntry(c, false, if (Files.isSymbolicLink(c.toPath())) 0 else c.length(), 1)
        }
        return aggregate(dir, entries)
    }

    fun aggregate(dir: File, entries: List<SizeEntry>): FolderBreakdown =
        FolderBreakdown(dir, entries.sumOf { it.bytes }, entries.sortedWith(compareByDescending<SizeEntry> { it.bytes }.thenBy { it.file.name }))

    fun sizeOf(dir: File, isCancelled: () -> Boolean = { false }): Pair<Long, Int> {
        var bytes = 0L
        var count = 0
        dir.walkTopDown()
            .onEnter { !isCancelled() && (it == dir || !Files.isSymbolicLink(it.toPath())) }
            .forEach { f -> if (f.isFile && !Files.isSymbolicLink(f.toPath())) { bytes += f.length(); count++ } }
        return bytes to count
    }

    fun human(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = bytes / 1024.0
        var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return String.format(java.util.Locale.US, "%.1f %s", v, units[i])
    }
}
