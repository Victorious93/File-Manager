package ai.victorious.filemanager.core.appmgr

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One step in a saved App Manager profile. Applied in order; each may need confirmation. */
@Serializable
sealed class ProfileAction {
    @Serializable data class DisablePackage(val packageName: String) : ProfileAction()
    @Serializable data class EnablePackage(val packageName: String) : ProfileAction()
    @Serializable data class SuspendPackage(val packageName: String) : ProfileAction()
    @Serializable data class UnsuspendPackage(val packageName: String) : ProfileAction()
    @Serializable data class ForceStop(val packageName: String) : ProfileAction()
    @Serializable data class BlockComponent(val packageName: String, val className: String, val type: String) : ProfileAction()
    @Serializable data class UnblockComponent(val packageName: String, val className: String) : ProfileAction()
    @Serializable data class GrantPermission(val packageName: String, val permission: String) : ProfileAction()
    @Serializable data class RevokePermission(val packageName: String, val permission: String) : ProfileAction()
    @Serializable data class SetAppOp(val packageName: String, val op: String, val mode: String) : ProfileAction()
}

/** Optional schedule for a profile (WorkManager maps this in the app layer). */
@Serializable
data class ProfileSchedule(
    /** Off | once delay hours | daily at hour | weekly. */
    val mode: String = "off", // off, once, daily, weekly
    val hourOfDay: Int = 9,
    val minuteOfHour: Int = 0,
    val dayOfWeek: Int = 1, // 1=Monday … 7=Sunday (ISO)
    val delayHours: Int = 1,
)

@Serializable
data class AppProfile(
    val id: String,
    val name: String,
    val description: String = "",
    val actions: List<ProfileAction> = emptyList(),
    val createdAtMs: Long = 0,
    val schedule: ProfileSchedule = ProfileSchedule(),
)

object ProfileActions {
    fun move(actions: List<ProfileAction>, from: Int, to: Int): List<ProfileAction> {
        if (from !in actions.indices || to !in actions.indices || from == to) return actions
        val m = actions.toMutableList()
        val item = m.removeAt(from)
        m.add(to, item)
        return m
    }

    fun removeAt(actions: List<ProfileAction>, index: Int): List<ProfileAction> =
        if (index in actions.indices) actions.toMutableList().also { it.removeAt(index) } else actions

    fun summarize(a: ProfileAction): String = when (a) {
        is ProfileAction.DisablePackage -> "Disable ${a.packageName}"
        is ProfileAction.EnablePackage -> "Enable ${a.packageName}"
        is ProfileAction.SuspendPackage -> "Suspend ${a.packageName}"
        is ProfileAction.UnsuspendPackage -> "Unsuspend ${a.packageName}"
        is ProfileAction.ForceStop -> "Force-stop ${a.packageName}"
        is ProfileAction.BlockComponent -> "Block ${a.packageName}/${a.className}"
        is ProfileAction.UnblockComponent -> "Unblock ${a.packageName}/${a.className}"
        is ProfileAction.GrantPermission -> "Grant ${a.permission} → ${a.packageName}"
        is ProfileAction.RevokePermission -> "Revoke ${a.permission} ← ${a.packageName}"
        is ProfileAction.SetAppOp -> "AppOp ${a.op}=${a.mode} on ${a.packageName}"
    }
}

object ProfileCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "kind" }
    fun encode(list: List<AppProfile>): String = json.encodeToString(list)
    fun decode(text: String): List<AppProfile> =
        if (text.isBlank()) emptyList() else runCatching { json.decodeFromString<List<AppProfile>>(text) }.getOrDefault(emptyList())
}

/**
 * Expands a profile into shell commands. Destructive steps are listed separately so the UI can
 * show a confirm dialog before running anything.
 */
object ProfileRunner {
    data class Plan(val commands: List<String>, val summary: List<String>)

    fun plan(profile: AppProfile, user: Int = 0): Plan {
        val cmds = mutableListOf<String>()
        val sum = mutableListOf<String>()
        for (a in profile.actions) when (a) {
            is ProfileAction.DisablePackage -> { cmds += AppState.disable(a.packageName, user); sum += "Disable ${a.packageName}" }
            is ProfileAction.EnablePackage -> { cmds += AppState.enable(a.packageName, user); sum += "Enable ${a.packageName}" }
            is ProfileAction.SuspendPackage -> { cmds += AppState.suspend(a.packageName, user); sum += "Suspend ${a.packageName}" }
            is ProfileAction.UnsuspendPackage -> { cmds += AppState.unsuspend(a.packageName, user); sum += "Unsuspend ${a.packageName}" }
            is ProfileAction.ForceStop -> { cmds += AppState.forceStop(a.packageName); sum += "Force-stop ${a.packageName}" }
            is ProfileAction.BlockComponent -> {
                cmds += ComponentBlocker.pmDisable(a.packageName, a.className, user)
                sum += "Block ${a.packageName}/${a.className}"
            }
            is ProfileAction.UnblockComponent -> {
                cmds += ComponentBlocker.pmEnable(a.packageName, a.className, user)
                sum += "Unblock ${a.packageName}/${a.className}"
            }
            is ProfileAction.GrantPermission -> {
                cmds += PermissionOps.grant(a.packageName, a.permission, user)
                sum += "Grant ${a.permission} → ${a.packageName}"
            }
            is ProfileAction.RevokePermission -> {
                cmds += PermissionOps.revoke(a.packageName, a.permission, user)
                sum += "Revoke ${a.permission} ← ${a.packageName}"
            }
            is ProfileAction.SetAppOp -> {
                val mode = AppOpsManagerOps.Mode.entries.firstOrNull { it.cli == a.mode }
                    ?: AppOpsManagerOps.Mode.DEFAULT
                cmds += AppOpsManagerOps.set(a.packageName, a.op, mode)
                sum += "AppOp ${a.op}=${a.mode} on ${a.packageName}"
            }
        }
        return Plan(cmds, sum)
    }
}
