package ai.victorious.filemanager.core.priv

/**
 * Fleet Shizuku standard: exactly four user-visible states. Sui exposes the same binder API, so
 * a Sui-backed device resolves through the same machine (installed = Sui active).
 */
enum class ShizukuState { GRANTED, DENIED, NOT_INSTALLED, NOT_RUNNING }

/** Raw probe results, collected off the Shizuku API by the app layer (pure data → testable). */
data class ShizukuProbe(
    /** Shizuku manager package present, or Sui initialised. */
    val installed: Boolean,
    /** `Shizuku.pingBinder()`. */
    val binderAlive: Boolean,
    /** `Shizuku.checkSelfPermission() == GRANTED`; only meaningful when the binder is alive. */
    val permissionGranted: Boolean,
    /** Pre-v11 Shizuku servers use the legacy runtime permission flow, which we do not support. */
    val preV11: Boolean = false,
)

object ShizukuStateMachine {
    fun resolve(p: ShizukuProbe): ShizukuState = when {
        // A live binder proves something (Shizuku or Sui) is installed, even if the package probe
        // missed it (package visibility, work profile, Sui without a manager app).
        !p.binderAlive && !p.installed -> ShizukuState.NOT_INSTALLED
        !p.binderAlive -> ShizukuState.NOT_RUNNING
        p.preV11 -> ShizukuState.DENIED
        p.permissionGranted -> ShizukuState.GRANTED
        else -> ShizukuState.DENIED
    }

    /** Applies a permission-result callback to the current state without re-probing. */
    fun onPermissionResult(current: ShizukuState, granted: Boolean): ShizukuState = when (current) {
        ShizukuState.NOT_INSTALLED, ShizukuState.NOT_RUNNING -> current
        else -> if (granted) ShizukuState.GRANTED else ShizukuState.DENIED
    }

    fun onBinderDead(current: ShizukuState): ShizukuState =
        if (current == ShizukuState.NOT_INSTALLED) current else ShizukuState.NOT_RUNNING

    /** Only a running, not-yet-granted server can show the permission dialog. */
    fun canRequestPermission(s: ShizukuState): Boolean = s == ShizukuState.DENIED
}

/** Consistent fleet copy for the status indicator and permission prompts. */
object ShizukuCopy {
    const val PERMISSION = "moe.shizuku.manager.permission.API_V23"

    fun title(s: ShizukuState): String = when (s) {
        ShizukuState.GRANTED -> "Shizuku: connected"
        ShizukuState.DENIED -> "Shizuku: permission needed"
        ShizukuState.NOT_INSTALLED -> "Shizuku: not installed"
        ShizukuState.NOT_RUNNING -> "Shizuku: not running"
    }

    fun detail(s: ShizukuState): String = when (s) {
        ShizukuState.GRANTED ->
            "Privileged file ops run through the Shizuku service. Each change still asks you first."
        ShizukuState.DENIED ->
            "Shizuku is running but File-Manager has not been allowed. Tap Grant access and choose Allow."
        ShizukuState.NOT_INSTALLED ->
            "Install Shizuku (or enable Sui under Magisk) to unlock /data, system dirs and package tools. " +
                "Everything else keeps working without it."
        ShizukuState.NOT_RUNNING ->
            "Open Shizuku and start it via Wireless debugging (Android 11+) or root, then come back. " +
                "Unprivileged browsing keeps working meanwhile."
    }

    fun action(s: ShizukuState): String? = when (s) {
        ShizukuState.GRANTED -> null
        ShizukuState.DENIED -> "Grant access"
        ShizukuState.NOT_INSTALLED -> "Get Shizuku"
        ShizukuState.NOT_RUNNING -> "Open Shizuku"
    }
}
