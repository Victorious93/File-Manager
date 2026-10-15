package ai.victorious.filemanager.core.search

import ai.victorious.filemanager.core.fs.FileEntry
import ai.victorious.filemanager.core.fs.FileIo
import ai.victorious.filemanager.core.path.PathHelpers

data class SearchHit(
    val entry: FileEntry,
    val relativePath: String,
)

class FileSearch(
    private val io: FileIo,
    private val skipDirNames: Set<String> = setOf(".victorious-trash", ".git"),
) {
    fun search(root: String, query: String, maxResults: Int = 200): List<SearchHit> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        val normalizedRoot = PathHelpers.normalize(root)
        if (!io.isDirectory(normalizedRoot)) return emptyList()
        val hits = mutableListOf<SearchHit>()
        walk(normalizedRoot, normalizedRoot, needle.lowercase(), maxResults, hits)
        return hits
    }

    private fun walk(
        root: String,
        dir: String,
        needle: String,
        maxResults: Int,
        out: MutableList<SearchHit>,
    ) {
        if (out.size >= maxResults) return
        val children = runCatching { io.list(dir) }.getOrDefault(emptyList())
        for (child in children) {
            if (out.size >= maxResults) return
            if (child.isDirectory && child.name in skipDirNames) continue
            if (child.name.lowercase().contains(needle)) {
                out += SearchHit(
                    entry = child,
                    relativePath = PathHelpers.relativeTo(child.path, root),
                )
            }
            if (child.isDirectory) {
                walk(root, child.path, needle, maxResults, out)
            }
        }
    }
}
