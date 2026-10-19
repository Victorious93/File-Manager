package ai.victorious.victorfiles.ui

import ai.victorious.victorfiles.terminal.PtySupport
import ai.victorious.victorfiles.terminal.TerminalSessionManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Terminal entry point: prefer a real PTY (Termux/jackpal Apache-2.0 modules + libtermux.so).
 * If the native library fails to load, fall back to the existing pipe-based shell with the
 * lightweight AnsiParser.
 */
@Composable
fun TerminalScreen(manager: TerminalSessionManager, onClose: () -> Unit, cwd: String? = null) {
    var forcePipe by remember { mutableStateOf(false) }
    if (!forcePipe && PtySupport.available) {
        PtyTerminalScreen(cwd = cwd, onClose = onClose, onFallback = { forcePipe = true })
    } else {
        PipeTerminalScreen(manager, onClose)
    }
}
