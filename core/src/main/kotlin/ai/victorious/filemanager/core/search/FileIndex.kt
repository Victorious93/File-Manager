package ai.victorious.filemanager.core.search

import ai.victorious.filemanager.core.fs.FileEntry
import ai.victorious.filemanager.core.fs.FileIo
import ai.victorious.filemanager.core.path.PathHelpers

/**
 * In-memory name index for fast search. One walk builds flat arrays (lower-cased names +
 * entries); each query is then a linear scan over strings: tens of ms for 100k+ files
 * instead of a full filesystem walk per keystroke.
 *
 * Query syntax: space-separated terms must all appear in the name (case-insensitive);
 * `*.ext` or `.ext` restricts to that extension; `/` prefix matches against the relative path.
 */
class FileIndex(
    private val io: FileIo,
    private val skipDirNames: Set<String> = setOf(".victorious-trash", ".git"),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    class Snapshot(
        val root: String,
        val lowerNames: Array<String>,
        val lowerRel: Array<String>,
        val entries: Array<FileEntry>,
        val builtAtMs: Long,
        val buildMs: Long,
    ) { val size: Int get() = entries.size }

    @Volatile private var snapshot: Snapshot? = null
    @Volatile private var stale = false

    fun current(): Snapshot? = snapshot

    /** True when there is no index for [root], it was invalidated, or it is older than [maxAgeMs]. */
    fun needsBuild(root: String, maxAgeMs: Long = 10 * 60_000L): Boolean {
        val s = snapshot ?: return true
        return stale || s.root != PathHelpers.normalize(root) || clock() - s.builtAtMs > maxAgeMs
    }

    /** Marks the index stale after file operations; the next search rebuilds it. */
    fun invalidate() { stale = true }

    fun build(root: String, isCancelled: () -> Boolean = { false }, maxEntries: Int = 500_000): Snapshot {
        val start = clock()
        val normRoot = PathHelpers.normalize(root)
        val names = ArrayList<String>(); val rels = ArrayList<String>(); val entries = ArrayList<FileEntry>()
        val stack = ArrayDeque<String>().apply { add(normRoot) }
        while (stack.isNotEmpty() && entries.size < maxEntries) {
            if (isCancelled()) throw InterruptedException("index build cancelled")
            val dir = stack.removeLast()
            val children = runCatching { io.list(dir) }.getOrDefault(emptyList())
            for (c in children) {
                if (c.isDirectory && c.name in skipDirNames) continue
                names += c.name.lowercase()
                rels += PathHelpers.relativeTo(c.path, normRoot).lowercase()
                entries += c
                if (c.isDirectory) stack.addLast(c.path)
            }
        }
        val s = Snapshot(normRoot, names.toTypedArray(), rels.toTypedArray(), entries.toTypedArray(), clock(), clock() - start)
        snapshot = s
        stale = false
        return s
    }

    fun search(query: String, maxResults: Int = 500): List<SearchHit> {
        val s = snapshot ?: return emptyList()
        val q = parse(query) ?: return emptyList()
        val out = ArrayList<SearchHit>()
        for (i in s.entries.indices) {
            if (matches(q, s.lowerNames[i], s.lowerRel[i])) {
                out += SearchHit(s.entries[i], PathHelpers.relativeTo(s.entries[i].path, s.root))
                if (out.size >= maxResults) break
            }
        }
        return out
    }

    class Query(val terms: List<String>, val ext: String?, val pathTerms: List<String>)

    companion object {
        fun parse(query: String): Query? {
            val parts = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (parts.isEmpty()) return null
            var ext: String? = null
            val terms = ArrayList<String>(); val pathTerms = ArrayList<String>()
            for (p in parts) when {
                p.startsWith("*.") && p.length > 2 -> ext = p.substring(2)
                p.startsWith(".") && p.length > 1 && !p.substring(1).contains('.') && parts.size > 1 -> ext = p.substring(1)
                p.startsWith("/") && p.length > 1 -> pathTerms += p.substring(1)
                else -> terms += p
            }
            return Query(terms, ext, pathTerms)
        }

        fun matches(q: Query, lowerName: String, lowerRel: String): Boolean {
            if (q.ext != null && !lowerName.endsWith("." + q.ext)) return false
            for (t in q.terms) if (!lowerName.contains(t)) return false
            for (t in q.pathTerms) if (!lowerRel.contains(t)) return false
            return q.terms.isNotEmpty() || q.ext != null || q.pathTerms.isNotEmpty()
        }
    }
}
