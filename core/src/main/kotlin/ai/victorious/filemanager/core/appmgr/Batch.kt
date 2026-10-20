package ai.victorious.filemanager.core.appmgr

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** One app-manager action. Every action knows whether it is destructive (needs a confirm dialog). */
@Serializable
sealed class AppAction {
    abstract val packageName: String
    abstract val destructive: Boolean
    abstract fun describe(): String

    @Serializable @SerialName("freeze")
    data class Freeze(override val packageName: String) : AppAction() {
        override val destructive get() = true
        override fun describe() = "Freeze $packageName"
    }
    @Serializable @SerialName("unfreeze")
    data class Unfreeze(override val packageName: String) : AppAction() {
        override val destructive get() = false
        override fun describe() = "Unfreeze $packageName"
    }
    @Serializable @SerialName("disable")
    data class Disable(override val packageName: String) : AppAction() {
        override val destructive get() = true
        override fun describe() = "Disable $packageName"
    }
    @Serializable @SerialName("enable")
    data class Enable(override val packageName: String) : AppAction() {
        override val destructive get() = false
        override fun describe() = "Enable $packageName"
    }
    @Serializable @SerialName("force_stop")
    data class ForceStop(override val packageName: String) : AppAction() {
        override val destructive get() = true
        override fun describe() = "Force-stop $packageName"
    }
    @Serializable @SerialName("clear_data")
    data class ClearData(override val packageName: String) : AppAction() {
        override val destructive get() = true
        override fun describe() = "Clear ALL data of $packageName"
    }
    @Serializable @SerialName("block_component")
    data class BlockComponent(override val packageName: String, val className: String, val type: ComponentType) : AppAction() {
        override val destructive get() = true
        override fun describe() = "Block ${type.name.lowercase()} $className"
    }
    @Serializable @SerialName("unblock_component")
    data class UnblockComponent(override val packageName: String, val className: String, val type: ComponentType) : AppAction() {
        override val destructive get() = false
        override fun describe() = "Unblock ${type.name.lowercase()} $className"
    }
    @Serializable @SerialName("revoke_permission")
    data class RevokePermission(override val packageName: String, val permission: String) : AppAction() {
        override val destructive get() = true
        override fun describe() = "Revoke $permission from $packageName"
    }
    @Serializable @SerialName("grant_permission")
    data class GrantPermission(override val packageName: String, val permission: String) : AppAction() {
        override val destructive get() = false
        override fun describe() = "Grant $permission to $packageName"
    }
    @Serializable @SerialName("set_appop")
    data class SetAppOp(override val packageName: String, val op: String, val mode: AppOpsManagerOps.Mode) : AppAction() {
        override val destructive get() = mode == AppOpsManagerOps.Mode.DENY || mode == AppOpsManagerOps.Mode.IGNORE
        override fun describe() = "AppOp $op = ${mode.cli} for $packageName"
    }
    @Serializable @SerialName("uninstall")
    data class Uninstall(override val packageName: String) : AppAction() {
        override val destructive get() = true
        override fun describe() = "Uninstall $packageName"
    }
}

/** Shell command(s) for one action. Component blocks use `pm` here (works on Shizuku and root). */
object ActionCommands {
    fun commands(a: AppAction, user: Int = 0): List<String> = when (a) {
        is AppAction.Freeze -> listOf(AppState.suspend(a.packageName, user))
        is AppAction.Unfreeze -> listOf(AppState.unsuspend(a.packageName, user))
        is AppAction.Disable -> listOf(AppState.disable(a.packageName, user))
        is AppAction.Enable -> listOf(AppState.enable(a.packageName, user))
        is AppAction.ForceStop -> listOf(AppState.forceStop(a.packageName))
        is AppAction.ClearData -> listOf(AppState.clearData(a.packageName, user))
        is AppAction.BlockComponent -> listOf(ComponentBlocker.pmDisable(a.packageName, a.className, user))
        is AppAction.UnblockComponent -> listOf(ComponentBlocker.pmEnable(a.packageName, a.className, user))
        is AppAction.RevokePermission -> listOf(PermissionOps.revoke(a.packageName, a.permission, user))
        is AppAction.GrantPermission -> listOf(PermissionOps.grant(a.packageName, a.permission, user))
        is AppAction.SetAppOp -> listOf(AppOpsManagerOps.set(a.packageName, a.op, a.mode))
        is AppAction.Uninstall -> listOf("pm uninstall --user $user ${ai.victorious.filemanager.core.shell.ShellCommands.quote(a.packageName)}")
    }
}

data class BatchPlan(val actions: List<AppAction>) {
    val destructiveCount get() = actions.count { it.destructive }
    /** Destructive batches always need the user's explicit confirmation before [BatchRunner.run]. */
    val needsConfirmation get() = destructiveCount > 0
    fun summary(): List<String> = actions.map { (if (it.destructive) "⚠ " else "") + it.describe() }
}

data class BatchResult(val action: AppAction, val ok: Boolean, val message: String)

class BatchConfirmationRequired : IllegalStateException("destructive batch needs confirmation")

/**
 * Runs a batch sequentially (keeps going after a failure, reports each).
 * Self-protection: refuses to act on [protectedPackages] (this app, the shell, system UI…).
 */
class BatchRunner(
    private val exec: (String) -> Pair<Boolean, String>,
    private val protectedPackages: Set<String> = DEFAULT_PROTECTED,
) {
    fun run(plan: BatchPlan, confirmed: Boolean): List<BatchResult> {
        if (plan.needsConfirmation && !confirmed) throw BatchConfirmationRequired()
        return plan.actions.map { a ->
            if (a.packageName in protectedPackages && a.destructive) {
                BatchResult(a, false, "refused: protected package")
            } else {
                var ok = true
                val msgs = mutableListOf<String>()
                for (c in ActionCommands.commands(a)) {
                    val (o, m) = exec(c)
                    if (m.isNotBlank()) msgs += m.trim()
                    if (!o) { ok = false; break }
                }
                BatchResult(a, ok, msgs.joinToString("\n").take(500))
            }
        }
    }

    companion object {
        val DEFAULT_PROTECTED = setOf(
            "android", "com.android.systemui", "com.android.shell", "com.android.settings",
            "com.android.phone", "ai.victorious.filemanager", "ai.victorious.filemanager.debug",
            "moe.shizuku.privileged.api",
        )
    }
}

/** A profile = a named, saved set of actions (e.g. "Night: freeze social apps"). */
@Serializable
data class AppProfile(val id: String, val name: String, val actions: List<AppAction>)

object ProfileCodec {
    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "kind" }
    fun encode(p: List<AppProfile>): String = json.encodeToString(ListSerializer(AppProfile.serializer()), p)
    fun decode(s: String): List<AppProfile> =
        if (s.isBlank()) emptyList() else runCatching { json.decodeFromString(ListSerializer(AppProfile.serializer()), s) }.getOrDefault(emptyList())

    /** The inverse profile (e.g. Freeze -> Unfreeze), used for one-tap "undo profile". */
    fun inverse(p: AppProfile): AppProfile = p.copy(id = p.id + "-undo", name = "Undo ${p.name}", actions = p.actions.mapNotNull {
        when (it) {
            is AppAction.Freeze -> AppAction.Unfreeze(it.packageName)
            is AppAction.Unfreeze -> AppAction.Freeze(it.packageName)
            is AppAction.Disable -> AppAction.Enable(it.packageName)
            is AppAction.Enable -> AppAction.Disable(it.packageName)
            is AppAction.BlockComponent -> AppAction.UnblockComponent(it.packageName, it.className, it.type)
            is AppAction.UnblockComponent -> AppAction.BlockComponent(it.packageName, it.className, it.type)
            is AppAction.RevokePermission -> AppAction.GrantPermission(it.packageName, it.permission)
            is AppAction.GrantPermission -> AppAction.RevokePermission(it.packageName, it.permission)
            else -> null // force-stop / clear / uninstall / appops have no safe inverse
        }
    })
}
