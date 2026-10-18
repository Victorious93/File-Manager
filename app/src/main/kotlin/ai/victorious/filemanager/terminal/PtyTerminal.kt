package ai.victorious.filemanager.terminal

import ai.victorious.filemanager.core.priv.PrivilegeBackend
import ai.victorious.filemanager.priv.PrivilegeManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.File

/**
 * Whether the Termux/jackpal native PTY library (`libtermux.so`) can be loaded. Checked once;
 * if it fails, the UI keeps the pipe-based [ai.victorious.filemanager.terminal.TerminalSession].
 */
object PtySupport {
    val available: Boolean by lazy {
        runCatching {
            System.loadLibrary("termux")
            true
        }.getOrDefault(false)
    }

    fun shellPath(privilege: PrivilegeManager): String {
        // Prefer a real login shell. Root: su. Shizuku can't host a PTY through our executor, so
        // we fall back to /system/bin/sh even when Shizuku is the privilege backend.
        return when (privilege.backend()) {
            PrivilegeBackend.ROOT -> listOf("/system/xbin/su", "/system/bin/su", "/sbin/su")
                .firstOrNull { File(it).canExecute() } ?: "/system/bin/sh"
            else -> "/system/bin/sh"
        }
    }

    fun env(): Array<String> {
        val home = System.getenv("HOME") ?: "/data/local/tmp"
        val path = System.getenv("PATH") ?: "/system/bin:/system/xbin"
        return arrayOf(
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            "HOME=$home",
            "PATH=$path",
            "PREFIX=/system",
            "LANG=en_US.UTF-8",
        )
    }
}

/** Thin adapter: Termux callbacks → clipboard + optional listeners. */
class FmTerminalClient(private val context: Context) : TerminalSessionClient {
    var onChanged: (() -> Unit)? = null
    var onFinished: (() -> Unit)? = null
    var onTitle: ((String?) -> Unit)? = null

    override fun onTextChanged(session: TerminalSession) { onChanged?.invoke() }
    override fun onTitleChanged(session: TerminalSession) { onTitle?.invoke(session.title) }
    override fun onSessionFinished(session: TerminalSession) { onFinished?.invoke() }
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("terminal", text))
    }
    override fun onPasteTextFromClipboard(session: TerminalSession) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: return
        session.write(text.toByteArray(), 0, text.toByteArray().size)
    }
    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) { onChanged?.invoke() }
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun getTerminalCursorStyle(): Int? = null
    override fun logError(tag: String, message: String) {}
    override fun logWarn(tag: String, message: String) {}
    override fun logInfo(tag: String, message: String) {}
    override fun logDebug(tag: String, message: String) {}
    override fun logVerbose(tag: String, message: String) {}
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
    override fun logStackTrace(tag: String, e: Exception) {}
}

/**
 * Creates a Termux [TerminalSession] for [cwd] (or the app's files dir). Does not start the
 * emulator until [TerminalSession.updateSize] is called (TerminalView does that).
 */
fun openPtySession(context: Context, privilege: PrivilegeManager, cwd: String?): Pair<TerminalSession, FmTerminalClient>? {
    if (!PtySupport.available) return null
    val client = FmTerminalClient(context.applicationContext)
    val shell = PtySupport.shellPath(privilege)
    val args = if (shell.endsWith("/su")) arrayOf("su", "-") else arrayOf("sh", "-")
    val dir = cwd?.takeIf { File(it).isDirectory }
        ?: context.filesDir.absolutePath
    val session = TerminalSession(shell, dir, args, PtySupport.env(), /* transcriptRows */ 3000, client)
    return session to client
}

/** Best-effort current working directory of a running PTY session (Linux /proc). */
fun TerminalSession.cwdOrNull(): String? = runCatching {
    val pid = pid
    if (pid <= 0) return@runCatching null
    File("/proc/$pid/cwd").canonicalPath
}.getOrNull()
