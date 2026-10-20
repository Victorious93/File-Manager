package ai.victorious.filemanager.core.notes

/**
 * Backlink / outgoing-link index over a vault of markdown notes.
 * Keyed by note path. Rebuild is O(notes × links) and fine for typical vaults.
 */
data class LinkEdge(val fromPath: String, val toTarget: String, val toPath: String?, val raw: String)

class BacklinkIndex {
    private val outgoing = linkedMapOf<String, List<LinkEdge>>() // path -> edges
    private val titles = linkedMapOf<String, String>() // title -> path

    fun noteCount(): Int = outgoing.size

    fun setNote(path: String, body: String) {
        if (!NotePaths.isNote(path)) return
        titles[NotePaths.titleOf(path)] = path
        outgoing[path] = WikiLinkParser.links(body).map { link ->
            LinkEdge(path, link.target, null, link.raw) // toPath filled in reindex
        }
        rebind()
    }

    fun removeNote(path: String) {
        titles.entries.removeIf { it.value == path }
        outgoing.remove(path)
        rebind()
    }

    fun clear() { outgoing.clear(); titles.clear() }

    /** Re-resolve every edge's toPath against current titles. */
    private fun rebind() {
        val titleIndex = titles.toMap()
        for ((path, edges) in outgoing.toList()) {
            outgoing[path] = edges.map { e ->
                val resolved = titleIndex[e.toTarget]
                    ?: titleIndex.entries.firstOrNull { it.key.equals(e.toTarget, ignoreCase = true) }?.value
                e.copy(toPath = resolved)
            }
        }
    }

    fun outgoing(path: String): List<LinkEdge> = outgoing[path].orEmpty()

    fun backlinks(path: String): List<LinkEdge> =
        outgoing.values.flatten().filter { it.toPath == path }

    fun backlinksByTitle(title: String): List<LinkEdge> {
        val path = titles[title] ?: titles.entries.firstOrNull { it.key.equals(title, ignoreCase = true) }?.value
        return path?.let { backlinks(it) }.orEmpty()
    }

    fun orphanNotes(): List<String> =
        outgoing.keys.filter { path ->
            outgoing(path).isEmpty() && backlinks(path).isEmpty()
        }

    fun unresolved(): List<LinkEdge> = outgoing.values.flatten().filter { it.toPath == null }

    /** Autocomplete candidates for a partial `[[query`. */
    fun suggest(query: String, limit: Int = 20): List<String> {
        val q = query.trim()
        if (q.isEmpty()) return titles.keys.sorted().take(limit)
        val starts = titles.keys.filter { it.startsWith(q, ignoreCase = true) }.sorted()
        val contains = titles.keys.filter { it.contains(q, ignoreCase = true) && it !in starts }.sorted()
        return (starts + contains).take(limit)
    }

    fun allTitles(): List<String> = titles.keys.sorted()
    fun pathForTitle(title: String): String? = titles[title] ?: titles.entries.firstOrNull { it.key.equals(title, ignoreCase = true) }?.value
}

/** Graph for Compose Canvas: nodes = notes, edges = resolved wikilinks. */
data class GraphNode(val path: String, val title: String, val tagCount: Int = 0)
data class GraphEdge(val from: String, val to: String)

object NoteGraph {
    fun build(index: BacklinkIndex, tagCounts: Map<String, Int> = emptyMap()): Pair<List<GraphNode>, List<GraphEdge>> {
        val nodes = index.allTitles().mapNotNull { t ->
            val p = index.pathForTitle(t) ?: return@mapNotNull null
            GraphNode(p, t, tagCounts[p] ?: 0)
        }
        val edges = linkedSetOf<GraphEdge>()
        for (n in nodes) {
            for (e in index.outgoing(n.path)) {
                val to = e.toPath ?: continue
                edges += GraphEdge(n.path, to)
            }
        }
        return nodes to edges.toList()
    }
}
