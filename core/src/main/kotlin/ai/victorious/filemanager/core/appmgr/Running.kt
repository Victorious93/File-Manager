package ai.victorious.filemanager.core.appmgr

import ai.victorious.filemanager.core.shell.ShellCommands

data class RunningProcess(
    val pid: Int,
    val user: String,
    val name: String,
    val packageName: String? = null,
)

/**
 * Parses `ps -A` / `ps -A -o USER,PID,NAME` style output. Pure so tests don't need a live device.
 * Force-stop of a package is the safe "kill" for an app; raw `kill <pid>` is reserved for root
 * and still goes through the confirm dialog.
 */
object RunningApps {
    private val PS = Regex("""^\s*(\S+)\s+(\d+)\s+(\S+)\s*$""")
    private val PS_WIDE = Regex("""^\s*(\S+)\s+(\d+)\s+\d+\s+\S+\s+\S+\s+\S+\s+\S+\s+(\S+)\s*$""")

    fun parsePs(text: String): List<RunningProcess> =
        text.lineSequence().drop(1).mapNotNull { line ->
            val m = PS.find(line) ?: PS_WIDE.find(line) ?: return@mapNotNull null
            val user = m.groupValues[1]
            val pid = m.groupValues[2].toInt()
            val name = m.groupValues[3]
            RunningProcess(pid, user, name, packageOf(name))
        }.toList()

    /** Best-effort package guess from a process name (`com.foo:remote` → `com.foo`). */
    fun packageOf(processName: String): String? {
        val base = processName.substringBefore(':')
        return if (base.contains('.') && base.any { it.isLetter() }) base else null
    }

    fun forceStop(pkg: String): String = AppState.forceStop(pkg)
    fun killPid(pid: Int): String = "kill -9 $pid"
}

/** Batch ops with a shared confirm step. */
data class BatchPlan(
    val title: String,
    val items: List<String>,
    val commands: List<String>,
) {
    val summary: String get() = "$title (${items.size}):\n" + items.take(20).joinToString("\n") +
        if (items.size > 20) "\n… ${items.size - 20} more" else ""
}

object BatchOps {
    fun disable(packages: List<String>, user: Int = 0) = BatchPlan(
        "Disable apps", packages, packages.map { AppState.disable(it, user) },
    )
    fun enable(packages: List<String>, user: Int = 0) = BatchPlan(
        "Enable apps", packages, packages.map { AppState.enable(it, user) },
    )
    fun forceStop(packages: List<String>) = BatchPlan(
        "Force-stop apps", packages, packages.map(AppState::forceStop),
    )
    fun blockComponents(comps: List<AppComponent>, user: Int = 0) = BatchPlan(
        "Block components",
        comps.map { "${it.packageName}/${it.className}" },
        comps.map { ComponentBlocker.pmDisable(it.packageName, it.className, user) },
    )
    fun unblockComponents(comps: List<AppComponent>, user: Int = 0) = BatchPlan(
        "Unblock components",
        comps.map { "${it.packageName}/${it.className}" },
        comps.map { ComponentBlocker.pmEnable(it.packageName, it.className, user) },
    )
}
