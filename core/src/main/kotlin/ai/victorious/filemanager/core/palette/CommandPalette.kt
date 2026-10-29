package ai.victorious.filemanager.core.palette

/**
 * Command palette ranking: fuzzy subsequence match with bonuses for word starts, contiguous runs,
 * keyword hits and recent use. Pure logic; the app supplies commands and runs the chosen one.
 */
data class PaletteCommand(val id: String, val title: String, val keywords: List<String> = emptyList(), val group: String = "")

object CommandPalette {
    /** Score of [query] against [text]; null when not every query char appears in order. */
    fun fuzzyScore(query: String, text: String): Int? {
        val q = query.lowercase().filterNot { it.isWhitespace() }
        if (q.isEmpty()) return 0
        val t = text.lowercase()
        var score = 0; var ti = 0; var prev = -2
        for (c in q) {
            var found = -1
            while (ti < t.length) { if (t[ti] == c) { found = ti; break }; ti++ }
            if (found < 0) return null
            val wordStart = found == 0 || !t[found - 1].isLetterOrDigit()
            score += 1 + (if (wordStart) 8 else 0) + (if (found == prev + 1) 4 else 0)
            prev = found
            ti = found + 1
        }
        if (t.startsWith(q)) score += 20
        if (t.contains(q)) score += 10
        return score - (t.length - q.length) / 8
    }

    fun rank(query: String, commands: List<PaletteCommand>, recent: List<String> = emptyList(), limit: Int = 30): List<PaletteCommand> {
        val q = query.trim()
        if (q.isEmpty()) {
            val byId = commands.associateBy { it.id }
            val rec = recent.mapNotNull { byId[it] }.distinct()
            return (rec + commands.filter { it !in rec }.sortedBy { it.title.lowercase() }).take(limit)
        }
        return commands.mapNotNull { c ->
            val best = (listOf(fuzzyScore(q, c.title)) + c.keywords.map { k -> fuzzyScore(q, k)?.minus(3) }).filterNotNull().maxOrNull()
                ?: return@mapNotNull null
            val recency = recent.indexOf(c.id).let { if (it < 0) 0 else (10 - it).coerceAtLeast(1) }
            c to best + recency
        }.sortedWith(compareByDescending<Pair<PaletteCommand, Int>> { it.second }.thenBy { it.first.title }).map { it.first }.take(limit)
    }

    fun pushRecent(recent: List<String>, id: String, max: Int = 10): List<String> = (listOf(id) + recent.filter { it != id }).take(max)
}
