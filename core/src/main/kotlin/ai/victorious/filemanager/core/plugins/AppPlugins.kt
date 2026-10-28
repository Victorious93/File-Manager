package ai.victorious.filemanager.core.plugins

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Plugin API v2: "app plugins" are separate APKs, sandboxed by Android. VictorFiles never loads their
 * code. A plugin declares an activity with [ACTION] and a <meta-data android:name="[META_DATA]">
 * whose value is a JSON [AppPluginDescriptor]. When the user runs it, VictorFiles starts that activity
 * with read-only content:// grants for exactly the selected files. See docs/PLUGINS.md.
 */
object AppPluginContract {
    const val ACTION = "ai.victorious.filemanager.action.PLUGIN"
    const val META_DATA = "ai.victorious.filemanager.plugin"
    const val EXTRA_API_VERSION = "ai.victorious.filemanager.extra.API_VERSION"
    const val EXTRA_DISPLAY_NAMES = "ai.victorious.filemanager.extra.DISPLAY_NAMES"
    const val API_VERSION = 2
}

@Serializable
data class AppPluginDescriptor(
    val id: String,
    val name: String,
    val version: String = "1.0",
    val description: String = "",
    val apiVersion: Int = AppPluginContract.API_VERSION,
    /** Extension filter, empty = any file. */
    val extensions: List<String> = emptyList(),
    val minFiles: Int = 1,
    val maxFiles: Int = 50,
    /** Optional: ask for write grants. Shown to the user; still limited to the selected files. */
    val wantsWrite: Boolean = false,
)

object AppPluginParser {
    private val ID = Regex("^[a-z0-9][a-z0-9._-]{1,63}$")
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): AppPluginDescriptor {
        val d = try { json.decodeFromString(AppPluginDescriptor.serializer(), text) }
        catch (e: Exception) { throw PluginValidationException(listOf("Not a valid plugin descriptor: ${e.message?.lineSequence()?.firstOrNull()}")) }
        val p = validate(d)
        if (p.isNotEmpty()) throw PluginValidationException(p)
        return d
    }

    fun validate(d: AppPluginDescriptor): List<String> = buildList {
        if (!ID.matches(d.id)) add("id must be lowercase letters, digits, . _ - (2-64 chars)")
        if (d.name.isBlank() || d.name.length > 60) add("name must be 1-60 chars")
        if (d.apiVersion != AppPluginContract.API_VERSION) add("apiVersion ${d.apiVersion} not supported (need ${AppPluginContract.API_VERSION})")
        if (d.minFiles < 0 || d.maxFiles < d.minFiles || d.maxFiles > 500) add("0 <= minFiles <= maxFiles <= 500")
        if (d.extensions.any { it.isBlank() || it.contains('.') || it.contains('/') }) add("extensions are bare like \"apk\"")
    }

    /** Null when the plugin can run on [paths]; otherwise the reason. */
    fun whyNot(d: AppPluginDescriptor, paths: List<String>): String? {
        if (paths.size < d.minFiles) return "Select at least ${d.minFiles} file(s)"
        if (paths.size > d.maxFiles) return "Select at most ${d.maxFiles} file(s)"
        if (d.extensions.isNotEmpty()) {
            val allowed = d.extensions.map { it.lowercase() }.toSet()
            val bad = paths.filter { it.substringAfterLast('/').substringAfterLast('.', "").lowercase() !in allowed }
            if (bad.isNotEmpty()) return "Only .${d.extensions.joinToString(", .")} files"
        }
        return null
    }
}
