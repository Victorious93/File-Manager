package ai.victorious.victorfiles.core.syntax

data class FindOptions(val regex: Boolean = false, val ignoreCase: Boolean = false, val wholeWord: Boolean = false)

data class ReplaceResult(val text: String, val count: Int)

/** Find & replace engine used by the code editor. Regex mode supports $1-style group references. */
object FindReplace {
    fun compile(query: String, o: FindOptions): Regex {
        var p = if (o.regex) query else Regex.escape(query)
        if (o.wholeWord) p = "\\b(?:$p)\\b"
        val opts = buildSet { if (o.ignoreCase) { add(RegexOption.IGNORE_CASE) }; add(RegexOption.MULTILINE) }
        return Regex(p, opts)
    }

    /** Returns match ranges, or throws IllegalArgumentException for an invalid pattern. */
    fun findAll(text: String, query: String, o: FindOptions = FindOptions()): List<IntRange> {
        if (query.isEmpty()) return emptyList()
        return compile(query, o).findAll(text).filter { it.range.first <= it.range.last }.map { it.range }.toList()
    }

    fun count(text: String, query: String, o: FindOptions = FindOptions()): Int = findAll(text, query, o).size

    fun replaceAll(text: String, query: String, replacement: String, o: FindOptions = FindOptions()): ReplaceResult {
        if (query.isEmpty()) return ReplaceResult(text, 0)
        val re = compile(query, o)
        var n = 0
        val repl = if (o.regex) replacement else Regex.escapeReplacement(replacement)
        val out = re.replace(text) { m -> n++; expand(m, repl) }
        return ReplaceResult(out, n)
    }

    /** Replace only the match starting at or after [from]; returns new text, or null if none. */
    fun replaceNext(text: String, query: String, replacement: String, from: Int, o: FindOptions = FindOptions()): ReplaceResult {
        if (query.isEmpty()) return ReplaceResult(text, 0)
        val re = compile(query, o)
        val m = re.find(text, from.coerceIn(0, text.length)) ?: re.find(text) ?: return ReplaceResult(text, 0)
        val repl = if (o.regex) replacement else Regex.escapeReplacement(replacement)
        return ReplaceResult(text.replaceRange(m.range, expand(m, repl)), 1)
    }

    private fun expand(m: MatchResult, repl: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < repl.length) {
            val c = repl[i]
            if (c == '\\' && i + 1 < repl.length) { sb.append(repl[i + 1]); i += 2; continue }
            if (c == '$' && i + 1 < repl.length && repl[i + 1].isDigit()) {
                var j = i + 1
                while (j < repl.length && repl[j].isDigit()) j++
                val g = repl.substring(i + 1, j).toInt()
                sb.append(m.groupValues.getOrNull(g) ?: "")
                i = j; continue
            }
            sb.append(c); i++
        }
        return sb.toString()
    }
}
