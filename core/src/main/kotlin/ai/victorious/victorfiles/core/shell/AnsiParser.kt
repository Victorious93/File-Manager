package ai.victorious.victorfiles.core.shell

/** One styled run of terminal text after ANSI CSI stripping/application. */
data class AnsiSpan(
    val text: String,
    val bold: Boolean = false,
    val fg: Int? = null,
    val bg: Int? = null,
)

/**
 * Minimal ANSI CSI parser for terminal scrollback: SGR colours (30-37, 90-97, 40-47, 100-107),
 * bold/reset, and strips cursor/erase sequences.
 */
object AnsiParser {
    private val ESC = '\u001B'
    private val CSI = Regex("\u001B\\[([0-9;]*)([A-Za-z])")

    fun parse(input: String): List<AnsiSpan> {
        val out = ArrayList<AnsiSpan>()
        var bold = false
        var fg: Int? = null
        var bg: Int? = null
        var i = 0
        val sb = StringBuilder()
        fun flush() {
            if (sb.isEmpty()) return
            out += AnsiSpan(sb.toString(), bold, fg, bg)
            sb.clear()
        }
        while (i < input.length) {
            if (input[i] == ESC) {
                val m = CSI.matchAt(input, i)
                if (m != null) {
                    flush()
                    val cmd = m.groupValues[2]
                    if (cmd == "m") {
                        val params = m.groupValues[1].split(';').filter { it.isNotEmpty() }.mapNotNull { it.toIntOrNull() }
                            .ifEmpty { listOf(0) }
                        for (p in params) when (p) {
                            0 -> { bold = false; fg = null; bg = null }
                            1 -> bold = true
                            22 -> bold = false
                            in 30..37 -> fg = p - 30
                            in 90..97 -> fg = p - 90 + 8
                            39 -> fg = null
                            in 40..47 -> bg = p - 40
                            in 100..107 -> bg = p - 100 + 8
                            49 -> bg = null
                        }
                    }
                    i = m.range.last + 1
                    continue
                }
                i++
                continue
            }
            sb.append(input[i])
            i++
        }
        flush()
        return out
    }

    fun strip(input: String): String = CSI.replace(input, "")
}
