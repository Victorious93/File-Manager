package ai.victorious.victorfiles.plugins

import ai.victorious.victorfiles.core.plugins.Capability
import ai.victorious.victorfiles.core.plugins.PluginAction
import ai.victorious.victorfiles.core.plugins.PluginCommandBuilder
import ai.victorious.victorfiles.core.plugins.PluginManifest
import ai.victorious.victorfiles.core.plugins.PluginManifestParser
import ai.victorious.victorfiles.core.plugins.SavedScript
import ai.victorious.victorfiles.core.plugins.ScriptRunnerSupport
import ai.victorious.victorfiles.core.shell.ShellResult
import ai.victorious.victorfiles.priv.NormalShellExecutor
import ai.victorious.victorfiles.priv.PrivilegeManager
import android.content.Context
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

data class InstalledPlugin(val manifest: PluginManifest, val enabled: Boolean)

/**
 * Stores declarative plugin manifests under app-private storage. Plugins are disabled on
 * install; the user enables each one after reviewing its declared capabilities.
 */
class PluginRepository(private val context: Context, private val privilege: PrivilegeManager) {
    private val dir = File(context.filesDir, "plugins").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("plugins", Context.MODE_PRIVATE)
    private val scriptsFile = File(context.filesDir, "scripts.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun list(): List<InstalledPlugin> = dir.listFiles { f -> f.extension == "json" }.orEmpty()
        .mapNotNull { f -> runCatching { PluginManifestParser.parse(f.readText()) }.getOrNull() }
        .sortedBy { it.name.lowercase() }
        .map { InstalledPlugin(it, prefs.getBoolean("enabled.${it.id}", false)) }

    /** Validates and installs; replaces an existing plugin with the same id and disables it again. */
    fun install(manifestText: String): PluginManifest {
        val m = PluginManifestParser.parse(manifestText)
        File(dir, "${m.id}.json").writeText(manifestText)
        setEnabled(m.id, false)
        return m
    }

    fun installFromFile(path: String): PluginManifest {
        val f = File(path)
        require(f.isFile && f.length() < 256 * 1024) { "Pick a plugin .json file (max 256 KB)" }
        return install(f.readText())
    }

    fun installSamples(): List<PluginManifest> =
        context.assets.list("plugins").orEmpty().filter { it.endsWith(".json") }.map { name ->
            install(context.assets.open("plugins/$name").bufferedReader().use { it.readText() })
        }

    fun remove(id: String) {
        File(dir, "$id.json").delete()
        prefs.edit().remove("enabled.$id").apply()
    }

    fun setEnabled(id: String, enabled: Boolean) {
        prefs.edit().putBoolean("enabled.$id", enabled).apply()
    }

    fun run(plugin: InstalledPlugin, action: PluginAction, selected: List<String>, dir: String, log: (String) -> Unit) {
        require(plugin.enabled) { "Enable ${plugin.manifest.name} first" }
        val elevated = Capability.ELEVATED in plugin.manifest.capabilities
        val exec = if (elevated) privilege.executor() else NormalShellExecutor()
        val cmds = PluginCommandBuilder.build(action, selected, dir)
        log("▶ ${plugin.manifest.name} › ${action.title} (${cmds.size} run${if (cmds.size == 1) "" else "s"}, ${exec.backend})")
        for (c in cmds) report(exec.exec(c, action.timeoutSec), log)
    }

    // --- Saved scripts (automation runner) ---

    fun scripts(): List<SavedScript> = if (!scriptsFile.exists()) emptyList() else
        runCatching { json.decodeFromString(ListSerializer(SavedScript.serializer()), scriptsFile.readText()) }.getOrDefault(emptyList())

    fun saveScript(name: String, body: String, perFile: Boolean, id: String? = null): SavedScript {
        val s = SavedScript(id ?: UUID.randomUUID().toString().take(8), name.ifBlank { "Script" }, body, perFile)
        writeScripts(scripts().filterNot { it.id == s.id } + s)
        return s
    }

    fun deleteScript(id: String) = writeScripts(scripts().filterNot { it.id == id })

    private fun writeScripts(list: List<SavedScript>) =
        scriptsFile.writeText(json.encodeToString(ListSerializer(SavedScript.serializer()), list))

    fun runScript(script: SavedScript, selected: List<String>, dir: String, log: (String) -> Unit) {
        val exec = NormalShellExecutor()
        val cmds = ScriptRunnerSupport.commandsFor(script, selected, dir)
        log("▶ ${script.name} (${cmds.size} run${if (cmds.size == 1) "" else "s"})")
        for (c in cmds) report(exec.exec(c, 120), log)
    }

    private fun report(r: ShellResult, log: (String) -> Unit) {
        if (r.stdout.isNotBlank()) log(r.stdout.trimEnd().take(20_000))
        if (r.stderr.isNotBlank()) log("stderr: " + r.stderr.trimEnd().take(5_000))
        log("exit ${r.code}")
    }
}
