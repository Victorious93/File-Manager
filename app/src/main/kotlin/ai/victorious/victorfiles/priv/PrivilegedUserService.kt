package ai.victorious.victorfiles.priv

import androidx.annotation.Keep
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Shizuku UserService: instantiated by Shizuku/Sui in a separate privileged process.
 * Must keep a public no-arg (or Context) constructor; never logs command output.
 */
@Keep
class PrivilegedUserService() : IPrivilegedService.Stub() {
    override fun destroy() = exitProcess(0)

    override fun exit() = destroy()

    override fun exec(command: String, stdin: String?, timeoutSec: Long): Array<String> = try {
        val p = ProcessBuilder("sh", "-c", command).start()
        if (stdin != null) p.outputStream.use { it.write(stdin.toByteArray()) } else p.outputStream.close()
        var out = ""
        var err = ""
        val t1 = Thread { out = p.inputStream.bufferedReader().readText() }.apply { start() }
        val t2 = Thread { err = p.errorStream.bufferedReader().readText() }.apply { start() }
        val done = p.waitFor(timeoutSec.coerceIn(1, 600), TimeUnit.SECONDS)
        if (!done) p.destroyForcibly()
        t1.join(2000); t2.join(2000)
        arrayOf((if (done) p.exitValue() else 124).toString(), out, if (done) err else "$err\n(timeout)")
    } catch (e: Exception) {
        arrayOf("1", "", e.javaClass.simpleName)
    }
}
