package ai.victorious.filemanager.core.archive

import ai.victorious.filemanager.core.search.ContentSearch
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.regex.Pattern

/**
 * What to look for inside one archive. [text] is matched against entry names when [searchNames] is
 * on, and against the text lines of entries when [searchContent] is on.
 */
data class ArchiveQuery(
    val text: String,
    val searchNames: Boolean = true,
    val searchContent: Boolean = false,
    val regex: Boolean = false,
    val caseSensitive: Boolean = false,
    /** Content search only: look inside entries whose file name matches one of these globs; empty = all. */
    val nameGlobs: List<String> = emptyList(),
    /** Entries larger than this are skipped by the content search. */
    val maxEntryBytes: Long = 4L * 1024 * 1024,
    /** Upper bound on bytes decompressed for one search, so a hostile archive cannot run away. */
    val maxTotalBytes: Long = 256L * 1024 * 1024,
    val maxResults: Int = 500,
)

/** A match inside an archive. [line] and [text] are null for a match on the entry name alone. */
data class ArchiveHit(val entry: String, val line: Int? = null, val text: String? = null)

data class ArchiveSearchResult(
    val hits: List<ArchiveHit>,
    /** Entries whose content could not be read because they are encrypted. */
    val skippedEncrypted: Int,
    /** Entries over [ArchiveQuery.maxEntryBytes]; their names were still searched. */
    val skippedTooLarge: Int,
    /** True when [ArchiveQuery.maxResults], [ArchiveQuery.maxTotalBytes] or cancellation stopped it early. */
    val truncated: Boolean,
)

/**
 * Searches inside ZIP/JAR/APK, 7z, TAR family, single compressed files and RAR by entry name and/or
 * text content, without extracting anything to disk. Nested archives are not opened.
 *
 * Content is read through a hard byte cap rather than the size the archive declares, because a
 * crafted archive can lie about it.
 */
object ArchiveSearch {
    fun search(
        archive: File,
        query: ArchiveQuery,
        sevenZPassword: CharArray? = null,
        rarPassword: String? = null,
        isCancelled: () -> Boolean = { false },
    ): ArchiveSearchResult {
        if (query.text.isEmpty() || (!query.searchNames && !query.searchContent)) {
            return ArchiveSearchResult(emptyList(), 0, 0, false)
        }
        val pattern = ContentSearch.compile(query.text, query.regex, query.caseSensitive)
        val globs = query.nameGlobs.filter { it.isNotBlank() }.map(ContentSearch::globToRegex)
        val hits = mutableListOf<ArchiveHit>()
        var encrypted = 0
        var tooLarge = 0
        var truncated = false
        var budget = query.maxTotalBytes

        Archives.visit(archive, sevenZPassword, rarPassword) { item, open ->
            if (isCancelled()) { truncated = true; return@visit false }
            if (hits.size >= query.maxResults) { truncated = true; return@visit false }

            if (query.searchNames && pattern.matcher(item.name).find()) hits += ArchiveHit(item.name)

            if (query.searchContent && !item.isDirectory && hits.size < query.maxResults) {
                val simpleName = item.name.substringAfterLast('/')
                val eligible = globs.isEmpty() || globs.any { it.matches(simpleName) }
                if (eligible) {
                    if (item.size > query.maxEntryBytes) {
                        tooLarge++
                    } else if (budget <= 0) {
                        truncated = true
                        return@visit false
                    } else {
                        val cap = minOf(query.maxEntryBytes, budget)
                        val counter = CountingLimit(cap)
                        try {
                            open().use { raw ->
                                scan(item.name, counter.wrap(raw), pattern, query.maxResults - hits.size, hits, isCancelled)
                            }
                        } catch (e: ArchivePasswordException) {
                            encrypted++
                        } catch (e: IOException) {
                            // An unreadable entry (encrypted ZIP entry, damaged stream) must not abort the search.
                            if (looksEncrypted(e)) encrypted++
                        }
                        budget -= counter.read
                        if (counter.capped) {
                            if (item.size < 0) tooLarge++
                            if (budget <= 0) { truncated = true; return@visit false }
                        }
                    }
                }
            }
            true
        }
        if (hits.size >= query.maxResults) truncated = true
        return ArchiveSearchResult(hits, encrypted, tooLarge, truncated)
    }

    private fun looksEncrypted(e: IOException): Boolean {
        val m = e.message.orEmpty().lowercase()
        return "encrypt" in m || "password" in m
    }

    private fun scan(
        entry: String,
        input: InputStream,
        pattern: Pattern,
        room: Int,
        out: MutableList<ArchiveHit>,
        isCancelled: () -> Boolean,
    ) {
        val buffered = BufferedInputStream(input)
        buffered.mark(4097)
        val head = ByteArray(4096)
        val n = buffered.read(head).coerceAtLeast(0)
        if ((0 until n).any { head[it] == 0.toByte() }) return // binary
        buffered.reset()
        var lineNo = 0
        var found = 0
        buffered.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                lineNo++
                if (isCancelled() || found >= room) break
                if (pattern.matcher(line).find()) {
                    out += ArchiveHit(entry, lineNo, line.take(300))
                    found++
                }
            }
        }
    }

    /** Counts bytes and stops reading at [limit]; [capped] tells whether the limit cut the entry short. */
    private class CountingLimit(private val limit: Long) {
        var read = 0L
            private set
        var capped = false
            private set

        fun wrap(inner: InputStream): InputStream = object : FilterInputStream(inner) {
            override fun read(): Int {
                if (read >= limit) { capped = true; return -1 }
                val b = super.read()
                if (b >= 0) read++
                return b
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (read >= limit) { capped = true; return -1 }
                val n = super.read(b, off, minOf(len.toLong(), limit - read).toInt())
                if (n > 0) read += n
                return n
            }

            // mark/reset on the buffered wrapper above, not here: keep the counter honest.
            override fun markSupported() = false
        }
    }
}
