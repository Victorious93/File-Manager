package ai.victorious.filemanager.core.photo

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Batch rename with pattern tokens. Preview only until the caller applies.
 * Tokens: {name} {ext} {index} {index:00} {date:yyyyMMdd} {parent}
 */
object BatchRename {
    data class Preview(val from: String, val to: String)

    fun applyPattern(
        fileName: String,
        pattern: String,
        index: Int,
        parentName: String = "",
        modifiedEpochMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.of("America/New_York"),
    ): String {
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot + 1) else ""
        var out = pattern
        out = out.replace("{name}", base)
        out = out.replace("{ext}", ext)
        out = out.replace("{parent}", parentName)
        val indexRe = Regex("""\{index(?::([^}]+))?\}""")
        out = indexRe.replace(out) { m ->
            val fmt = m.groupValues.getOrNull(1).orEmpty()
            if (fmt.isEmpty()) index.toString()
            else "%0${fmt.count { it == '0' }.coerceAtLeast(1)}d".format(index)
        }
        val dateRe = Regex("""\{date(?::([^}]+))?\}""")
        out = dateRe.replace(out) { m ->
            val fmt = m.groupValues.getOrNull(1)?.ifBlank { null } ?: "yyyyMMdd"
            DateTimeFormatter.ofPattern(fmt).withZone(zone).format(Instant.ofEpochMilli(modifiedEpochMs))
        }
        return out
    }

    fun preview(
        names: List<String>,
        pattern: String,
        parentName: String = "",
        startIndex: Int = 1,
    ): List<Preview> = names.mapIndexed { i, n ->
        Preview(n, applyPattern(n, pattern, startIndex + i, parentName))
    }
}
