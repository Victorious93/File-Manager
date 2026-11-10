package ai.victorious.filemanager.core.notes

/**
 * Small markdown → display-token pass for the live preview pane.
 * Not a full CommonMark implementation. Covers headings, bullet / numbered lists (with nesting
 * depth), task lists (`- [ ]`, `- [x]`, tappable), GitHub-style pipe tables, code fences, quotes,
 * rules, and inline bold / italic / strikethrough / ==highlight== / `code` / [links](url) /
 * <autolinks> / wikilinks / #tags. Code fences are marked so the UI can run the existing syntax
 * highlighter on the body.
 *
 * [MdBlock.Task.line] is the 0-based line in the *whole* text (frontmatter included), so the UI
 * can pass it straight to [NoteOps.toggleTask].
 */
sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Paragraph(val spans: List<MdSpan>) : MdBlock()
    data class Bullet(val spans: List<MdSpan>, val depth: Int = 0) : MdBlock()
    data class Numbered(val n: Int, val spans: List<MdSpan>, val depth: Int = 0) : MdBlock()
    data class Task(val checked: Boolean, val spans: List<MdSpan>, val line: Int, val depth: Int = 0) : MdBlock()
    data class Code(val language: String, val code: String) : MdBlock()
    data class Quote(val spans: List<MdSpan>) : MdBlock()
    data class Table(val header: List<List<MdSpan>>, val align: List<TableAlign>, val rows: List<List<List<MdSpan>>>) : MdBlock()
    data object Rule : MdBlock()
}

enum class TableAlign { LEFT, CENTER, RIGHT }

sealed class MdSpan {
    data class Text(val text: String) : MdSpan()
    data class Bold(val text: String) : MdSpan()
    data class Italic(val text: String) : MdSpan()
    data class Strike(val text: String) : MdSpan()
    data class Highlight(val text: String) : MdSpan()
    data class Code(val text: String) : MdSpan()
    data class Link(val text: String, val url: String) : MdSpan()
    data class Wiki(val link: WikiLink) : MdSpan()
    data class Tag(val name: String) : MdSpan()
}

object MarkdownPreview {
    private val HEADING = Regex("""^#{1,6}\s+.*""")
    private val BULLET = Regex("""^(\s*)[-*+]\s+(.*)$""")
    private val NUMBERED = Regex("""^(\s*)(\d+)[.)]\s+(.*)$""")
    private val TASK = Regex("""^(\s*)[-*+]\s+\[([ xX])]\s?(.*)$""")
    private val TABLE_SEP = Regex("""^\s*\|?\s*:?-{1,}:?\s*(\|\s*:?-{1,}:?\s*)*\|?\s*$""")
    private val WIKI = Regex("""\[\[([^\]|#]+)(?:#([^\]|]+))?(?:\|([^\]]+))?]]""")
    private val LINK = Regex("""\[([^\]\n]+)]\(([^)\s]+)(?:\s+"[^"]*")?\)""")
    private val AUTOLINK = Regex("""<(https?://[^>\s]+)>""")

    /** Only these schemes become tappable links; anything else (javascript:, file:, intent:) stays text. */
    fun isSafeUrl(url: String): Boolean {
        val u = url.trim().lowercase()
        return u.startsWith("https://") || u.startsWith("http://") || u.startsWith("mailto:")
    }

    private fun depthOf(indent: String): Int = indent.replace("\t", "    ").length / 2

    fun parse(text: String): List<MdBlock> {
        val fm = WikiLinkParser.frontmatter(text)
        val body = fm?.let { text.substring(it.bodyStart) } ?: text
        val lineOffset = fm?.let { text.substring(0, it.bodyStart).count { c -> c == '\n' } } ?: 0
        val out = mutableListOf<MdBlock>()
        val lines = body.replace("\r\n", "\n").split('\n')
        var i = 0
        fun startsBlock(k: Int): Boolean {
            val l = lines[k]
            return l.isBlank() || HEADING.matches(l) || l.startsWith("```") || l.startsWith(">") ||
                BULLET.matches(l) || NUMBERED.matches(l) || isTableStart(lines, k)
        }
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trim()
            when {
                t == "---" || t == "***" || t == "___" -> { out += MdBlock.Rule; i++ }
                line.startsWith("```") -> {
                    val lang = line.removePrefix("```").trim()
                    val buf = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].startsWith("```")) { buf.appendLine(lines[i]); i++ }
                    if (i < lines.size) i++ // closing fence
                    out += MdBlock.Code(lang, buf.toString().trimEnd('\n'))
                }
                HEADING.matches(line) -> {
                    val level = line.takeWhile { it == '#' }.length
                    out += MdBlock.Heading(level, line.drop(level).trim().trimEnd('#').trim())
                    i++
                }
                line.startsWith("> ") || line == ">" -> { out += MdBlock.Quote(inline(line.removePrefix("> ").removePrefix(">"))); i++ }
                isTableStart(lines, i) -> {
                    val header = cells(lines[i])
                    val align = cells(lines[i + 1]).map { c ->
                        val s = c.trim()
                        when {
                            s.startsWith(":") && s.endsWith(":") -> TableAlign.CENTER
                            s.endsWith(":") -> TableAlign.RIGHT
                            else -> TableAlign.LEFT
                        }
                    }
                    i += 2
                    val rows = mutableListOf<List<List<MdSpan>>>()
                    while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                        val c = cells(lines[i])
                        // Pad / trim to the header width so the grid is always rectangular.
                        rows += List(header.size) { k -> inline(c.getOrElse(k) { "" }.trim()) }
                        i++
                    }
                    out += MdBlock.Table(header.map { inline(it.trim()) }, List(header.size) { align.getOrElse(it) { TableAlign.LEFT } }, rows)
                }
                TASK.matches(line) -> {
                    val m = TASK.matchEntire(line)!!
                    out += MdBlock.Task(m.groupValues[2] != " ", inline(m.groupValues[3]), i + lineOffset, depthOf(m.groupValues[1]))
                    i++
                }
                BULLET.matches(line) -> {
                    val m = BULLET.matchEntire(line)!!
                    out += MdBlock.Bullet(inline(m.groupValues[2]), depthOf(m.groupValues[1]))
                    i++
                }
                NUMBERED.matches(line) -> {
                    val m = NUMBERED.matchEntire(line)!!
                    out += MdBlock.Numbered(m.groupValues[2].toIntOrNull() ?: 1, inline(m.groupValues[3]), depthOf(m.groupValues[1]))
                    i++
                }
                line.isBlank() -> i++
                else -> {
                    val buf = StringBuilder(line.trim())
                    i++
                    while (i < lines.size && !startsBlock(i)) { buf.append(' ').append(lines[i].trim()); i++ }
                    out += MdBlock.Paragraph(inline(buf.toString()))
                }
            }
        }
        return out
    }

    private fun isTableStart(lines: List<String>, i: Int): Boolean =
        i >= 0 && i + 1 < lines.size && lines[i].contains('|') && TABLE_SEP.matches(lines[i + 1]) && lines[i + 1].contains('-') &&
            cells(lines[i]).isNotEmpty()

    /** Splits a pipe-table row, dropping the optional outer pipes and honouring `\|` escapes. */
    internal fun cells(row: String): List<String> {
        var s = row.trim()
        if (s.startsWith("|")) s = s.substring(1)
        if (s.endsWith("|") && !s.endsWith("\\|")) s = s.dropLast(1)
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var k = 0
        while (k < s.length) {
            val c = s[k]
            if (c == '\\' && k + 1 < s.length && s[k + 1] == '|') { cur.append('|'); k += 2; continue }
            if (c == '|') { out += cur.toString(); cur.clear() } else cur.append(c)
            k++
        }
        out += cur.toString()
        return out
    }

    fun inline(text: String): List<MdSpan> {
        val out = mutableListOf<MdSpan>()
        val plain = StringBuilder()
        fun flush() { if (plain.isNotEmpty()) { out += MdSpan.Text(plain.toString()); plain.clear() } }
        fun emit(s: MdSpan) { flush(); out += s }
        fun paired(i: Int, marker: String): Int = text.indexOf(marker, i + marker.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                text.startsWith("[[", i) -> {
                    val m = WIKI.find(text, i)
                    if (m != null && m.range.first == i) {
                        emit(MdSpan.Wiki(WikiLink(m.groupValues[1].trim(), m.groupValues[3].ifBlank { null }, m.groupValues[2].ifBlank { null }, m.value, m.range.first, m.range.last + 1)))
                        i = m.range.last + 1
                    } else { plain.append(c); i++ }
                }
                c == '[' -> {
                    val m = LINK.find(text, i)
                    if (m != null && m.range.first == i && isSafeUrl(m.groupValues[2])) {
                        emit(MdSpan.Link(m.groupValues[1], m.groupValues[2])); i = m.range.last + 1
                    } else { plain.append(c); i++ }
                }
                c == '<' -> {
                    val m = AUTOLINK.find(text, i)
                    if (m != null && m.range.first == i) { emit(MdSpan.Link(m.groupValues[1], m.groupValues[1])); i = m.range.last + 1 } else { plain.append(c); i++ }
                }
                text.startsWith("**", i) || text.startsWith("__", i) -> {
                    val marker = text.substring(i, i + 2)
                    val end = paired(i, marker)
                    if (end > i + 2) { emit(MdSpan.Bold(text.substring(i + 2, end))); i = end + 2 } else { plain.append(marker); i += 2 }
                }
                text.startsWith("~~", i) -> {
                    val end = paired(i, "~~")
                    if (end > i + 2) { emit(MdSpan.Strike(text.substring(i + 2, end))); i = end + 2 } else { plain.append("~~"); i += 2 }
                }
                text.startsWith("==", i) -> {
                    val end = paired(i, "==")
                    if (end > i + 2) { emit(MdSpan.Highlight(text.substring(i + 2, end))); i = end + 2 } else { plain.append("=="); i += 2 }
                }
                c == '*' || (c == '_' && (i == 0 || !text[i - 1].isLetterOrDigit())) -> {
                    val end = text.indexOf(c, i + 1)
                    val okEnd = end > i + 1 && (c == '*' || end + 1 >= text.length || !text[end + 1].isLetterOrDigit())
                    if (okEnd) { emit(MdSpan.Italic(text.substring(i + 1, end))); i = end + 1 } else { plain.append(c); i++ }
                }
                c == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i) { emit(MdSpan.Code(text.substring(i + 1, end))); i = end + 1 } else { plain.append(c); i++ }
                }
                c == '#' && (i == 0 || text[i - 1].isWhitespace()) && i + 1 < text.length && text[i + 1].isLetter() -> {
                    var j = i + 1
                    while (j < text.length && (text[j].isLetterOrDigit() || text[j] == '_' || text[j] == '-' || text[j] == '/')) j++
                    emit(MdSpan.Tag(text.substring(i + 1, j))); i = j
                }
                else -> { plain.append(c); i++ }
            }
        }
        flush()
        return out
    }
}
