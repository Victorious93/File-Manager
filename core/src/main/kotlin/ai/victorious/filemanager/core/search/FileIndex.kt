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
 *
 * Filters (combine freely with terms): `type:image|video|audio|doc|archive|apk|folder`,
 * `size>10mb` / `size<500k` (b, k, m, g; files only), `newer:7d` / `older:1y` (m, h, d, w, y).
 * A filter that cannot be parsed (e.g. `size>abc`) is treated as an ordinary search term.
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
        val now = clock()
        for (i in s.entries.indices) {
            if (matches(q, s.lowerNames[i], s.lowerRel[i], s.entries[i], now)) {
                out += SearchHit(s.entries[i], PathHelpers.relativeTo(s.entries[i].path, s.root))
                if (out.size >= maxResults) break
            }
        }
        // A "newer:" filter is a recency question: show the freshest files first.
        return if (q.newerThanMs != null) out.sortedByDescending { it.entry.modifiedEpochMs } else out
    }

    /** Files modified at or after [sinceMs], newest first (the "Recent" view). */
    fun recent(sinceMs: Long = 0L, limit: Int = 200): List<SearchHit> {
        val s = snapshot ?: return emptyList()
        return s.entries.asSequence()
            .filter { !it.isDirectory && it.modifiedEpochMs >= sinceMs }
            .sortedByDescending { it.modifiedEpochMs }
            .take(limit)
            .map { SearchHit(it, PathHelpers.relativeTo(it.path, s.root)) }
            .toList()
    }

    class Query(
        val terms: List<String>,
        val ext: String?,
        val pathTerms: List<String>,
        val category: FileCategory? = null,
        val foldersOnly: Boolean = false,
        val minSize: Long? = null,
        val maxSize: Long? = null,
        /** Modified no longer ago than this (`newer:7d`). */
        val newerThanMs: Long? = null,
        /** Modified at least this long ago (`older:1y`). */
        val olderThanMs: Long? = null,
    ) {
        val hasEntryFilters: Boolean
            get() = category != null || foldersOnly || minSize != null || maxSize != null || newerThanMs != null || olderThanMs != null
    }

    companion object {
        fun parse(query: String): Query? {
            val parts = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (parts.isEmpty()) return null
            var ext: String? = null
            val terms = ArrayList<String>(); val pathTerms = ArrayList<String>()
            var category: FileCategory? = null; var foldersOnly = false
            var minSize: Long? = null; var maxSize: Long? = null
            var newer: Long? = null; var older: Long? = null
            for (p in parts) when {
                p.startsWith("type:") && p.length > 5 && (p.substring(5) == "folder" || p.substring(5) == "dir" || FileCategory.fromToken(p.substring(5)) != null) -> {
                    val t = p.substring(5)
                    if (t == "folder" || t == "dir") foldersOnly = true else category = FileCategory.fromToken(t)
                }
                p.startsWith("size>") && parseSize(p.substring(5)) != null -> minSize = parseSize(p.substring(5))
                p.startsWith("size<") && parseSize(p.substring(5)) != null -> maxSize = parseSize(p.substring(5))
                p.startsWith("newer:") && parseAge(p.substring(6)) != null -> newer = parseAge(p.substring(6))
                p.startsWith("older:") && parseAge(p.substring(6)) != null -> older = parseAge(p.substring(6))
                p.startsWith("*.") && p.length > 2 -> ext = p.substring(2)
                p.startsWith(".") && p.length > 1 && !p.substring(1).contains('.') && parts.size > 1 -> ext = p.substring(1)
                p.startsWith("/") && p.length > 1 -> pathTerms += p.substring(1)
                else -> terms += p
            }
            return Query(terms, ext, pathTerms, category, foldersOnly, minSize, maxSize, newer, older)
        }

        /** `10mb`, `500k`, `2g`, `100` (bytes); case-insensitive. Null when malformed. */
        fun parseSize(text: String): Long? {
            val m = Regex("^(\\d{1,12})(b|k|kb|m|mb|g|gb)?$").matchEntire(text.lowercase()) ?: return null
            val n = m.groupValues[1].toLong()
            val mult = when (m.groupValues[2]) {
                "k", "kb" -> 1L shl 10
                "m", "mb" -> 1L shl 20
                "g", "gb" -> 1L shl 30
                else -> 1L
            }
            return n * mult
        }

        /** `90m`, `12h`, `7d`, `2w`, `1y` as milliseconds. Null when malformed. */
        fun parseAge(text: String): Long? {
            val m = Regex("^(\\d{1,6})(m|h|d|w|y)$").matchEntire(text.lowercase()) ?: return null
            val n = m.groupValues[1].toLong()
            val unit = when (m.groupValues[2]) {
                "m" -> 60_000L
                "h" -> 3_600_000L
                "d" -> 86_400_000L
                "w" -> 7 * 86_400_000L
                else -> 365 * 86_400_000L
            }
            return n * unit
        }

        fun matches(q: Query, lowerName: String, lowerRel: String): Boolean {
            if (q.hasEntryFilters) return false // needs the entry; use the overload below
            return matchesText(q, lowerName, lowerRel)
        }

        fun matches(q: Query, lowerName: String, lowerRel: String, entry: FileEntry, nowMs: Long): Boolean {
            // `type:folder` lists folders only; any other filter (size, date, type) is about files.
            if (q.foldersOnly != entry.isDirectory) return false
            if (q.category != null && !q.category.matches(lowerName)) return false
            if (q.minSize != null && entry.sizeBytes < q.minSize) return false
            if (q.maxSize != null && entry.sizeBytes > q.maxSize) return false
            if (q.newerThanMs != null && nowMs - entry.modifiedEpochMs > q.newerThanMs) return false
            if (q.olderThanMs != null && nowMs - entry.modifiedEpochMs < q.olderThanMs) return false
            return matchesText(q, lowerName, lowerRel, allowFilterOnly = q.hasEntryFilters)
        }

        private fun matchesText(q: Query, lowerName: String, lowerRel: String, allowFilterOnly: Boolean = false): Boolean {
            if (q.ext != null && !lowerName.endsWith("." + q.ext)) return false
            for (t in q.terms) if (!lowerName.contains(t)) return false
            for (t in q.pathTerms) if (!lowerRel.contains(t)) return false
            return allowFilterOnly || q.terms.isNotEmpty() || q.ext != null || q.pathTerms.isNotEmpty()
        }
    }
}
