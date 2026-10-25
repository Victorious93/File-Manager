package ai.victorious.filemanager.core.notes

/**
 * Obsidian-style note graph primitives. Written from scratch — no Obsidian source.
 * Wikilink forms: `[[Target]]`, `[[Target|Alias]]`, `[[Target#Heading]]`,
 * `[[Target#Heading|Alias]]`. Targets are note titles (file name without `.md`).
 */
data class WikiLink(
    val target: String,
    val alias: String? = null,
    val heading: String? = null,
    val raw: String,
    val start: Int,
    val end: Int,
)

data class NoteTag(val name: String, val start: Int, val end: Int)

data class Frontmatter(val fields: Map<String, String>, val bodyStart: Int)

object WikiLinkParser {
    private val LINK = Regex("""\[\[([^\]|#]+)(?:#([^\]|]+))?(?:\|([^\]]+))?]]""")
    private val TAG = Regex("""(?:^|[\s(,])#([A-Za-z][A-Za-z0-9_/-]*)""")
    private val FM = Regex("""^---\r?\n(.*?)\r?\n---\r?\n?""", RegexOption.DOT_MATCHES_ALL)

    fun links(text: String): List<WikiLink> = LINK.findAll(text).map { m ->
        WikiLink(
            target = m.groupValues[1].trim(),
            heading = m.groupValues[2].ifBlank { null }?.trim(),
            alias = m.groupValues[3].ifBlank { null }?.trim(),
            raw = m.value,
            start = m.range.first,
            end = m.range.last + 1,
        )
    }.toList()

    fun tags(text: String): List<NoteTag> {
        val body = frontmatter(text)?.let { text.substring(it.bodyStart) } ?: text
        val linkRanges = links(body).map { it.start until it.end }
        return TAG.findAll(body).mapNotNull { m ->
            val nameStart = m.range.first + m.value.indexOf('#')
            if (linkRanges.any { nameStart in it }) return@mapNotNull null
            val lineStart = body.lastIndexOf('\n', nameStart).let { if (it < 0) 0 else it + 1 }
            // Skip ATX headings: line starts with #… then whitespace.
            if (body.substring(lineStart, nameStart).isBlank()) {
                var i = nameStart
                while (i < body.length && body[i] == '#') i++
                if (i < body.length && body[i].isWhitespace()) return@mapNotNull null
            }
            NoteTag(m.groupValues[1], nameStart, nameStart + 1 + m.groupValues[1].length)
        }.toList()
    }

    fun frontmatter(text: String): Frontmatter? {
        val m = FM.find(text) ?: return null
        val fields = linkedMapOf<String, String>()
        for (line in m.groupValues[1].lineSequence()) {
            val i = line.indexOf(':')
            if (i <= 0) continue
            val k = line.substring(0, i).trim()
            val v = line.substring(i + 1).trim().removeSurrounding("\"").removeSurrounding("'")
            if (k.isNotEmpty()) fields[k] = v
        }
        return Frontmatter(fields, m.range.last + 1)
    }

    fun displayText(link: WikiLink): String = link.alias ?: listOfNotNull(link.target, link.heading).joinToString(" › ")
}

object NotePaths {
    fun titleOf(path: String): String {
        val name = path.substringAfterLast('/').substringAfterLast('\\')
        return if (name.endsWith(".md", ignoreCase = true)) name.dropLast(3) else name
    }

    fun isNote(path: String): Boolean = path.endsWith(".md", ignoreCase = true)

    fun resolve(target: String, notePaths: Collection<String>): String? {
        val byTitle = notePaths.filter(::isNote).associateBy { titleOf(it) }
        return byTitle[target] ?: byTitle.entries.firstOrNull { it.key.equals(target, ignoreCase = true) }?.value
    }
}
