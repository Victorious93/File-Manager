package ai.victorious.victorfiles.terminal

import ai.victorious.victorfiles.priv.PrivilegeManager
import ai.victorious.victorfiles.priv.ShellExecutor
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class TerminalSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    private val executor: ShellExecutor,
) {
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private val alive = AtomicBoolean(false)
    private val buffer = StringBuilder()
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    val output: String get() = synchronized(buffer) { buffer.toString() }
    val isAlive: Boolean get() = alive.get()

    fun start() {
        if (alive.get()) return
        val p = executor.openShell(arrayOf("sh", "-")) ?: error("Cannot open shell (${executor.backend})")
        process = p
        writer = BufferedWriter(OutputStreamWriter(p.outputStream))
        alive.set(true)
        append("[$title · ${executor.backend}]\n")
        thread(name = "term-out-$id", isDaemon = true) {
            try {
                BufferedReader(InputStreamReader(p.inputStream)).use { reader ->
                    val buf = CharArray(1024)
                    while (alive.get()) {
                        val n = reader.read(buf)
                        if (n < 0) break
                        append(String(buf, 0, n))
                    }
                }
            } catch (_: Exception) {
            } finally {
                alive.set(false)
                append("\n[session ended]\n")
            }
        }
    }

    fun write(line: String) {
        val w = writer ?: return
        try {
            w.write(line)
            if (!line.endsWith("\n")) w.write("\n")
            w.flush()
            append(line.trimEnd() + "\n")
        } catch (e: Exception) {
            append("[write failed: ${e.message}]\n")
        }
    }

    fun sendControl(c: Char) {
        val w = writer ?: return
        try {
            w.write(c.code)
            w.flush()
        } catch (_: Exception) {
        }
    }

    fun resizeHint() { /* PTY resize not available without JNI; no-op. */ }

    fun destroy() {
        alive.set(false)
        runCatching { writer?.close() }
        runCatching { process?.destroyForcibly() }
        process = null
        writer = null
    }

    fun addListener(l: (String) -> Unit) { listeners += l }
    fun removeListener(l: (String) -> Unit) { listeners -= l }

    private fun append(s: String) {
        synchronized(buffer) {
            buffer.append(s)
            if (buffer.length > 200_000) buffer.delete(0, buffer.length - 160_000)
        }
        listeners.forEach { it(s) }
    }

    companion object {
        fun create(privilege: PrivilegeManager, index: Int): TerminalSession {
            val exec = privilege.executor()
            return TerminalSession(title = "Shell ${index + 1}", executor = exec)
        }
    }
}

class TerminalSessionManager(private val privilege: PrivilegeManager) {
    private val sessions = mutableListOf<TerminalSession>()
    val all: List<TerminalSession> get() = sessions.toList()

    fun newSession(): TerminalSession {
        val s = TerminalSession.create(privilege, sessions.size)
        sessions += s
        s.start()
        return s
    }

    fun close(id: String) {
        val s = sessions.find { it.id == id } ?: return
        s.destroy()
        sessions.remove(s)
    }

    fun closeAll() {
        sessions.toList().forEach { it.destroy() }
        sessions.clear()
    }
}
