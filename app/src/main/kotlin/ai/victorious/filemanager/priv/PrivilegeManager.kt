package ai.victorious.filemanager.priv

import ai.victorious.filemanager.core.priv.PrivilegeAvailability
import ai.victorious.filemanager.core.priv.PrivilegeBackend
import ai.victorious.filemanager.core.priv.PrivilegeSelector
import ai.victorious.filemanager.core.shell.ShellResult
import android.content.pm.PackageManager
import com.topjohnwu.superuser.Shell
import rikka.shizuku.Shizuku

class PrivilegeManager(val settings: PrivilegeSettings) {
    private val normal = NormalShellExecutor()
    private val root = RootShellExecutor()
    private val shizuku = ShizukuShellExecutor()

    init {
        Shell.enableVerboseLogging = false
        Shell.setDefaultBuilder(Shell.Builder.create().setTimeout(30))
    }

    fun availability(): PrivilegeAvailability {
        val shizukuUp = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val shizukuPerm = shizukuUp && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
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

    fun requestShizukuPermission(requestCode: Int = 9001) {
        if (runCatching { Shizuku.pingBinder() }.getOrDefault(false) &&
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED
        ) {
            Shizuku.requestPermission(requestCode)
        }
    }

    fun missingReason(): String? = PrivilegeSelector.missingReason(availability())
}
