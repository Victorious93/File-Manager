package ai.victorious.filemanager.core.appmgr

/** One `logcat -v threadtime` line. */
data class LogLine(val date: String, val time: String, val pid: Int, val tid: Int, val level: Char, val tag: String, val message: String)

object Logcat {
    private val RX = Regex("""^(\d\d-\d\d)\s+(\d\d:\d\d:\d\d\.\d+)\s+(\d+)\s+(\d+)\s+([VDIWEFA])\s+(.*?)\s*:\s(.*)$""")
    val LEVELS = "VDIWEFA"

    fun parse(line: String): LogLine? = RX.find(line)?.let { m ->
        val g = m.groupValues
        LogLine(g[1], g[2], g[3].toInt(), g[4].toInt(), g[5][
