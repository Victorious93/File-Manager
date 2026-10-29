package ai.victorious.filemanager.core.tags

import kotlinx.serialization.Serializable

/**
 * Smart collection = a saved query that is re-evaluated live over an index of files,
 * e.g. "PDFs over 5 MB changed this week tagged Red". Pure matching logic; the app feeds entries.
 */
@Serializable
data class SmartRule(
    val name: String,
    val nameContains: String? = null,
    val extensions: Set<String> = emptySet(),
    val minBytes: Long? = null,
    val maxBytes: Long? = null,
    val modifiedWithinDays: Int? = null,
    val colorTags: Set<String> = emptySet(),
    val underPath: String? = null,
)

data class SmartEntry(val path: String, val sizeBytes: Long, val modifiedMs: Long, val isDirectory: Boolean = false)

object SmartCollections {
    val presets: List<SmartRule> = listOf(
        SmartRule("Recent documents", extensions = setOf("pdf", "doc", "docx", "odt", "txt", "md", "xls", "xlsx", "ppt", "pptx"), modifiedWithinDays = 7),
        SmartRule("Large videos", extensions = setOf("mp4", "mkv", "webm", "avi", "mov"), minBytes = 500L * 1024 * 1024),
        SmartRule("APKs", extensions = setOf("apk", "apks", "xapk")),
        SmartRule("Screenshots this month", nameContains = "screenshot", modifiedWithinDays = 31),
        SmartRule("Tagged red", colorTags = setOf(ColorTag.RED.name)),
    )

    fun matches(rule: SmartRule, e: SmartEntry, tags: Set<ColorTag>, nowMs: Long): Boolean {
        if (e.isDirectory) return false
        val name = e.path.substringAfterLast('/')
        if (rule.underPath != null && !(e.path == rule.underPath || e.path.startsWith(rule.underPath.trimEnd('/') + "/"))) return false
        if (rule.nameContains != null && !name.contains(rule.nameContains, ignoreCase = true)) return false
        if (rule.extensions.isNotEmpty() && name.substringAfterLast('.', "").lowercase() !in rule.extensions.map { it.lowercase() }) return false
        if (rule.minBytes != null && e.sizeBytes < rule.minBytes) return false
        if (rule.maxBytes != null && e.sizeBytes > rule.maxBytes) return false
        if (rule.modifiedWithinDays != null && nowMs - e.modifiedMs > rule.modifiedWithinDays * 86_400_000L) return false
        if (rule.colorTags.isNotEmpty() && tags.none { it.name in rule.colorTags }) return false
        return true
    }

    fun evaluate(rule: SmartRule, entries: Sequence<SmartEntry>, tagsOf: (String) -> Set<ColorTag>, nowMs: Long, limit: Int = 500): List<SmartEntry> =
        entries.filter { matches(rule, it, tagsOf(it.path), nowMs) }.sortedByDescending { it.modifiedMs }.take(limit).toList()
}
