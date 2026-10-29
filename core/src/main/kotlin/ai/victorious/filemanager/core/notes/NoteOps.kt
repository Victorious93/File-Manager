package ai.victorious.filemanager.core.notes

/**
 * Phase 18 note tools: link-safe rename, unlinked mentions, tag tree, outline,
 * task toggling and a local (neighbourhood) graph. Written from scratch.
 */
data class Mention(val path: String, val line: Int, val start: Int, val end: Int, val snippet: String)
data class Heading(val level: Int, val text: String, val line: Int)
data class TagNode(val name: String, val fullName: String, val notes: List<String>, val children: List<TagNode>)
data class RenameResult(val newPath: String, val updatedNotes: List<String>, val linksRewritten: Int)

object NoteOps {
    private val LINK = Regex("""(!?)\[\[([^\]|#]+)((?:#[^\]|]+)?)((?:\|[^\]]+)?)]]""")
    private val HEADING = Regex("""^(#{1,6})\s+(.+?)\s*#*\s*$""")
    private val TASK = Regex("""^(\s*[-*+]\s+\[)([ xX])(].*)$""")

    /** Rewrite every `[[old…]]` / `![[old…]]` to point to [newTitle], keeping heading and alias. */
    fun rewriteLinks(body: String, oldTitle: String, newTitle: String): Pair<String, Int> {
        var count = 0
        val out = LINK.replace(body) { m ->
            if (m.groupValues[2].trim().equals(oldTitle, ignoreCase = true)) {
                count++
                "${m.groupValues[1]}[[$newTitle${m.groupValues[3]}${m.groupValues[4]}]]"
            } else m.value
        }
        return out to count
    }

    /** Character ranges that are code (fenced blocks or inline backticks) or wikilinks. */
    private fun protectedRanges(body: String): List<IntRange> {
        val r = mutableListOf<IntRange>()
        Regex("""```.*?(```|\z)""", RegexOption.DOT_MATCHES_ALL).findAll(body).forEach { r += it.range }
        Regex("""`[^`\n]+`""").findAll(body).forEach { r += it.range }
        LINK.findAll(body).forEach { r += it.range }
        Regex("""\[[^\]\n]*]\([^)\n]*\)""").findAll(body).forEach { r += it.range }
        WikiLinkParser.frontmatter(body)?.let { r += 0 until it.bodyStart }
        return r
    }

    /** Plain-text mentions of [title] (whole word, any case) not already inside a link or code. */
    fun unlinkedMentions(title: String, notes: Map<String, String>, selfPath: String?): List<Mention> {
        if (title.isBlank()) return emptyList()
        val re = Regex("""(?<![\p{L}\p{N}_])""" + Regex.escape(title) + """(?![\p{L}\p{N}_])""", RegexOption.IGNORE_CASE)
        val out = mutableListOf<Mention>()
        for ((path, body) in notes) {
            if (path == selfPath) continue
            val prot = protectedRanges(body)
            for (m in re.findAll(body)) {
                if (prot.any { m.range.first in it }) continue
                val ls = body.lastIndexOf('\n', m.range.first - 1).let { if (it < 0) 0 else it + 1 }
                val le = body.indexOf('\n', m.range.first).let { if (it < 0) body.length else it }
                val line = body.substring(0, m.range.first).count { it == '\n' }
                out += Mention(path, line, m.range.first, m.range.last + 1, body.substring(ls, le).trim().take(160))
            }
        }
        return out
    }

    /** Turn one plain mention into a wikilink (keeps the original text as alias when case differs). */
    fun linkMention(body: String, mention: Mention, title: String): String {
        val text = body.substring(mention.start, mention.end)
        val link = if (text == title) "[[$title]]" else "[[$title|$text]]"
        return body.substring(0, mention.start) + link + body.substring(mention.end)
    }

    /** Nested tag tree: `#a/b` counts for `a` and `a/b`. Notes listed under every level. */
    fun tagTree(notes: Map<String, String>): List<TagNode> {
        val byTag = sortedMapOf<String, MutableSet<String>>(String.CASE_INSENSITIVE_ORDER)
        for ((path, body) in notes) {
            for (t in WikiLinkParser.tags(body)) {
                val parts = t.name.trim('/').split('/').filter { it.isNotEmpty() }
                for (i in parts.indices) byTag.getOrPut(parts.take(i + 1).joinToString("/")) { linkedSetOf() } += path
            }
        }
        fun build(prefix: String?): List<TagNode> {
            val depth = prefix?.count { it == '/' }?.plus(1) ?: 0
            return byTag.keys.filter { k ->
                k.count { it == '/' } == depth && (prefix == null || k.startsWith("$prefix/", ignoreCase = true))
            }.map { k -> TagNode(k.substringAfterLast('/'), k, byTag[k]!!.sorted(), build(k)) }
        }
        return build(null)
    }

    fun flatten(nodes: List<TagNode>): List<Pair<Int, TagNode>> =
        nodes.flatMap { n -> listOf(0 to n) + flatten(n.children).map { (d, c) -> d + 1 to c } }

    /** ATX headings outside code fences and frontmatter. */
    fun outline(body: String): List<Heading> {
        val start = WikiLinkParser.frontmatter(body)?.bodyStart ?: 0
        val skipLines = body.substring(0, start).count { it == '\n' }
        var inFence = false
        val out = mutableListOf<Heading>()
        body.lines().forEachIndexed { i, line ->
            if (i < skipLines) return@forEachIndexed
            if (line.trimStart().startsWith("```")) { inFence = !inFence; return@forEachIndexed }
            if (inFence) return@forEachIndexed
            HEADING.matchEntire(line)?.let { out += Heading(it.groupValues[1].length, it.groupValues[2], i) }
        }
        return out
    }

    /** Toggle `- [ ]` ↔ `- [x]` on [line] (0-based). Returns body unchanged if not a task. */
    fun toggleTask(body: String, line: Int): String {
        val lines = body.split('\n').toMutableList()
        if (line !in lines.indices) return body
        val m = TASK.matchEntire(lines[line]) ?: return body
        val mark = if (m.groupValues[2] == " ") "x" else " "
        lines[line] = m.groupValues[1] + mark + m.groupValues[3]
        return lines.joinToString("\n")
    }

    data class TaskStats(val open: Int, val done: Int)
    fun taskStats(body: String): TaskStats {
        var o = 0; var d = 0
        body.lines().forEach { l -> TASK.matchEntire(l)?.let { if (it.groupValues[2] == " ") o++ else d++ } }
        return TaskStats(o, d)
    }

    /** Notes within [depth] link hops of [center], following links in both directions. */
    fun localGraph(index: BacklinkIndex, center: String, depth: Int = 1): Pair<List<GraphNode>, List<GraphEdge>> {
        val (all, edges) = NoteGraph.build(index)
        val keep = linkedSetOf(center)
        var frontier = setOf(center)
        repeat(depth.coerceAtLeast(0)) {
            val next = edges.filter { it.from in frontier || it.to in frontier }.flatMap { listOf(it.from, it.to) }.toSet() - keep
            keep += next; frontier = next
        }
        return all.filter { it.path in keep } to edges.filter { it.from in keep && it.to in keep }
    }
}
