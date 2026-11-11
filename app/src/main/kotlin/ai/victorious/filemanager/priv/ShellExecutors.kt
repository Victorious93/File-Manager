package ai.victorious.filemanager.priv

import ai.victorious.filemanager.core.priv.PrivilegeBackend
import ai.victorious.filemanager.core.shell.ShellCommands
import ai.victorious.filemanager.core.shell.ShellResult
import ai.victorious.filemanager.core.priv.ShizukuState
import com.topjohnwu.superuser.Shell
import java.io.File
import java.util.concurrent.TimeUnit
import rikka.shizuku.Shizuku

interface ShellExecutor {
    val backend: PrivilegeBackend

    /**
     * Runs [command]. [stdin], when given, is fed to the command's standard input, so file
     * contents never have to travel inside the command line (see ElevatedFileTransfer).
     */
    fun exec(command: String, timeoutSec: Long = 60, stdin: String? = null): ShellResult
    fun openShell(command: Array<String> = arrayOf("sh", "-")): Process?
}

class NormalShellExecutor : ShellExecutor {
    override val backend = PrivilegeBackend.NORMAL

    override fun exec(command: String, timeoutSec: Long, stdin: String?): ShellResult {
        val p = ProcessBuilder("sh", "-c", command).start()
        // Feed stdin from a thread so a large input cannot deadlock against the output pipes.
        val feeder = Thread { runCatching { p.outputStream.use { o -> if (stdin != null) o.write(stdin.toByteArray()) } } }
            .apply { start() }
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        val finished = p.waitFor(timeoutSec, TimeUnit.SECONDS)
        feeder.join(1000)
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

    override fun exec(command: String, timeoutSec: Long, stdin: String?): ShellResult {
        if (Shell.isAppGrantedRoot() != true) {
            return ShellResult(126, "", "Root not granted")
        }
        // printf is a shell builtin, so a large input is not limited by the argv size.
        val line = if (stdin == null) command else "printf '%s' ${ShellCommands.quote(stdin)} | $command"
        val r = Shell.cmd(line).exec()
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

/**
 * Shizuku backend. One-shot commands go through the bound [PrivilegedUserService] (official
 * UserService path). Only the interactive terminal still uses the reflective newProcess.
 */
class ShizukuShellExecutor(private val controller: ShizukuController) : ShellExecutor {
    override val backend = PrivilegeBackend.SHIZUKU

    override fun exec(command: String, timeoutSec: Long, stdin: String?): ShellResult =
        controller.exec(command, stdin, timeoutSec)

    override fun openShell(command: Array<String>): Process? {
        if (controller.refresh() != ShizukuState.GRANTED) return null
        return ShizukuProcesses.start(command)
    }
}
