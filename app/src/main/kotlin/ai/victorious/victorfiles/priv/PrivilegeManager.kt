package ai.victorious.victorfiles.priv

import ai.victorious.victorfiles.core.priv.PrivilegeAvailability
import ai.victorious.victorfiles.core.priv.PrivilegeBackend
import ai.victorious.victorfiles.core.priv.PrivilegeSelector
import ai.victorious.victorfiles.core.shell.ShellResult
import ai.victorious.victorfiles.core.priv.CommandRunner
import ai.victorious.victorfiles.core.priv.ConfirmGate
import ai.victorious.victorfiles.core.priv.GatedPrivilegedOps
import ai.victorious.victorfiles.core.priv.PrivilegedOps
import ai.victorious.victorfiles.core.priv.PrivilegedOpsSelector
import ai.victorious.victorfiles.core.priv.ShellPrivilegedOps
import ai.victorious.victorfiles.core.priv.ShizukuCopy
import ai.victorious.victorfiles.core.priv.ShizukuState
import ai.victorious.victorfiles.core.priv.UnprivilegedOps
import ai.victorious.victorfiles.core.shell.ShellCommands
import com.topjohnwu.superuser.Shell

class PrivilegeManager(val settings: PrivilegeSettings, val shizukuController: ShizukuController) {
    private val normal = NormalShellExecutor()
    private val root = RootShellExecutor()
    private val shizuku = ShizukuShellExecutor(shizukuController)

    /** Single-use user confirmations required before any privileged mutation runs. */
    val confirmGate = ConfirmGate()

    init {
        Shell.enableVerboseLogging = false
        Shell.setDefaultBuilder(Shell.Builder.create().setTimeout(30))
    }

    fun availability(): PrivilegeAvailability {
        val st = shizukuController.refresh()
        val shizukuUp = st == ShizukuState.GRANTED || st == ShizukuState.DENIED
        val shizukuPerm = st == ShizukuState.GRANTED
        val rootGranted = runCatching { Shell.isAppGrantedRoot() == true }.getOrDefault(false)
        val rootPresent = RootShellExecutor.probeAvailable() || rootGranted
        return PrivilegeAvailability(
            rootAvailable = rootPresent,
            rootEnabled = settings.isRootEnabled(),
            shizukuAvailable = shizukuUp,
            shizukuPermission = shizukuPerm,
            shizukuEnabled = settings.isShizukuEnabled(),
        )
    }

    fun backend(): PrivilegeBackend = PrivilegeSelector.select(availability())

    fun executor(): ShellExecutor = when (backend()) {
        PrivilegeBackend.ROOT -> root
        PrivilegeBackend.SHIZUKU -> shizuku
        PrivilegeBackend.NORMAL -> normal
    }

    fun execElevated(command: String): ShellResult = executor().exec(command)

    /** Explicit user tap only. Returns false when the dialog cannot be shown in this state. */
    fun requestShizukuPermission(): Boolean = shizukuController.requestPermission()

    private val rootRunner = CommandRunner { cmd, stdin ->
        root.exec(if (stdin == null) cmd else "printf '%s' ${ShellCommands.quote(stdin)} | $cmd", 60)
    }

    /**
     * Power-user ops (protected dirs, /data, chmod/chown, remount, pm install/uninstall).
     * Root → Shizuku UserService → unprivileged; mutations require [confirmGate] approval.
     * Blocking: call from Dispatchers.IO.
     */
    fun ops(): PrivilegedOps = GatedPrivilegedOps(
        PrivilegedOpsSelector.select(
            availability(),
            root = { ShellPrivilegedOps(PrivilegeBackend.ROOT, rootRunner) },
            shizuku = { shizukuController.ops() },
            fallback = { UnprivilegedOps { missingReason() ?: ShizukuCopy.detail(shizukuController.state.value) } },
        ),
        confirmGate,
    )

    fun missingReason(): String? = PrivilegeSelector.missingReason(availability())
}
