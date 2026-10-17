package ai.victorious.filemanager.priv

import ai.victorious.filemanager.core.priv.PrivilegeBackend
import ai.victorious.filemanager.core.shell.ShellResult
import android.content.pm.PackageManager
import com.topjohnwu.superuser.Shell
import java.io.File
import java.util.concurrent.TimeUnit
import rikka.shizuku.Shizuku

interface ShellExecutor {
    val backend: PrivilegeBackend
    fun exec(command: String, timeoutSec: Long = 60): ShellResult
    fun openShell(command: Array<String> = arrayOf("sh", "-")): Process?
}

class NormalShellExecutor : ShellExecutor {
    override val backend = PrivilegeBackend.NORMAL

    override fun exec(command: String, timeoutSec: Long): ShellResult {
        val p = ProcessBuilder("sh", "-c", command).start()
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        val finished = p.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!finished) {
            p.destroyForcibly()
            return ShellResult(124, out, err + "\n(timeout)")
        }
        return ShellResult(p.exitValue(), out, err)
    }

    override fun openShell(command: Array<String>): Process? =
        runCatching { ProcessBuilder(*command).redirectErrorStream(true).start() }.getOrNull()
}

class RootShellExecutor : ShellExecutor {
    override val backend = PrivilegeBackend.ROOT

    override fun exec(command: String, timeoutSec: Long): ShellResult {
        if (Shell.isAppGrantedRoot() != true) {
            return ShellResult(126, "", "Root not granted")
        }
        val r = Shell.cmd(command).exec()
        return ShellResult(r.code, r.out.joinToString("\n"), r.err.joinToString("\n"))
    }

    override fun openShell(command: Array<String>): Process? {
        val args = if (command.contentEquals(arrayOf("sh", "-"))) {
            arrayOf("su")
        } else {
            arrayOf("su", "-c", command.joinToString(" "))
        }
        return runCatching { ProcessBuilder(*args).redirectErrorStream(true).start() }.getOrNull()
    }

    companion object {
        fun probeAvailable(): Boolean = runCatching {
            File("/system/xbin/su").canExecute() ||
                File("/system/bin/su").canExecute() ||
                File("/sbin/su").canExecute() ||
                Shell.isAppGrantedRoot() == true
        }.getOrDefault(false)
    }
}

/**
 * Shizuku 13 made [Shizuku.newProcess] non-public; we call it reflectively (same signature as
 * the old public API) so we can run short shell commands without a custom UserService.
 */
object ShizukuProcesses {
    private val newProcess by lazy {
        runCatching {
            Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            ).also { it.isAccessible = true }
        }.getOrNull()
    }

    fun start(command: Array<String>, env: Array<String>? = null, dir: String? = null): Process? {
        val m = newProcess ?: return null
        return runCatching { m.invoke(null, command, env, dir) as Process }.getOrNull()
    }
}

class ShizukuShellExecutor : ShellExecutor {
    override val backend = PrivilegeBackend.SHIZUKU

    private fun ready(): Boolean =
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    override fun exec(command: String, timeoutSec: Long): ShellResult {
        if (!ready()) return ShellResult(126, "", "Shizuku not ready")
        val proc = ShizukuProcesses.start(arrayOf("sh", "-c", command))
            ?: return ShellResult(1, "", "Shizuku newProcess unavailable")
        return try {
            val out = proc.inputStream.bufferedReader().readText()
            val err = proc.errorStream.bufferedReader().readText()
            val code = try {
                proc.waitFor()
            } catch (_: Exception) {
                -1
            }
            ShellResult(code, out, err)
        } catch (e: Exception) {
            ShellResult(1, "", e.message ?: "Shizuku exec failed")
        }
    }

    override fun openShell(command: Array<String>): Process? {
        if (!ready()) return null
        return ShizukuProcesses.start(command)
    }
}
