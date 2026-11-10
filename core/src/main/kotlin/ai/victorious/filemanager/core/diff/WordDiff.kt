package ai.victorious.filemanager.core.diff

/** A run of characters in one side of a changed line; [changed] runs get the stronger highlight. */
data class InlineSegment(val text: String, val changed: Boolean)

/**
 * Word-level diff inside a changed line pair, for the side-by-side compare view.
 * Lines are split into words, whitespace runs and single punctuation marks, then diffed with the
 * same Myers engine as [LineDiff]. Concatenating each side's segments gives the original line.
 */
object WordDiff {
    const val MAX_TOKENS = 4000
    private val TOKEN = Regex("""\w+|\s+|[^\w\s]""")

    fun tokens(line: String): List<String> = TOKEN.findAll(line).map { it.value }.toList()

    fun inline(old: String, new: String): Pair<List<InlineSegment>, List<InlineSegment>> {
        val a = tokens(old)
        val b = tokens(new)
        // Myers keeps a trace per edit step; very long minified lines are just marked as fully changed.
        if (a.size + b.size > MAX_TOKENS) return listOf(InlineSegment(old, true)) to listOf(InlineSegment(new, true))
        val ops = LineDiff.diff(a, b)
        val left = ArrayList<InlineSegment>()
        val right = ArrayList<InlineSegment>()
        for (d in ops) when (d.op) {
            DiffOp.EQUAL -> { push(left, d.text, false); push(right, d.text, false) }
            DiffOp.DELETE -> push(left, d.text, true)
            DiffOp.INSERT -> push(right, d.text, true)
        }
        return left to right
    }

    /** Share of characters unchanged across both sides (0..1); low values mean "rewritten line". */
    fun similarity(old: String, new: String): Double {
        if (old.isEmpty() && new.isEmpty()) return 1.0
        val (l, r) = inline(old, new)
        val kept = l.filter { !it.changed }.sumOf { it.text.length }
        return (2.0 * kept) / (old.length + new.length)
    }

    private fun push(list: MutableList<InlineSegment>, text: String, changed: Boolean) {
        val last = list.lastOrNull()
        if (last != null && last.changed == changed) list[list.size - 1] = last.copy(text = last.text + text) else list += InlineSegment(text, changed)
    }
}
