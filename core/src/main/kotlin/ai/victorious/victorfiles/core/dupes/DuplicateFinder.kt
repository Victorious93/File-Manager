package ai.victorious.victorfiles.core.dupes

import java.io.File
import java.security.MessageDigest

data class DuplicateGroup(val size: Long, val hash: String, val files: List<File>) {
    /** Bytes reclaimable if all but one copy are removed. */
    val wasted: Long get() = size * (files.size - 1)
}

/** Finds duplicate files: group by size, then by a quick partial hash, then by full SHA-256. */
object DuplicateFinder {
    private const val PARTIAL = 64L * 1024

    fun scan(root: File, minSize: Long = 1, isCancelled: () -> Boolean = { false }): List<DuplicateGroup> {
        val files = root.walkTopDown()
            .onEnter { !isCancelled() && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
            .filter { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
            .toList()
        return group(files, minSize, isCancelled)
    }

    fun group(files: List<File>, minSize: Long = 1, isCancelled: () -> Boolean = { false }): List<DuplicateGroup> {
        val out = ArrayList<DuplicateGroup>()
        val bySize = files.filter { it.length() >= minSize }.groupBy { it.length() }.filterValues { it.size > 1 }
        for ((size, sameSize) in bySize) {
            if (isCancelled()) break
            val partial = sameSize.groupBy { runCatching { hash(it, PARTIAL) }.getOrNull() }.filterKeys { it != null }.filterValues { it.size > 1 }
            for ((_, cands) in partial) {
                val full = if (size <= PARTIAL) cands.groupBy { hash(it, PARTIAL) }
                else cands.groupBy { runCatching { hash(it, Long.MAX_VALUE) }.getOrNull() }.filterKeys { it != null }
                for ((h, same) in full) if (same.size > 1) out += DuplicateGroup(size, h!!, same.sortedBy { it.path })
            }
        }
        return out.sortedByDescending { it.wasted }
    }

    /** Default selection: keep the first (shortest path, then alphabetical) and mark the rest. */
    fun defaultSelection(groups: List<DuplicateGroup>): Set<File> =
        groups.flatMap { g -> g.files.sortedWith(compareBy({ it.path.length }, { it.path })).drop(1) }.toSet()

    fun hash(file: File, limit: Long): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            var remaining = limit
            while (remaining > 0) {
                val r = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (r < 0) break
                md.update(buf, 0, r)
                remaining -= r
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
