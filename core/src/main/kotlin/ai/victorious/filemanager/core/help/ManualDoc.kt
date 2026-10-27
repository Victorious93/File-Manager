package ai.victorious.filemanager.core.help

/**
 * A tiny Markdown reader for the bundled user manual (docs/MANUAL.md, packaged as the
 * `MANUAL.md` asset). It understands the subset the manual uses: ATX headings, paragraphs,
 * bullet and numbered lists, fenced code blocks, block quotes and horizontal rules. Inline
 * markup (`**bold**`, `` `code` ``, `[text](link)`) is kept as [Inline] spans for the UI.
 */
object ManualDoc {
    sealed interface Block {
        data class Heading(val level: Int, val text: String, val anchor: String) : Block
        data class Paragraph(val text: String) : Block
        data class ListItem(val text: String, val ordered: Boolean, val marker: String, val depth: Int) : Block
        data class Code(val text: String) : Block
        data class Quote(val text: String) : Block
        data object Rule : Block

        val plain: String
            get() = when (this) {
                is Heading -> text
                is Paragraph -> text
                is ListItem -> text
                is Code -> text
                is Quote -> text
                Rule -> ""
            }.let(::stripInline)
    }

    /** A heading plus the blocks under it, up to the next heading of the same or higher level. */
    data class Section(val heading: Block.Heading?, val blocks: List<Block>, val startIndex: Int)

    data class Hit(val blockIndex: Int, val section: String, val snippet: String)

    enum class Style { PLAIN, BOLD, ITALIC, CODE, LINK }
    data class Inline(val text: String, val style: Style, val url: String? = null)

    fun parse(markdown: String): List<Block> {
        val out = mutableListOf<Block>()
        val para = StringBuilder()
        fun flush() {
            if (para.isNotBlank()) out += Block.Paragraph(para.toString().trim())
            para.setLength(0)
        }
        val lines = markdown.replace("\r\n", "\n").split('\n')
        var i = 0
        while (i < lines.size) {
            val raw = lines[i]
            val line = raw.trimEnd()
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("```") -> {
                    flush()
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                        if (code.isNotEmpty()) code.append('\n')
                        code.append(lines[i])
                        i++
                    }
                    out += Block.Code(code.toString())
                }
                HEADING.matches(trimmed) -> {
                    flush()
                    val m = HEADING.matchEntire(trimmed)!!
                    val text = m.groupValues[2].trim().trimEnd('#').trim()
                    out += Block.Heading(m.groupValues[1].length, text, anchor(text))
                }
                trimmed.matches(RULE) -> { flush(); out += Block.Rule }
                BULLET.matches(line) -> {
                    flush()
                    val m = BULLET.matchEntire(line)!!
                    out += Block.ListItem(continuation(lines, i, m.groupValues[3]).also { i = it.second }.first, false, "•", m.groupValues[1].length / 2)
                }
                NUMBERED.matches(line) -> {
                    flush()
                    val m = NUMBERED.matchEntire(line)!!
                    out += Block.ListItem(continuation(lines, i, m.groupValues[3]).also { i = it.second }.first, true, m.groupValues[2] + ".", m.groupValues[1].length / 2)
                }
                trimmed.startsWith(">") -> {
                    flush()
                    out += Block.Quote(trimmed.removePrefix(">").trim())
                }
                trimmed.isEmpty() -> flush()
                else -> { if (para.isNotEmpty()) para.append(' '); para.append(trimmed) }
            }
            i++
        }
        flush()
        return out
    }

    /** Joins indented continuation lines of a list item; returns the text and the last line index used. */
    private fun continuation(lines: List<String>, start: Int, first: String): Pair<String, Int> {
        val sb = StringBuilder(first.trim())
        var j = start
        while (j + 1 < lines.size) {
            val next = lines[j + 1]
            if (next.isBlank() || !next.startsWith("  ") || BULLET.matches(next) || NUMBERED.matches(next) || next.trimStart().startsWith("```")) break
            sb.append(' ').append(next.trim())
            j++
        }
        return sb.toString() to j
    }

    fun sections(blocks: List<Block>): List<Section> {
        val out = mutableListOf<Section>()
        var current: Block.Heading? = null
        var start = 0
        var buf = mutableListOf<Block>()
        blocks.forEachIndexed { idx, b ->
            if (b is Block.Heading) {
                if (current != null || buf.isNotEmpty()) out += Section(current, buf, start)
                current = b; start = idx; buf = mutableListOf()
            } else buf += b
        }
        if (current != null || buf.isNotEmpty()) out += Section(current, buf, start)
        return out
    }

    /** Table of contents: level 2 and 3 headings with their block index. */
    fun toc(blocks: List<Block>): List<Pair<Int, Block.Heading>> =
        blocks.withIndex().mapNotNull { (i, b) -> (b as? Block.Heading)?.takeIf { it.level in 2..3 }?.let { i to it } }

    /**
     * Case-insensitive search. Every query word must appear in the block (or its heading).
     * Results are in document order with a short snippet around the first match.
     */
    fun search(blocks: List<Block>, query: String, limit: Int = 100): List<Hit> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val hits = mutableListOf<Hit>()
        var heading = ""
        for ((i, b) in blocks.withIndex()) {
            if (b is Block.Heading) heading = b.text
            val text = b.plain
            if (text.isEmpty()) continue
            val hay = (text + " " + heading).lowercase()
            if (words.all { it in hay }) {
                hits += Hit(i, heading, snippet(text, words.first()))
                if (hits.size >= limit) break
            }
        }
        return hits
    }

    internal fun snippet(text: String, word: String, radius: Int = 60): String {
        val at = text.lowercase().indexOf(word)
        if (at < 0) return text.take(radius * 2)
        val from = (at - radius).coerceAtLeast(0)
        val to = (at + word.length + radius).coerceAtMost(text.length)
        return (if (from > 0) "…" else "") + text.substring(from, to) + (if (to < text.length) "…" else "")
    }

    fun anchor(text: String): String =
        stripInline(text).lowercase().replace(Regex("[^a-z0-9 -]"), "").trim().replace(Regex("\\s+"), "-")

    fun stripInline(text: String): String = inline(text).joinToString("") { it.text }

    /** Splits `**bold**`, `*italic*`, `` `code` `` and `[label](url)` into styled spans. */
    fun inline(text: String): List<Inline> {
        val out = mutableListOf<Inline>()
        val plain = StringBuilder()
        fun emit(t: String, s: Style, url: String? = null) {
            if (plain.isNotEmpty()) { out += Inline(plain.toString(), Style.PLAIN); plain.setLength(0) }
            if (t.isNotEmpty()) out += Inline(t, s, url)
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i) { emit(text.substring(i + 1, end), Style.CODE); i = end + 1 } else { plain.append(c); i++ }
                }
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > i) { emit(text.substring(i + 2, end), Style.BOLD); i = end + 2 } else { plain.append(c); i++ }
                }
                c == '*' && i + 1 < text.length && text[i + 1] != ' ' -> {
                    val end = text.indexOf('*', i + 1)
                    if (end > i + 1) { emit(text.substring(i + 1, end), Style.ITALIC); i = end + 1 } else { plain.append(c); i++ }
                }
                c == '[' -> {
                    val close = text.indexOf("](", i)
                    val paren = if (close > i) text.indexOf(')', close) else -1
                    if (close > i && paren > close) { emit(text.substring(i + 1, close), Style.LINK, text.substring(close + 2, paren)); i = paren + 1 } else { plain.append(c); i++ }
                }
                else -> { plain.append(c); i++ }
            }
        }
        emit("", Style.PLAIN)
        return out
    }

    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val RULE = Regex("^(-{3,}|\\*{3,}|_{3,})$")
    private val BULLET = Regex("^( *)([-*+])\\s+(.*)$")
    private val NUMBERED = Regex("^( *)(\\d+)[.)]\\s+(.*)$")
}
