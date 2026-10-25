package ai.victorious.filemanager.core.appmgr

/**
 * Logcat viewer model + filter. The app layer runs `logcat -v threadtime` (or dumps via Shizuku)
 * and feeds lines here. Pure parsing so unit tests don't need a device.
 */
data class LogLine(
    val raw: String,
    val timestamp: String = "",
    val pid: Int = 0,
    val tid: Int = 0,
    val level: Char = '?',
    val tag: String = "",
    val message: String = "",
)

object LogcatParser {
    // 10-13 12:34:56.789  1234  5678 I Tag: message
    private val THREADTIME = Regex(
        """^(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+)\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+([^:]+):\s?(.*)$""",
    )

    fun parse(line: String): LogLine {
        val m = THREADTIME.find(line.trim())
        return if (m == null) LogLine(raw = line)
        else LogLine(line, m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3].toInt(),
            m.groupValues[4][0], m.groupValues[5].trim(), m.groupValues[6])
    }

    fun parseAll(text: String): List<LogLine> = text.lineSequence().filter { it.isNotBlank() }.map(::parse).toList()
}

data class LogFilter(
    val levels: Set<Char> = setOf('V', 'D', 'I', 'W', 'E', 'F'),
    val tagContains: String = "",
    val messageContains: String = "",
    val pid: Int? = null,
    val packageHint: String = "",
) {
    fun matches(line: LogLine): Boolean {
        if (line.level !in levels && line.level != '?') return false
        if (pid != null && line.pid != 0 && line.pid != pid) return false
        if (tagContains.isNotBlank() && !line.tag.contains(tagContains, ignoreCase = true)) return false
        if (messageContains.isNotBlank() && !line.message.contains(messageContains, ignoreCase = true) &&
            !line.raw.contains(messageContains, ignoreCase = true)
        ) return false
        if (packageHint.isNotBlank() &&
            !line.tag.contains(packageHint, ignoreCase = true) &&
            !line.message.contains(packageHint, ignoreCase = true) &&
            !line.raw.contains(packageHint, ignoreCase = true)
        ) return false
        return true
    }
}

object LogcatFilter {
    fun apply(lines: List<LogLine>, filter: LogFilter): List<LogLine> = lines.filter(filter::matches)

    /** Plain-text export (same as what the user sees). */
    fun export(lines: List<LogLine>): String = lines.joinToString("\n") { it.raw }
}
