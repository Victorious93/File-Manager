package ai.victorious.victorfiles.core.plugins

import ai.victorious.victorfiles.core.path.PathHelpers
import ai.victorious.victorfiles.core.shell.ShellCommands
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Declarative plugin model. Plugins are JSON manifests whose actions are shell command
 * templates; there is no dex/class loading. Every action must be covered by a capability
 * the manifest declares, and the user enables each plugin individually.
 */
@Serializable
enum class Capability {
    /** Action receives selected file paths ({file}, {files}, {name}). */
    @SerialName("read_files") READ_FILES,
    /** Action may create/modify files (writes into {dir}). */
    @SerialName("write_files") WRITE_FILES,
    /** Action runs a shell command (always required for shell actions). */
    @SerialName("shell") SHELL,
    /** Action may use the network (e.g. curl). Declared for transparency. */
    @SerialName("network") NETWORK,
    /** Action wants the elevated (root/Shizuku) shell when available. */
    @SerialName("elevated") ELEVATED,
}

@Serializable
enum class ActionTarget {
    /** Run once per selected file. */
    @SerialName("each_file") EACH_FILE,
    /** Run once with all selected files ({files}). */
    @SerialName("all_files") ALL_FILES,
    /** Run once in the focused folder; no file selection needed. */
    @SerialName("folder") FOLDER,
}

@Serializable
data class PluginAction(
    val id: String,
    val title: String,
    val command: String,
    val target: ActionTarget = ActionTarget.EACH_FILE,
    /** Optional extension filter, e.g. ["apk","zip"]. Empty = any. */
    val extensions: List<String> = emptyList(),
    val timeoutSec: Long = 60,
)

@Serializable
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String = "1.0",
    val author: String = "",
    val description: String = "",
    val apiVersion: Int = 1,
    val capabilities: List<Capability> = emptyList(),
    val actions: List<PluginAction> = emptyList(),
)

class PluginValidationException(val problems: List<String>) :
    IllegalArgumentException("Invalid plugin: " + problems.joinToString("; "))

object PluginManifestParser {
    const val SUPPORTED_API = 1
    private val ID = Regex("^[a-z0-9][a-z0-9._-]{1,63}$")
    private val PLACEHOLDER = Regex("\\{([a-z]+)}")
    val KNOWN_PLACEHOLDERS = setOf("file", "files", "name", "dir")
    private val NETWORK_HINTS = Regex("\\b(curl|wget|nc|ncat|telnet|ssh|scp|ftp)\\b")
    private val WRITE_HINTS = Regex("(>|\\b(rm|mv|cp|mkdir|touch|tee|chmod|chown|unzip|tar|dd|sed\\s+-i)\\b)")

    private val json = Json { ignoreUnknownKeys = false; isLenient = false }

    fun parse(text: String): PluginManifest {
        val m = try {
            json.decodeFromString(PluginManifest.serializer(), text)
        } catch (e: Exception) {
            throw PluginValidationException(listOf("Not a valid manifest: ${e.message?.lineSequence()?.firstOrNull() ?: "parse error"}"))
        }
        val problems = validate(m)
        if (problems.isNotEmpty()) throw PluginValidationException(problems)
        return m
    }

    fun validate(m: PluginManifest): List<String> {
        val p = mutableListOf<String>()
        if (!ID.matches(m.id)) p += "id must be lowercase letters/digits/._- (2-64 chars)"
        if (m.name.isBlank()) p += "name is required"
        if (m.apiVersion != SUPPORTED_API) p += "apiVersion ${m.apiVersion} not supported (expected $SUPPORTED_API)"
        if (m.actions.isEmpty()) p += "at least one action is required"
        val ids = mutableSetOf<String>()
        val caps = m.capabilities.toSet()
        for (a in m.actions) {
            val where = "action '${a.id}'"
            if (!ID.matches(a.id)) p += "$where: invalid id"
            if (!ids.add(a.id)) p += "$where: duplicate id"
            if (a.title.isBlank()) p += "$where: title is required"
            if (a.command.isBlank()) p += "$where: command is required"
            if (a.timeoutSec !in 1..600) p += "$where: timeoutSec must be 1..600"
            if (Capability.SHELL !in caps) p += "$where: needs the 'shell' capability"
            val used = PLACEHOLDER.findAll(a.command).map { it.groupValues[1] }.toSet()
            (used - KNOWN_PLACEHOLDERS).forEach { p += "$where: unknown placeholder {$it}" }
            val touchesFiles = used.any { it == "file" || it == "files" || it == "name" }
            if (touchesFiles && Capability.READ_FILES !in caps) p += "$where: uses selected files but lacks 'read_files'"
            if (a.target != ActionTarget.FOLDER && !touchesFiles) p += "$where: targets files but never references {file}/{files}/{name}"
            if (a.target == ActionTarget.EACH_FILE && "files" in used) p += "$where: use {file} with each_file, {files} with all_files"
            if (a.target == ActionTarget.ALL_FILES && ("file" in used || "name" in used)) p += "$where: use {files} with all_files"
            if (NETWORK_HINTS.containsMatchIn(a.command) && Capability.NETWORK !in caps) p += "$where: looks like network access but lacks 'network'"
            if (WRITE_HINTS.containsMatchIn(a.command) && Capability.WRITE_FILES !in caps) p += "$where: looks like it writes files but lacks 'write_files'"
        }
        return p
    }
}

/** Builds concrete, safely quoted shell commands from an action template. */
object PluginCommandBuilder {
    fun applies(action: PluginAction, path: String): Boolean {
        if (action.extensions.isEmpty()) return true
        val ext = PathHelpers.fileName(path).substringAfterLast('.', "").lowercase()
        return action.extensions.any { it.lowercase().trimStart('.') == ext }
    }

    /** Returns one command per invocation. Paths are always single-quoted. */
    fun build(action: PluginAction, selected: List<String>, dir: String): List<String> {
        val files = selected.filter { applies(action, it) }
        return when (action.target) {
            ActionTarget.FOLDER -> listOf(render(action.command, null, files, dir))
            ActionTarget.ALL_FILES -> {
                require(files.isNotEmpty()) { "Select at least one matching file" }
                listOf(render(action.command, null, files, dir))
            }
            ActionTarget.EACH_FILE -> {
                require(files.isNotEmpty()) { "Select at least one matching file" }
                files.map { render(action.command, it, files, dir) }
            }
        }
    }

    private fun render(template: String, file: String?, files: List<String>, dir: String): String {
        var out = template
        out = out.replace("{dir}", ShellCommands.quote(dir))
        out = out.replace("{files}", files.joinToString(" ") { ShellCommands.quote(it) })
        if (file != null) {
            out = out.replace("{file}", ShellCommands.quote(file))
            out = out.replace("{name}", ShellCommands.quote(PathHelpers.fileName(file)))
        }
        return out
    }
}

/** A saved user script for the automation runner. Runs once per selected file or once in the folder. */
@Serializable
data class SavedScript(
    val id: String,
    val name: String,
    val body: String,
    val perFile: Boolean = true,
)

object ScriptRunnerSupport {
    /**
     * Wraps [script] so it runs with FILE/NAME/DIR env-style variables set to safely quoted values.
     * The body itself is user-authored and runs as-is.
     */
    fun commandsFor(script: SavedScript, selected: List<String>, dir: String): List<String> {
        require(script.body.isNotBlank()) { "Script is empty" }
        val header = "DIR=" + ShellCommands.quote(dir) + "; cd \"\$DIR\" || exit 1; "
        if (!script.perFile) {
            val all = selected.joinToString(" ") { ShellCommands.quote(it) }
            return listOf(header + "set -- $all; " + script.body)
        }
        require(selected.isNotEmpty()) { "Select at least one file" }
        return selected.map { f ->
            header + "FILE=" + ShellCommands.quote(f) + "; NAME=" + ShellCommands.quote(PathHelpers.fileName(f)) + "; " + script.body
        }
    }
}
