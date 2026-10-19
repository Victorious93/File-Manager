package ai.victorious.victorfiles.ui

import ai.victorious.victorfiles.core.shell.AnsiParser
import ai.victorious.victorfiles.core.shell.AnsiSpan
import ai.victorious.victorfiles.terminal.TerminalSession
import ai.victorious.victorfiles.terminal.TerminalSessionManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val AnsiFg = listOf(
    Color(0xFF000000), Color(0xFFCC0000), Color(0xFF4E9A06), Color(0xFFC4A000),
    Color(0xFF3465A4), Color(0xFF75507B), Color(0xFF06989A), Color(0xFFD3D7CF),
    Color(0xFF555753), Color(0xFFEF2929), Color(0xFF8AE234), Color(0xFFFCE94F),
    Color(0xFF729FCF), Color(0xFFAD7FA8), Color(0xFF34E2E2), Color(0xFFEEEEEC),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(manager: TerminalSessionManager, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    var sessions by remember { mutableStateOf(manager.all) }
    var activeId by remember { mutableStateOf(sessions.firstOrNull()?.id) }
    var tick by remember { mutableIntStateOf(0) }
    var input by remember { mutableStateOf("") }

    fun ensureSession() {
        if (sessions.isEmpty()) {
            val s = manager.newSession()
            sessions = manager.all
            activeId = s.id
        }
    }

    LaunchedEffect(Unit) { ensureSession() }
    val active = sessions.find { it.id == activeId } ?: sessions.firstOrNull()

    DisposableEffect(active?.id) {
        val s = active
        val listener: (String) -> Unit = { tick++ }
        s?.addListener(listener)
        onDispose { s?.removeListener(listener) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(active?.title ?: "Terminal") },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Close") }
                },
                actions = {
                    IconButton(onClick = {
                        val s = manager.newSession()
                        sessions = manager.all
                        activeId = s.id
                    }) { Icon(Icons.Outlined.Add, "New session") }
                    IconButton(onClick = {
                        active?.let {
                            manager.close(it.id)
                            sessions = manager.all
                            activeId = sessions.firstOrNull()?.id
                            if (sessions.isEmpty()) ensureSession()
                        }
                    }) { Icon(Icons.Outlined.Close, "Close session") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().background(Color(0xFF1E1E1E))) {
            if (sessions.size > 1) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                    sessions.forEach { s ->
                        FilterChip(
                            selected = s.id == activeId,
                            onClick = { activeId = s.id },
                            label = { Text(s.title) },
                        )
                    }
                }
            }
            val scroll = rememberScrollState()
            LaunchedEffect(tick, active?.id) { scroll.animateScrollTo(scroll.maxValue) }
            SelectionContainer(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(8.dp)) {
                Text(
                    text = remember(tick, active?.id) {
                        annotateAnsi(active?.output.orEmpty())
                    },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = Color(0xFFDCDCDC),
                )
            }
            ExtraKeysRow(
                onEsc = { active?.write("\u001B") },
                onTab = { active?.write("\t") },
                onCtrl = { c -> active?.sendControl((c.lowercaseChar().code - 96).toChar()) },
                onArrow = { seq -> active?.write(seq) },
            )
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("Command") },
                )
                TextButton(onClick = {
                    val line = input
                    input = ""
                    active?.write(line)
                    tick++
                }) { Text("Send") }
            }
        }
    }
}

@Composable
private fun ExtraKeysRow(
    onEsc: () -> Unit,
    onTab: () -> Unit,
    onCtrl: (Char) -> Unit,
    onArrow: (String) -> Unit,
) {
    var ctrl by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TextButton(onClick = onEsc) { Text("Esc") }
        TextButton(onClick = onTab) { Text("Tab") }
        FilterChip(selected = ctrl, onClick = { ctrl = !ctrl }, label = { Text("Ctrl") })
        listOf('C', 'D', 'Z', 'L').forEach { c ->
            TextButton(onClick = {
                if (ctrl) onCtrl(c) else onCtrl(c)
                ctrl = false
            }) { Text(if (ctrl) "^$c" else c.toString()) }
        }
        TextButton(onClick = { onArrow("\u001B[A") }) { Text("↑") }
        TextButton(onClick = { onArrow("\u001B[B") }) { Text("↓") }
        TextButton(onClick = { onArrow("\u001B[D") }) { Text("←") }
        TextButton(onClick = { onArrow("\u001B[C") }) { Text("→") }
    }
}

private fun annotateAnsi(raw: String) = buildAnnotatedString {
    for (span in AnsiParser.parse(raw)) {
        val style = SpanStyle(
            color = span.fg?.let { AnsiFg.getOrElse(it) { Color(0xFFDCDCDC) } } ?: Color(0xFFDCDCDC),
            background = span.bg?.let { AnsiFg.getOrElse(it) { Color.Transparent } } ?: Color.Transparent,
            fontWeight = if (span.bold) FontWeight.Bold else FontWeight.Normal,
            fontFamily = FontFamily.Monospace,
        )
        withStyle(style) { append(span.text) }
    }
}
