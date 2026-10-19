package ai.victorious.victorfiles.ui

import ai.victorious.victorfiles.FileManagerApplication
import ai.victorious.victorfiles.terminal.FmTerminalClient
import ai.victorious.victorfiles.terminal.PtySupport
import ai.victorious.victorfiles.terminal.openPtySession
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

private class PtyTab(val session: TerminalSession, val client: FmTerminalClient, var title: String)

/**
 * Real PTY terminal (Termux/jackpal Apache-2.0 modules): resize, full VT100, long-press select,
 * copy/paste via the TerminalView selection + clipboard callbacks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PtyTerminalScreen(cwd: String?, onClose: () -> Unit, onFallback: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as FileManagerApplication
    val tabs = remember { mutableStateListOf<PtyTab>() }
    var active by remember { mutableIntStateOf(0) }
    var tick by remember { mutableIntStateOf(0) }
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    var viewRef by remember { mutableStateOf<TerminalView?>(null) }

    fun openTab() {
        val pair = openPtySession(context, app.privilege, cwd) ?: run { onFallback(); return }
        val (session, client) = pair
        client.onChanged = { tick++ }
        client.onFinished = { tick++ }
        client.onTitle = { t ->
            val i = tabs.indexOfFirst { it.session === session }
            if (i >= 0 && !t.isNullOrBlank()) tabs[i] = tabs[i].also { it.title = t }
            tick++
        }
        tabs += PtyTab(session, client, "PTY ${tabs.size + 1}")
        active = tabs.lastIndex
    }

    DisposableEffect(Unit) {
        if (!PtySupport.available) onFallback()
        else if (tabs.isEmpty()) openTab()
        onDispose { tabs.forEach { it.session.finishIfRunning() }; tabs.clear() }
    }

    BackHandler(onBack = onClose)
    val tab = tabs.getOrNull(active)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tab?.let { it.session.title ?: it.title } ?: "Terminal (PTY)") },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Close") }
                },
                actions = {
                    IconButton(onClick = {
                        viewRef?.let { v ->
                            v.requestFocus()
                            val imm = context.getSystemService(InputMethodManager::class.java)
                            imm?.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
                        }
                    }) { Icon(Icons.Outlined.Keyboard, "Keyboard") }
                    IconButton(onClick = { openTab() }) { Icon(Icons.Outlined.Add, "New session") }
                    IconButton(onClick = {
                        tab?.session?.finishIfRunning()
                        if (tab != null) tabs.remove(tab)
                        active = active.coerceAtMost(tabs.lastIndex.coerceAtLeast(0))
                        if (tabs.isEmpty()) openTab()
                    }) { Icon(Icons.Outlined.Close, "Close session") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text(
                "PTY · ${if (PtySupport.available) "libtermux" else "unavailable"} · long-press to select · copy/paste via clipboard",
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            if (tabs.size > 1) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                    tabs.forEachIndexed { i, t ->
                        FilterChip(selected = i == active, onClick = { active = i }, label = { Text(t.title) })
                    }
                }
            }
            Row(Modifier.padding(horizontal = 8.dp)) {
                listOf(
                    "Esc" to { tab?.session?.write(byteArrayOf(0x1b), 0, 1) },
                    "Tab" to { tab?.session?.write(byteArrayOf(0x09), 0, 1) },
                    "Ctrl" to { ctrl = !ctrl },
                    "Alt" to { alt = !alt },
                    "↑" to { tab?.session?.write("\u001b[A".toByteArray(), 0, 3) },
                    "↓" to { tab?.session?.write("\u001b[B".toByteArray(), 0, 3) },
                    "←" to { tab?.session?.write("\u001b[D".toByteArray(), 0, 3) },
                    "→" to { tab?.session?.write("\u001b[C".toByteArray(), 0, 3) },
                ).forEach { (label, action) ->
                    val selected = (label == "Ctrl" && ctrl) || (label == "Alt" && alt)
                    TextButton(onClick = { action() }) {
                        Text(if (selected) "[$label]" else label)
                    }
                }
            }
            if (tab != null) {
                // tick forces recomposition when output changes (TerminalView also invalidates itself).
                @Suppress("UNUSED_EXPRESSION") tick
                AndroidView(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    factory = { ctx ->
                        TerminalView(ctx, null).also { view ->
                            viewRef = view
                            view.setTextSize(14)
                            view.setTerminalViewClient(object : TerminalViewClient {
                                override fun onScale(scale: Float) = scale
                                override fun onSingleTapUp(e: MotionEvent) {
                                    view.requestFocus()
                                    ctx.getSystemService(InputMethodManager::class.java)
                                        ?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
                                }
                                override fun shouldBackButtonBeMappedToEscape() = false
                                override fun shouldEnforceCharBasedInput() = false
                                override fun shouldUseCtrlSpaceWorkaround() = false
                                override fun isTerminalViewSelected() = true
                                override fun copyModeChanged(copyMode: Boolean) {}
                                override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession) = false
                                override fun onKeyUp(keyCode: Int, e: KeyEvent) = false
                                override fun onLongPress(event: MotionEvent) = false
                                override fun readControlKey() = ctrl.also { if (it) ctrl = false }
                                override fun readAltKey() = alt.also { if (it) alt = false }
                                override fun readShiftKey() = false
                                override fun readFnKey() = false
                                override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession) = false
                                override fun onEmulatorSet() {}
                                override fun logError(tag: String, message: String) {}
                                override fun logWarn(tag: String, message: String) {}
                                override fun logInfo(tag: String, message: String) {}
                                override fun logDebug(tag: String, message: String) {}
                                override fun logVerbose(tag: String, message: String) {}
                                override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
                                override fun logStackTrace(tag: String, e: Exception) {}
                            })
                            view.attachSession(tab.session)
                        }
                    },
                    update = { view ->
                        if (view.currentSession !== tab.session) view.attachSession(tab.session)
                        view.onScreenUpdated()
                    },
                )
            }
        }
    }
}
