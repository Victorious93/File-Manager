package ai.victorious.filemanager.notes

import ai.victorious.filemanager.core.notes.NoteIo
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * NoteIo backed by a persisted SAF tree URI. Paths are **relative** to the tree root
 * (e.g. `Welcome.md`, `Daily/2026-10-20.md`). Written from scratch against DocumentFile (Apache-2.0).
 */
class SafNoteIo(private val context: Context, treeUri: Uri) : NoteIo {
    private val root: DocumentFile = DocumentFile.fromTreeUri(context, treeUri)
        ?: error("Cannot open SAF tree $treeUri")

    override fun listMarkdown(rootPath: String): List<String> {
        val out = mutableListOf<String>()
        fun walk(dir: DocumentFile, rel: String) {
            for (c in dir.listFiles()) {
                val name = c.name ?: continue
                val childRel = if (rel.isEmpty()) name else "$rel/$name"
                when {
                    c.isDirectory -> walk(c, childRel)
                    c.isFile && name.endsWith(".md", true) -> out += childRel
                }
            }
        }
        walk(root, "")
        return out.sorted()
    }

    override fun read(path: String): String {
        val doc = find(path) ?: error("Missing $path")
        return context.contentResolver.openInputStream(doc.uri)?.bufferedReader()?.use { it.readText() }
            ?: error("Cannot read $path")
    }

    override fun write(path: String, text: String) {
        val parentRel = path.substringBeforeLast('/', missingDelimiterValue = "")
        val name = path.substringAfterLast('/')
        val parent = if (parentRel.isEmpty()) root else ensureDir(parentRel)
        val existing = parent.findFile(name)
        val doc = existing ?: parent.createFile("text/markdown", name.removeSuffix(".md"))
            ?: parent.createFile("text/plain", name)
            ?: error("Cannot create $path")
        // Recreate if findFile returned a directory somehow
        context.contentResolver.openOutputStream(doc.uri, "wt")?.bufferedWriter()?.use { it.write(text) }
            ?: error("Cannot write $path")
    }

    override fun exists(path: String): Boolean = find(path)?.exists() == true

    override fun mkdir(path: String) { ensureDir(path) }

    /** Same-folder rename only (SAF has no cheap cross-folder move before API 24 providers). */
    override fun rename(from: String, to: String): Boolean {
        if (from.substringBeforeLast('/', "") != to.substringBeforeLast('/', "")) return false
        val doc = find(from) ?: return false
        return doc.renameTo(to.substringAfterLast('/'))
    }

    private fun ensureDir(rel: String): DocumentFile {
        var cur = root
        if (rel.isBlank()) return cur
        for (seg in rel.split('/').filter { it.isNotEmpty() }) {
            cur = cur.findFile(seg)?.takeIf { it.isDirectory }
                ?: cur.createDirectory(seg)
                ?: error("Cannot mkdir $rel")
        }
        return cur
    }

    private fun find(rel: String): DocumentFile? {
        var cur: DocumentFile = root
        val parts = rel.split('/').filter { it.isNotEmpty() }
        for ((i, seg) in parts.withIndex()) {
            val next = cur.findFile(seg) ?: return null
            if (i == parts.lastIndex) return next
            if (!next.isDirectory) return null
            cur = next
        }
        return cur
    }
}

/** Best-effort map of a primary-storage tree URI to a filesystem path. */
object SafTreePaths {
    fun primaryPath(treeUri: Uri): String? {
        val id = runCatching { android.provider.DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
        if (!id.startsWith("primary:", ignoreCase = true)) return null
        val rel = id.removePrefix("primary:").removePrefix("PRIMARY:")
        return if (rel.isEmpty()) "/storage/emulated/0" else "/storage/emulated/0/$rel"
    }
}
