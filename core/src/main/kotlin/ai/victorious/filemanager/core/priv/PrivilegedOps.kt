package ai.victorious.filemanager.core.priv

import ai.victorious.filemanager.core.shell.ShellCommands
import ai.victorious.filemanager.core.shell.ShellListParser
import ai.victorious.filemanager.core.shell.ShellResult

/**
 * The only privileged surface the rest of the app sees. Implementations: a Shizuku UserService
 * (via [ShellPrivilegedOps] over the bound service), root, or [UnprivilegedOps]. No caller
 * imports rikka.shizuku directly. All calls are blocking: invoke from a background dispatcher.
 */
interface PrivilegedOps {
    val backend: PrivilegeBackend
    fun list(path: String): List<ShellListParser.Row>
    fun readText(path: String): String
    fun writeText(path: String, text: String)
    fun chmod(path: String, mode: String)
    fun chown(path: String, owner: String)
    fun remount(mountPoint: String, readWrite: Boolean)
    fun installPackage(apkPath: String)
    fun uninstallPackage(packageName: String)
}

class PrivilegedOpException(message: String) : Exception(message)

/** Runs a shell command (optionally feeding stdin) in a privileged process. */
fun interface CommandRunner {
    fun run(command: String, stdin: String?): ShellResult
}

/** Implements [PrivilegedOps] on top of a privileged [CommandRunner] (UserService or su). */
class ShellPrivilegedOps(
    override val backend: PrivilegeBackend,
    private val runner: CommandRunner,
) : PrivilegedOps {
    private fun ok(cmd: String, stdin: String? = null): String {
        val r = runner.run(cmd, stdin)
        // Never echo stdout into the error: it may hold protected file contents.
        if (!r.ok) throw PrivilegedOpException(r.stderr.lineSequence().firstOrNull()?.take(200)?.ifBlank { null } ?: "exit ${r.code}")
        return r.stdout
    }

    override fun list(path: String) = ShellListParser.parse(ok(ShellCommands.listParsable(path)))
    override fun readText(path: String) = ok(ShellCommands.cat(path))
    override fun writeText(path: String, text: String) { ok(ShellCommands.writeViaStdin(path), text) }
    override fun chmod(path: String, mode: String) { ok(ShellCommands.chmod(path, mode)) }
    override fun chown(path: String, owner: String) { ok(PowerCommands.chown(path, owner)) }
    override fun remount(mountPoint: String, readWrite: Boolean) { ok(PowerCommands.remount(mountPoint, readWrite)) }
    override fun installPackage(apkPath: String) { ok(PowerCommands.install(apkPath)) }
    override fun uninstallPackage(packageName: String) { ok(PowerCommands.uninstall(packageName)) }
}

/** No-privilege fallback: never crashes, explains how to unlock instead. */
class UnprivilegedOps(private val reason: () -> String) : PrivilegedOps {
    override val backend = PrivilegeBackend.NORMAL
    private fun no(): Nothing = throw PrivilegedOpException(reason())
    override fun list(path: String) = no()
    override fun readText(path: String) = no()
    override fun writeText(path: String, text: String) = no()
    override fun chmod(path: String, mode: String) = no()
    override fun chown(path: String, owner: String) = no()
    override fun remount(mountPoint: String, readWrite: Boolean) = no()
    override fun installPackage(apkPath: String) = no()
    override fun uninstallPackage(packageName: String) = no()
}

object PowerCommands {
    private val owner = Regex("[A-Za-z0-9_]+(:[A-Za-z0-9_]+)?")
    private val pkg = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
    private val mounts = setOf("/", "/system", "/vendor", "/product", "/system_ext", "/odm")

    fun chown(path: String, o: String): String {
        require(o.matches(owner)) { "Invalid owner: $o" }
        return "chown $o " + ShellCommands.quote(path)
    }

    fun remount(mountPoint: String, rw: Boolean): String {
        require(mountPoint in mounts) { "Unsupported mount point: $mountPoint" }
        return "mount -o remount,${if (rw) "rw" else "ro"} " + ShellCommands.quote(mountPoint)
    }

    /** `pm install` reading from the shell user's view of the path (shell uid can read /sdcard). */
    fun install(apkPath: String): String = "pm install -r " + ShellCommands.quote(apkPath)

    fun uninstall(p: String): String {
        require(p.matches(pkg)) { "Bad package: $p" }
        return "pm uninstall " + p
    }
}

/** Picks the privileged implementation: Root → Shizuku UserService → unprivileged. */
object PrivilegedOpsSelector {
    fun select(
        availability: PrivilegeAvailability,
        root: () -> PrivilegedOps,
        shizuku: () -> PrivilegedOps?,
        fallback: () -> PrivilegedOps,
    ): PrivilegedOps = when (PrivilegeSelector.select(availability)) {
        PrivilegeBackend.ROOT -> root()
        PrivilegeBackend.SHIZUKU -> shizuku() ?: fallback()
        PrivilegeBackend.NORMAL -> fallback()
    }
}

/** Kinds of privileged action that must be confirmed by the user before they run. */
enum class PowerAction { WRITE, CHMOD, CHOWN, REMOUNT, INSTALL, UNINSTALL }

/**
 * Security gate: privileged mutations need an explicit, single-use user confirmation for the
 * exact action + target. Nothing runs automatically (e.g. from a plugin, bridge, or restore).
 */
class ConfirmGate {
    private val approved = mutableSetOf<Pair<PowerAction, String>>()

    @Synchronized fun approve(action: PowerAction, target: String) { approved += action to target }

    @Synchronized fun consume(action: PowerAction, target: String): Boolean = approved.remove(action to target)
}

/** Wraps [PrivilegedOps] so mutating calls fail unless [ConfirmGate] holds a matching approval. */
class GatedPrivilegedOps(private val inner: PrivilegedOps, private val gate: ConfirmGate) : PrivilegedOps by inner {
    private fun check(a: PowerAction, t: String) {
        if (!gate.consume(a, t)) throw PrivilegedOpException("Not confirmed: ${a.name.lowercase()} $t")
    }
    override fun writeText(path: String, text: String) { check(PowerAction.WRITE, path); inner.writeText(path, text) }
    override fun chmod(path: String, mode: String) { check(PowerAction.CHMOD, path); inner.chmod(path, mode) }
    override fun chown(path: String, owner: String) { check(PowerAction.CHOWN, path); inner.chown(path, owner) }
    override fun remount(mountPoint: String, readWrite: Boolean) { check(PowerAction.REMOUNT, mountPoint); inner.remount(mountPoint, readWrite) }
    override fun installPackage(apkPath: String) { check(PowerAction.INSTALL, apkPath); inner.installPackage(apkPath) }
    override fun uninstallPackage(packageName: String) { check(PowerAction.UNINSTALL, packageName); inner.uninstallPackage(packageName) }
}
