package ai.victorious.filemanager.core.search

import java.io.File
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

data class ContentHit(val path: String, val line: Int, val text: String)

data class ContentQuery(
    val text: String,
    val regex: Boolean = false,
    val caseSensitive: Boolean = false,
    /** Only search files whose name matches one of these globs (`*.smali`, `*.xml`); empty = all. */
    val nameGlobs: List<String> = emptyList(),
    val maxFileBytes: Long = 4L * 1024 * 1024,
    val maxResults: Int = 500,
)

/** grep-style search through the text content of local files (MT Manager's "search in files"). */
object ContentSearch {
    class InvalidQuery(message: String) : IllegalArgumentException(message)

    fun globToRegex(glob: String): Regex {
        val sb = StringBuilder()
        for (c in glob) when (c) {
            '*' -> sb.append(".*")
            '?' -> sb.append('.')
            else -> sb.append(Pattern.quote(c.toString()))
        }
        return Regex(sb.toString(), RegexOption.IGNORE_CASE)
    }

    fun search(root: File, query: ContentQuery, isCancelled: () -> Boolean = { false }): List<ContentHit> {
        if (query.text.isEmpty()) return emptyList()
        val pattern = try {
            var flags = 0
            if (!query.caseSensitive) flags = flags or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
            if (query.regex) Pattern.compile(query.text, flags) else Pattern.compile(Pattern.quote(query.text), flags)
        } catch (e: PatternSyntaxException) {
            throw InvalidQuery("Invalid pattern: ${e.description}")
        }
        val globs = query.nameGlobs.filter { it.isNotBlank() }.map(::globToRegex)
        val hits = mutableListOf<ContentHit>()
        val stack = ArrayDeque<File>()
        if (root.isFile) stack.addLast(root) else stack.addLast(root)
        while (stack.isNotEmpty() && hits.size < query.maxResults && !isCancelled()) {
            val cur = stack.removeLast()
            if (cur.isDirectory) {
                if (java.nio.file.Files.isSymbolicLink(cur.toPath())) continue
                cur.listFiles()?.sortedByDescending { it.name }?.forEach { stack.addLast(it) }
                continue
            }
            if (!cur.isFile || cur.length() > query.maxFileBytes) continue
            if (globs.isNotEmpty() && globs.none { it.matches(cur.name) }) continue
            searchFile(cur, pattern, query.maxResults - hits.size, hits, isCancelled)
        }
        return hits
    }

    private fun searchFile(file: File, pattern: Pattern, room: Int, out: MutableList<ContentHit>, isCancelled: () -> Boolean) {
        runCatching {
            file.inputStream().buffered().use { ins ->
                val head = ByteArray(minOf(4096, file.length().toInt()))
                ins.mark(head.size + 1)
                val n = ins.read(head)
                if (n > 0 && head.take(n).any { it == 0.toByte() }) return // binary
                ins.reset()
                var lineNo = 0
                var found = 0
                ins.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                    lineNo++
                    if (found < room && !isCancelled() && pattern.matcher(line).find()) {
                        out += ContentHit(file.path, lineNo, line.take(300))
                        found++
                    }
                }
            }
        }
    }
}
