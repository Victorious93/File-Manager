package ai.victorious.filemanager.core.diff

import java.io.File
import java.security.MessageDigest

enum class EntryStatus { SAME, DIFFERENT, ONLY_LEFT, ONLY_RIGHT, TYPE_MISMATCH }

/** Which side has the newer modification time when the files differ (null when equal / not applicable). */
enum class NewerSide { LEFT, RIGHT }

/**
 * One relative path in a folder comparison. [relPath] uses `/` and never starts with one.
 * Folders that exist on both sides are not listed themselves; only their contents are.
 */
data class FolderDiffEntry(
    val relPath: String,
    val status: EntryStatus,
    val isDirectory: Boolean,
    val leftSize: Long?,
    val rightSize: Long?,
    val newer: NewerSide? = null,
)

data class FolderDiffSummary(val same: Int, val different: Int, val onlyLeft: Int, val onlyRight: Int, val typeMismatch: Int) {
    val identical: Boolean get() = different == 0 && onlyLeft == 0 && onlyRight == 0 && typeMismatch == 0
}

data class FolderDiff(val left: File, val right: File, val entries: List<FolderDiffEntry>) {
    val summary: FolderDiffSummary
        get() = FolderDiffSummary(
            entries.count { it.status == EntryStatus.SAME },
            entries.count { it.status == EntryStatus.DIFFERENT },
            entries.count { it.status == EntryStatus.ONLY_LEFT },
            entries.count { it.status == EntryStatus.ONLY_RIGHT },
            entries.count { it.status == EntryStatus.TYPE_MISMATCH },
        )
}

/**
 * Recursive two-folder compare ("which files differ between these folders?").
 *
 * Content check: sizes first; equal sizes are confirmed byte-for-byte via SHA-256 when [deep] is
 * true, otherwise by size + modification time (fast mode, may miss same-size edits). A folder that
 * exists on one side only is reported once (its contents are not expanded) to keep lists short.
 * Hidden files are included. Symlinked folders are not descended twice.
 */
object FolderCompare {
    fun compare(left: File, right: File, deep: Boolean = true, ignore: (String) -> Boolean = { false }): FolderDiff {
        require(left.isDirectory) { "Not a folder: ${left.name}" }
        require(right.isDirectory) { "Not a folder: ${right.name}" }
        require(left.canonicalFile != right.canonicalFile) { "Pick two different folders" }
        val out = ArrayList<FolderDiffEntry>()
        val visited = HashSet<Pair<String, String>>()
        fun walk(l: File, r: File, prefix: String) {
            if (!visited.add(l.canonicalPath to r.canonicalPath)) return
            val ln = l.listFiles().orEmpty().associateBy { it.name }
            val rn = r.listFiles().orEmpty().associateBy { it.name }
            for (name in (ln.keys + rn.keys).sorted()) {
                val rel = if (prefix.isEmpty()) name else "$prefix/$name"
                if (ignore(rel)) continue
                val a = ln[name]
                val b = rn[name]
                when {
                    a == null -> out += FolderDiffEntry(rel, EntryStatus.ONLY_RIGHT, b!!.isDirectory, null, sizeOf(b))
                    b == null -> out += FolderDiffEntry(rel, EntryStatus.ONLY_LEFT, a.isDirectory, sizeOf(a), null)
                    a.isDirectory && b.isDirectory -> walk(a, b, rel)
                    a.isDirectory != b.isDirectory ->
                        out += FolderDiffEntry(rel, EntryStatus.TYPE_MISMATCH, false, sizeOf(a), sizeOf(b))
                    else -> {
                        val same = sameContent(a, b, deep)
                        val newer = if (same) null else when {
                            a.lastModified() > b.lastModified() -> NewerSide.LEFT
                            b.lastModified() > a.lastModified() -> NewerSide.RIGHT
                            else -> null
                        }
                        out += FolderDiffEntry(rel, if (same) EntryStatus.SAME else EntryStatus.DIFFERENT, false, a.length(), b.length(), newer)
                    }
                }
            }
        }
        walk(left, right, "")
        return FolderDiff(left, right, out)
    }

    fun sameContent(a: File, b: File, deep: Boolean): Boolean {
        if (a.length() != b.length()) return false
        if (!deep) return a.lastModified() / 1000 == b.lastModified() / 1000
        return sha256(a).contentEquals(sha256(b))
    }

    private fun sha256(f: File): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest()
    }

    /** Total bytes of a file, or of everything under a folder. */
    private fun sizeOf(f: File): Long = if (f.isDirectory) f.walkTopDown().filter { it.isFile }.sumOf { it.length() } else f.length()

    /**
     * Copies entries that exist on one side only to the other side ([toRight] = left → right).
     * Never overwrites: an existing target is skipped and counted. Returns copied/skipped counts.
     */
    fun copyMissing(diff: FolderDiff, toRight: Boolean, only: Set<String>? = null): Pair<Int, Int> {
        val want = if (toRight) EntryStatus.ONLY_LEFT else EntryStatus.ONLY_RIGHT
        val src = if (toRight) diff.left else diff.right
        val dst = if (toRight) diff.right else diff.left
        var copied = 0
        var skipped = 0
        for (e in diff.entries) {
            if (e.status != want || (only != null && e.relPath !in only)) continue
            val from = File(src, e.relPath)
            val to = File(dst, e.relPath)
            if (!to.canonicalPath.startsWith(dst.canonicalPath + File.separator)) { skipped++; continue }
            if (to.exists()) { skipped++; continue }
            if (from.isDirectory) from.copyRecursively(to, overwrite = false) else { to.parentFile?.mkdirs(); from.copyTo(to, overwrite = false) }
            to.setLastModified(from.lastModified())
            copied++
        }
        return copied to skipped
    }
}
