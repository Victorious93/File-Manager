package ai.victorious.victorfiles.ui

import ai.victorious.victorfiles.FileManagerApplication
import ai.victorious.victorfiles.core.plugins.ActionTarget
import ai.victorious.victorfiles.core.plugins.PluginValidationException
import ai.victorious.victorfiles.core.plugins.SavedScript
import ai.victorious.victorfiles.core.secure.VaultEntry
import ai.victorious.victorfiles.plugins.InstalledPlugin
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun P9Scaffold(title: String, onBack: () -> Unit, content: @Composable (Modifier) -> Unit) {
    BackHandler(onBack = onBack)
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
        )
    }) { pad -> content(Modifier.padding(pad).fillMaxSize()) }
}

@Composable
private fun OutputLog(lines: List<String>) {
    if (lines.isEmpty()) return
    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        SelectionContainer {
            Text(
                lines.joinToString("\n"),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(8.dp).heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
            )
        }
    }
}

/** Plugins: install (from a selected .json or samples), review capabilities, enable, run, remove. */
@Composable
fun PluginsScreen(selected: List<String>, dir: String, onClose: () -> Unit, onEvent: (UiEvent) -> Unit) {
    val app = LocalContext.current.applicationContext as FileManagerApplication
    val repo = app.plugins
    val scope = rememberCoroutineScope()
    var plugins by remember { mutableStateOf(repo.list()) }
    val log = remember { mutableStateListOf<String>() }
    var running by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<InstalledPlugin?>(null) }
    fun reload() { plugins = repo.list() }
    fun msg(s: String) = onEvent(UiEvent.ShowMessage(s))

    P9Scaffold("Plugins", onClose) { modifier ->
        LazyColumn(modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(
                    "Declarative JSON plugins run shell command templates with your selection (${selected.size} file(s)) " +
                        "in $dir. No code is loaded into the app. New plugins start disabled.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Button(onClick = {
                        val json = selected.singleOrNull { it.endsWith(".json", ignoreCase = true) }
                        if (json == null) { msg("Select exactly one plugin .json in Files first"); return@Button }
                        runCatching { repo.installFromFile(json) }
                            .onSuccess { msg("Installed ${it.name} (disabled — review & enable)"); reload() }
                            .onFailure { msg(if (it is PluginValidationException) it.problems.joinToString("\n") else it.message ?: "Install failed") }
                    }) { Text("Install selected .json") }
                    OutlinedButton(onClick = {
                        runCatching { repo.installSamples() }
                            .onSuccess { msg("Installed ${it.size} sample plugin(s)"); reload() }
                            .onFailure { msg(it.message ?: "Failed") }
                    }) { Text("Add samples") }
                }
            }
            items(plugins, key = { it.manifest.id }) { p ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${p.manifest.name} ${p.manifest.version}", style = MaterialTheme.typography.titleSmall)
                                Text(p.manifest.id + if (p.manifest.author.isNotBlank()) " · ${p.manifest.author}" else "",
                                    style = MaterialTheme.typography.labelSmall)
                            }
                            Switch(checked = p.enabled, onCheckedChange = { repo.setEnabled(p.manifest.id, it); reload() })
                        }
                        if (p.manifest.description.isNotBlank()) Text(p.manifest.description, style = MaterialTheme.typography.bodySmall)
                        Text("Capabilities: " + p.manifest.capabilities.joinToString { it.name.lowercase() },
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (a in p.manifest.actions) {
                                AssistChip(
                                    enabled = p.enabled && !running,
                                    onClick = {
                                        if (a.target != ActionTarget.FOLDER && selected.isEmpty()) { msg("Select files first"); return@AssistChip }
                                        running = true
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                runCatching { repo.run(p, a, selected, dir) { line -> scope.launch(Dispatchers.Main) { log += line } } }
                                                    .onFailure { e -> withContext(Dispatchers.Main) { log += "error: ${e.message}" } }
                                            }
                                            running = false
                                        }
                                    },
                                    label = { Text(a.title) },
                                )
                            }
                        }
                        TextButton(onClick = { confirmRemove = p }) { Text("Remove") }
                    }
                }
            }
            item { OutputLog(log) }
        }
    }
    confirmRemove?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remove ${p.manifest.name}?") },
            confirmButton = { TextButton(onClick = { repo.remove(p.manifest.id); confirmRemove = null; reload() }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Cancel") } },
        )
    }
}

/** Saved scripts: per-file ($FILE/$NAME/$DIR) or once with "$@" = selection; output log. */
@Composable
fun ScriptsScreen(selected: List<String>, dir: String, onClose: () -> Unit) {
    val app = LocalContext.current.applicationContext as FileManagerApplication
    val repo = app.plugins
    val scope = rememberCoroutineScope()
    var scripts by remember { mutableStateOf(repo.scripts()) }
    var editing by remember { mutableStateOf<SavedScript?>(null) }
    val log = remember { mutableStateListOf<String>() }
    var running by remember { mutableStateOf(false) }

    P9Scaffold("Scripts", onClose) { modifier ->
        LazyColumn(modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("Runs with sh in $dir as this app (no root). ${selected.size} file(s) selected. " +
                    "Per-file scripts get \$FILE, \$NAME, \$DIR; folder scripts get the selection as \"\$@\".",
                    style = MaterialTheme.typography.bodySmall)
                Button(onClick = { editing = SavedScript("", "", "", true) }, modifier = Modifier.padding(top = 8.dp)) { Text("New script") }
            }
            items(scripts, key = { it.id }) { s ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(s.name, style = MaterialTheme.typography.titleSmall)
                        Text(s.body.lineSequence().first().take(80), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(enabled = !running, onClick = {
                                running = true
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        runCatching { repo.runScript(s, selected, dir) { line -> scope.launch(Dispatchers.Main) { log += line } } }
                                            .onFailure { e -> withContext(Dispatchers.Main) { log += "error: ${e.message}" } }
                                    }
                                    running = false
                                }
                            }) { Text(if (s.perFile) "Run on selection" else "Run") }
                            TextButton(onClick = { editing = s }) { Text("Edit") }
                            TextButton(onClick = { repo.deleteScript(s.id); scripts = repo.scripts() }) { Text("Delete") }
                        }
                    }
                }
            }
            item { OutputLog(log) }
        }
    }
    editing?.let { s ->
        var name by remember(s) { mutableStateOf(s.name) }
        var body by remember(s) { mutableStateOf(s.body) }
        var perFile by remember(s) { mutableStateOf(s.perFile) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (s.id.isEmpty()) "New script" else "Edit script") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(body, { body = it }, label = { Text("sh script") }, minLines = 4,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Run once per selected file", Modifier.weight(1f))
                        Switch(checked = perFile, onCheckedChange = { perFile = it })
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = body.isNotBlank(), onClick = {
                    repo.saveScript(name, body, perFile, s.id.ifEmpty { null })
                    scripts = repo.scripts(); editing = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}

private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}

/** Biometric-locked secure folder: move selected files in, list, move out to the focused folder. */
@Composable
fun SecureFolderScreen(selected: List<String>, dir: String, onClose: () -> Unit, onEvent: (UiEvent) -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as FileManagerApplication
    val secure = app.secureFolder
    val scope = rememberCoroutineScope()
    var unlocked by remember { mutableStateOf(secure.lock.isUnlocked) }
    var entries by remember { mutableStateOf<List<VaultEntry>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    fun msg(s: String) = onEvent(UiEvent.ShowMessage(s))
    fun refresh() {
        unlocked = secure.lock.isUnlocked
        if (!unlocked) { entries = emptyList(); return }
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { secure.list() } }
            r.onSuccess { entries = it }.onFailure { msg("Secure folder: ${it.message}"); secure.lock.lock(); unlocked = false }
        }
    }
    // Poll the lock so the screen hides content as soon as it times out.
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            if (unlocked && !secure.lock.isUnlocked) { unlocked = false; entries = emptyList() }
        }
    }
    fun work(label: String, block: () -> String) {
        busy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching(block) }
            busy = false
            r.onSuccess { msg(it); onEvent(UiEvent.Refresh) }.onFailure { msg("$label failed: ${it.message}") }
            refresh()
        }
    }

    P9Scaffold("Secure folder", onClose) { modifier ->
        Column(modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Files are AES-256-GCM encrypted with a Keystore key that needs your biometric or screen lock. " +
                "Stored in app-private storage, hidden from other apps and the media scanner.",
                style = MaterialTheme.typography.bodySmall)
            if (!unlocked) {
                secure.authStatusMessage()?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = {
                    val act = ctx.findFragmentActivity()
                    if (act == null) { msg("Cannot show the unlock prompt here"); return@Button }
                    secure.authenticate(act) { ok, err -> if (ok) refresh() else msg(err ?: "Not unlocked") }
                }) { Text("Unlock") }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy && selected.isNotEmpty(), onClick = {
                        work("Move in") { "Moved ${secure.moveIn(selected)} file(s) into the secure folder" }
                    }) { Text("Move ${selected.size} selected in") }
                    OutlinedButton(onClick = { secure.lock.lock(); refresh() }) { Text("Lock now") }
                }
                HorizontalDivider()
                if (entries.isEmpty()) Text("Empty", style = MaterialTheme.typography.bodySmall)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(entries, key = { it.id }) { e ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f)) {
                                Text(e.originalName, style = MaterialTheme.typography.bodyMedium)
                                Text("${e.sizeBytes} bytes", style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(enabled = !busy, onClick = {
                                work("Move out") { "Restored to " + secure.moveOut(e.id, dir).absolutePath }
                            }) { Text("Move out") }
                            TextButton(enabled = !busy, onClick = { work("Delete") { secure.delete(e.id); "Deleted ${e.originalName}" } }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
}
