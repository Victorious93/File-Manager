package ai.victorious.filemanager.core.appmgr

import ai.victorious.filemanager.core.shell.ShellCommands

data class PermissionInfo(val name: String, val granted: Boolean, val dangerous: Boolean, val group: String? = null)

/** Runtime permission grant/revoke via `pm` (Shizuku/root). Parses `dumpsys package` grant state. */
object PermissionOps {
    fun grant(pkg: String, perm: String, user: Int = 0): String = "pm grant --user $user ${ShellCommands.quote(pkg)} ${ShellCommands.quote(perm)}"
    fun revoke(pkg: String, perm: String, user: Int = 0): String = "pm revoke --user $user ${ShellCommands.quote(pkg)} ${ShellCommands.quote(perm)}"

    private val GRANT = Regex("""^([A-Za-z0-9_.]+): granted=(true|false)""")

    /** Parses the `runtime permissions:` block(s) of `dumpsys package <pkg>`. */
    fun parseGranted(dumpsys: String): Map<String, Boolean> {
        val out = linkedMapOf<String, Boolean>()
        var inBlock = false
        for (raw in dumpsys.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("runtime permissions:") -> inBlock = true
                inBlock && GRANT.containsMatchIn(line) -> GRANT.find(line)!!.let { out[it.groupValues[1]] = it.groupValues[2] == "true" }
                inBlock && line.endsWith(":") -> inBlock = false
            }
        }
        return out
    }
}

/** AppOps management via `appops`/`cmd appops` (Shizuku/root). */
object AppOpsManagerOps {
    enum class Mode(val cli: String) { ALLOW("allow"), DENY("deny"), IGNORE("ignore"), DEFAULT("default") }

    fun set(pkg: String, op: String, mode: Mode): String = "cmd appops set ${ShellCommands.quote(pkg)} ${ShellCommands.quote(op)} ${mode.cli}"
    fun get(pkg: String): String = "cmd appops get ${ShellCommands.quote(pkg)}"
    fun reset(pkg: String): String = "cmd appops reset ${ShellCommands.quote(pkg)}"

    /** Parses `cmd appops get <pkg>` lines like `COARSE_LOCATION: allow` or `CAMERA: deny; time=...`. */
    fun parse(text: String): Map<String, Mode> {
        val out = linkedMapOf<String, Mode>()
        for (raw in text.lineSequence()) {
            val m = Regex("""^\s*([A-Z_]+):\s*(allow|deny|ignore|default)""").find(raw) ?: continue
            out[m.groupValues[1]] = Mode.entries.first { it.cli == m.groupValues[2] }
        }
        return out
    }
}
