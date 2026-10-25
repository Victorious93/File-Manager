package ai.victorious.filemanager.core.notes

/**
 * Tiny markdown → display-token pass for the live preview pane.
 * Not a full CommonMark implementation — enough for headings, lists, code fences,
 * bold/italic, wikilinks and tags. Code fences are marked so the UI can run the
 * existing syntax highlighter on the body.
 */
sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Paragraph(val spans: List<MdSpan>) : MdBlock()
    data class Bullet(val spans: List<MdSpan>) : MdBlock()
    data class Numbered(val n: Int, val spans: List<MdSpan>) : MdBlock()
    data class Code(val language: String, val code: String) : MdBlock()
    data class Quote(val spans: List<MdSpan>) : MdBlock()
    data object Rule : MdBlock()
}

sealed class MdSpan {
    data class Text(val text: String) : MdSpan()
    data class Bold(val text: String) : MdSpan()
    data class Italic(val text: String) : MdSpan()
    data class Code(val text: String) : MdSpan()
    data class Wiki(val link: WikiLink) : MdSpan()
    data class Tag(val name: String) : MdSpan()
}

object MarkdownPreview {
    fun parse(text: String): List<MdBlock> {
        val body = WikiLinkParser.frontmatter(text)?.let { text.substring(it.bodyStart) } ?: text
        val out = mutableListOf<MdBlock>()
        val lines = body.replace("\r\n", "\n").split('\n')
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.trim() == "---" || line.trim() == "***" || line.trim() == "___" -> { out += MdBlock.Rule; i++ }
                line.startsWith("```") -> {
                    val lang = line.removePrefix("```").trim()
                    val buf = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].startsWith("```")) { buf.appendLine(lines[i]); i++ }
                    if (i < lines.size) i++ // closing fence
                    out += MdBlock.Code(lang, buf.toString().trimEnd('\n'))
                }
                line.matches(Regex("""^#{1,6}\s+.*""")) -> {
                    val level = line.takeWhile { it == '#' }.length
                    out += MdBlock.Heading(level, line.drop(level).trim())
                    i++
                }
                line.startsWith("> ") || line == ">" -> { out += MdBlock.Quote(inline(line.removePrefix("> ").removePrefix(">"))); i++ }
                line.matches(Regex("""^\s*[-*+]\s+.*""")) -> {
                    out += MdBlock.Bullet(inline(line.replace(Regex("""^\s*[-*+]\s+"""), "")))
                    i++
                }
                line.matches(Regex("""^\s*\d+\.\s+.*""")) -> {
                    val n = Regex("""^\s*(\d+)\.""").find(line)!!.groupValues[1].toInt()
                    out += MdBlock.Numbered(n, inline(line.replace(Regex("""^\s*\d+\.\s+"""), "")))
                    i++
                }
                line.isBlank() -> i++
                else -> {
                    val buf = StringBuilder(line)
                    i++
                    while (i < lines.size && lines[i].isNotBlank() && !lines[i].startsWith("#") && !lines[i].startsWith("```") && !lines[i].startsWith("> ") && !lines[i].matches(Regex("""^\s*[-*+]\s+.*""")) && !lines[i].matches(Regex("""^\s*\d+\.\s+.*"""))) {
                        buf.append(' ').append(lines[i]); i++
                    }
                    out += MdBlock.Paragraph(inline(buf.toString()))
                }
            }
        }
        return out
    }

    fun inline(text: String): List<MdSpan> {
        val out = mutableListOf<MdSpan>()
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("[[", i) -> {
                    val m = Regex("""\[\[([^\]|#]+)(?:#([^\]|]+))?(?:\|([^\]]+))?]]""").find(text, i)
                    if (m != null && m.range.first == i) {
                        out += MdSpan.Wiki(WikiLink(m.groupValues[1].trim(), m.groupValues[3].ifBlank { null }, m.groupValues[2].ifBlank { null }, m.value, m.range.first, m.range.last + 1))
                        i = m.range.last + 1
                    } else { out += MdSpan.Text(text[i].toString()); i++ }
                }
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > i) { out += MdSpan.Bold(text.substring(i + 2, end)); i = end + 2 } else { out += MdSpan.Text("**"); i += 2 }
                }
                text.startsWith("*", i) && (i + 1 >= text.length || text[i + 1] != '*') -> {
                    val end = text.indexOf('*', i + 1)
                    if (end > i) { out += MdSpan.Italic(text.substring(i + 1, end)); i = end + 1 } else { out += MdSpan.Text("*"); i++ }
                }
                text.startsWith("`", i) -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i) { out += MdSpan.Code(text.substring(i + 1, end)); i = end + 1 } else { out += MdSpan.Text("`"); i++ }
                }
                text[i] == '#' && (i == 0 || text[i - 1].isWhitespace()) && i + 1 < text.length && text[i + 1].isLetter() -> {
                    var j = i + 1
                    while (j < text.length && (text[j].isLetterOrDigit() || text[j] == '_' || text[j] == '-' || text[j] == '/')) j++
                    out += MdSpan.Tag(text.substring(i + 1, j)); i = j
                }
                else -> {
                    var j = i + 1
                    while (j < text.length && text[j] != '*' && text[j] != '`' && text[j] != '#' && !(text[j] == '[' && j + 1 < text.length && text[j + 1] == '[')) j++
                    out += MdSpan.Text(text.substring(i, j)); i = j
                }
            }
        }
        return out
    }
}
