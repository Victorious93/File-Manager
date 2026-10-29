package ai.victorious.filemanager.ui

import ai.victorious.filemanager.FileManagerApplication
import ai.victorious.filemanager.core.diff.LineDiff
import ai.victorious.filemanager.core.palette.CommandPalette
import ai.victorious.filemanager.core.palette.PaletteCommand
import ai.victorious.filemanager.core.share.WifiQr
import ai.victorious.filemanager.core.snapshots.Snapshot
import ai.victorious.filemanager.core.snapshots.SnapshotStore
import ai.victorious.filemanager.core.tags.ColorTag
import ai.victorious.filemanager.core.tags.ColorTagStore
import ai.victorious.filemanager.core.tags.SmartCollections
import ai.victorious.filemanager.core.tags.SmartEntry
import ai.victorious.filemanager.core.tags.SmartRule
import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** App-wide Phase 18 stores, created lazily in app-private storage. */
object Phase18Stores {
    @Volatile private var tags: ColorTagStore? = null
    @Volatile private var snaps: SnapshotStore? = null
    fun tags(ctx: Context): ColorTagStore = tags ?: synchronized(this) {
        tags ?: ColorTagStore(File(ctx.filesDir, "color_tags.json").path).also { tags = it }
    }
    fun snapshots(ctx: Context): SnapshotStore = snaps ?: synchronized(this) {
        snaps ?: SnapshotStore(File(ctx.filesDir, "snapshots")).also { snaps = it }
    }
}

/** Fuzzy "type to run" list over every tool, with recently used commands first. */
@Composable
fun CommandPaletteScreen(selected: List<String>, dir: String, onClose: () -> Unit, onEvent: (UiEvent) -> Unit) {
    val prefs = LocalContext.current.getSharedPreferences("command_palette", 0)
    var recent by remember { mutableStateOf(prefs.getString("recent", "").orEmpty().split(',').filter { it.isNotBlank() }) }
    var q by remember { mutableStateOf("") }
    val actions: List<Pair<PaletteCommand, () -> Unit>> = remember(selected, dir) {
        fun tool(id: String, title: String, vararg kw: String, req: () -> ToolRequest) =
            PaletteCommand(id, title, kw.toList(), "Tools") to { onEvent(UiEvent.OpenTool(req())) }
        listOf(
            tool("help", "Help & manual", "docs", "guide") { ToolRequest.Help },
            PaletteCommand("terminal", "Terminal", listOf("shell", "su", "adb")) to { onEvent(UiEvent.OpenTerminal) },
            PaletteCommand("network", "Network connections", listOf("ftp", "sftp", "smb", "webdav")) to { onEvent(UiEvent.OpenNetwork) },
            PaletteCommand("wifi", "Wi-Fi file transfer", listOf("lan", "web server")) to { onEvent(UiEvent.OpenWifiTransfer) },
            tool("tags", "Colour tags & smart collections", "label", "color", "saved search") { ToolRequest.Organize(selected, dir) },
            tool("snapshots", "File version snapshots", "history", "versions", "restore") { ToolRequest.Snapshots(selected, dir) },
            tool("wifiqr", "Share Wi-Fi as QR code", "wlan", "password", "qr") { ToolRequest.WifiQrShare },
            tool("sync", "Folder sync", "mirror", "backup") { ToolRequest.FolderSync(dir) },
            tool("dupes", "Find duplicates", "same files") { ToolRequest.Duplicates(dir) },
            tool("storage", "Storage analyzer", "space", "size") { ToolRequest.Storage(dir) },
            tool("utils", "Utilities", "hash", "md5", "base64", "regex") { ToolRequest.Utilities(selected) },
            tool("scripts", "Scripts", "sh", "run") { ToolRequest.Scripts(selected, dir) },
            tool("plugins", "Plugins", "extensions") { ToolRequest.Plugins(selected, dir) },
            tool("secure", "Secure folder", "vault", "encrypt") { ToolRequest.SecureFolder(selected, dir) },
            tool("photo", "Photo tools", "exif", "resize") { ToolRequest.PhotoTools(selected, dir) },
            tool("extract", "Extract installed apps", "apk backup") { ToolRequest.ExtractApps(dir) },
        )
    }
    val byId = remember(actions) { actions.associateBy { it.first.id } }
    val ranked = remember(q, recent, actions) { CommandPalette.rank(q, actions.map { it.first }, recent) }
    fun run(c: PaletteCommand) {
        recent = CommandPalette.pushRecent(recent, c.id)
        prefs.edit().putString("recent", recent.joinToString(",")).apply()
        onClose()
        byId[c.id]?.second?.invoke()
    }
    ToolScaffold("Command palette", onClose) { modifier ->
        Column(modifier.padding(12.dp)) {
            OutlinedTextField(q, { q = it }, label = { Text("Type a command") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (ranked.isEmpty()) Text("No command matches “$q”.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            LazyColumn {
                items(ranked, key = { it.id }) { c ->
                    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { run(c) }.padding(12.dp)) {
                        Text(c.title, style = MaterialTheme.typography.titleSmall)
                        val sub = (if (c.id in recent) "Recent · " else "") + c.keywords.joinToString(" · ")
                        if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun TagDot(tag: ColorTag, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).padding(4.dp).background(Color(tag.argb), CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = tag.label + if (selected) " tag, on" else " tag, off" },
        contentAlignment = Alignment.Center,
    ) { if (selected) Text("✓", color = Color.White) }
}

/** Colour tags for the selection, browse by colour, and smart collections over the folder index. */
@Composable
fun OrganizeScreen(selected: List<String>, dir: String, onClose: () -> Unit, onEvent: (UiEvent) -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as FileManagerApplication
    val store = remember { Phase18Stores.tags(ctx) }
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    var tagsOfSel by remember { mutableStateOf<Map<String, Set<ColorTag>>>(emptyMap()) }
    var browse by remember { mutableStateOf<ColorTag?>(null) }
    var browseHits by remember { mutableStateOf<List<String>>(emptyList()) }
    var rule by remember { mutableStateOf<SmartRule?>(null) }
    var ruleHits by remember { mutableStateOf<List<SmartEntry>?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(version, selected) {
        tagsOfSel = withContext(Dispatchers.IO) { selected.associateWith { store.tagsOf(it) } }
    }
    LaunchedEffect(version, browse) {
        val t = browse ?: return@LaunchedEffect
        browseHits = withContext(Dispatchers.IO) { store.prune { File(it).exists() }; store.pathsWith(t) }
    }
    LaunchedEffect(rule) {
        val r = rule ?: return@LaunchedEffect
        busy = true; ruleHits = null
        ruleHits = withContext(Dispatchers.IO) {
            runCatching {
                if (app.index.needsBuild(dir)) app.index.build(dir)
                val snap = app.index.current() ?: return@runCatching emptyList()
                SmartCollections.evaluate(
                    r,
                    snap.entries.asSequence().map { SmartEntry(it.path, it.sizeBytes, it.modifiedEpochMs, it.isDirectory) },
                    { p -> store.tagsOf(p) }, System.currentTimeMillis(),
                )
            }.getOrElse { onEvent(UiEvent.ShowMessage("Smart collection failed: ${it.message}")); emptyList() }
        }
        busy = false
    }
    ToolScaffold("Colour tags & smart collections", onClose) { modifier ->
        Column(modifier.padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Tag the selection", style = MaterialTheme.typography.titleMedium)
            if (selected.isEmpty()) Text("Select files in Files, then reopen this tool to tag them.", style = MaterialTheme.typography.bodySmall)
            else {
                val common = ColorTag.entries.filter { t -> tagsOfSel.values.isNotEmpty() && tagsOfSel.values.all { t in it } }.toSet()
                Text("${selected.size} selected", style = MaterialTheme.typography.bodySmall)
                Row {
                    ColorTag.entries.forEach { t ->
                        TagDot(t, t in common) {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    selected.forEach { p -> store.set(p, if (t in common) store.tagsOf(p) - t else store.tagsOf(p) + t) }
                                }
                                version++
                            }
                        }
                    }
                }
            }
            Text("Browse by colour", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ColorTag.entries.forEach { t -> FilterChip(browse == t, { browse = if (browse == t) null else t }, label = { Text(t.label) }) }
            }
            if (browse != null) {
                if (browseHits.isEmpty()) Text("No files tagged ${browse!!.label} yet.", style = MaterialTheme.typography.bodySmall)
                browseHits.forEach { p -> PathRow(p) { onEvent(UiEvent.OpenSearchHit(p, File(p).isDirectory)); onClose() } }
            }
            Text("Smart collections (in $dir)", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SmartCollections.presets.forEach { r -> FilterChip(rule == r, { rule = r }, label = { Text(r.name) }) }
            }
            if (busy) Text("Scanning…", style = MaterialTheme.typography.bodySmall)
            ruleHits?.let { hits ->
                if (hits.isEmpty()) Text("Nothing matches “${rule?.name}” here.", style = MaterialTheme.typography.bodySmall)
                else Text("${hits.size} match${if (hits.size == 1) "" else "es"}", style = MaterialTheme.typography.bodySmall)
                hits.take(200).forEach { e -> PathRow(e.path) { onEvent(UiEvent.OpenSearchHit(e.path, false)); onClose() } }
            }
        }
    }
}

@Composable
private fun PathRow(path: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(8.dp)) {
        Text(path.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium)
        Text(path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** Keep, diff and restore earlier versions of a file. */
@Composable
fun SnapshotsScreen(selected: List<String>, onClose: () -> Unit, onEvent: (UiEvent) -> Unit) {
    val ctx = LocalContext.current
    val store = remember { Phase18Stores.snapshots(ctx) }
    val scope = rememberCoroutineScope()
    fun msg(s: String) = onEvent(UiEvent.ShowMessage(s))
    val files = selected.map(::File).filter { it.isFile }
    var target by remember { mutableStateOf(files.firstOrNull()?.absolutePath) }
    var list by remember { mutableStateOf<List<Snapshot>>(emptyList()) }
    var tracked by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var diffText by remember { mutableStateOf<String?>(null) }
    var version by remember { mutableIntStateOf(0) }
    LaunchedEffect(target, version) {
        withContext(Dispatchers.IO) {
            tracked = store.trackedFiles()
            list = target?.let { store.list(it) }.orEmpty()
        }
    }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    ToolScaffold("File version snapshots", onClose) { modifier ->
        Column(modifier.padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Snapshots are private copies kept in app storage (20 per file, identical versions stored once). Restoring first snapshots the current content, so it can be undone.",
                style = MaterialTheme.typography.bodySmall)
            val t = target
            if (t == null) Text("Select a file in Files to snapshot it, or pick a tracked file below.", style = MaterialTheme.typography.bodyMedium)
            else {
                Text(File(t).name, style = MaterialTheme.typography.titleMedium)
                Button(onClick = {
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { store.take(File(t)) } }
                        r.onSuccess { msg(if (it == null) "No change since the last snapshot" else "Snapshot saved") }
                            .onFailure { msg("Snapshot failed: ${it.message}") }
                        version++
                    }
                }) { Text("Take snapshot now") }
                if (list.isEmpty()) Text("No snapshots of this file yet.", style = MaterialTheme.typography.bodySmall)
                list.forEach { s ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text("${fmt.format(Date(s.takenAtMs))} · ${s.sizeBytes} bytes", style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                scope.launch {
                                    diffText = withContext(Dispatchers.IO) {
                                        runCatching {
                                            val cur = File(t)
                                            if (cur.length() > 2_000_000 || s.sizeBytes > 2_000_000) "Too large to diff here (2 MB limit)."
                                            else LineDiff.unified(LineDiff.diff(s.file.readText(), cur.readText())).ifBlank { "Identical to the current file." }
                                        }.getOrElse { "Cannot diff: ${it.message}" }
                                    }
                                }
                            }) { Text("Diff vs now") }
                            OutlinedButton(onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { runCatching { store.restore(s) } }
                                        .onSuccess { msg("Restored; previous content kept as a snapshot") }
                                        .onFailure { msg("Restore failed: ${it.message}") }
                                    version++
                                }
                            }) { Text("Restore") }
                            OutlinedButton(onClick = {
                                scope.launch { withContext(Dispatchers.IO) { store.delete(s) }; version++ }
                            }) { Text("Delete") }
                        }
                    }
                }
            }
            diffText?.let {
                Text("Diff (snapshot → current)", style = MaterialTheme.typography.titleSmall)
                Text(it.take(20_000), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            if (tracked.isNotEmpty()) {
                Text("Tracked files", style = MaterialTheme.typography.titleMedium)
                tracked.forEach { (p, n) ->
                    Text("${File(p).name} ($n)", Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { target = p; diffText = null }.padding(8.dp))
                }
            }
        }
    }
}

/** Build a Wi-Fi join QR code (scan with any phone camera). Nothing is stored or sent. */
@Composable
fun WifiQrScreen(onClose: () -> Unit) {
    var ssid by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var sec by remember { mutableStateOf(WifiQr.Security.WPA) }
    var hidden by remember { mutableStateOf(false) }
    var show by remember { mutableStateOf(false) }
    val result = remember(ssid, pass, sec, hidden) {
        runCatching { WifiQr.payload(WifiQr.Network(ssid, pass.ifEmpty { null }, sec, hidden)) }
    }
    val bitmap by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(null, result) {
        value = result.getOrNull()?.let { p ->
            withContext(Dispatchers.Default) { ai.victorious.filemanager.net.WifiTransferController.renderQr(p, 640) }
        }
    }
    ToolScaffold("Share Wi-Fi as QR", onClose) { modifier ->
        Column(modifier.padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(ssid, { ssid = it }, label = { Text("Network name (SSID)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                WifiQr.Security.entries.forEach { s -> FilterChip(sec == s, { sec = s }, label = { Text(if (s == WifiQr.Security.OPEN) "Open" else s.code) }) }
            }
            if (sec != WifiQr.Security.OPEN) {
                OutlinedTextField(pass, { pass = it }, label = { Text("Password") }, singleLine = true,
                    visualTransformation = if (show) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(show, { show = it }); Text("Show password") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(hidden, { hidden = it }); Text("Hidden network") }
            result.exceptionOrNull()?.let { if (ssid.isNotEmpty()) Text(it.message ?: "Invalid", color = MaterialTheme.colorScheme.error) }
            bitmap?.let {
                Image(it.asImageBitmap(), contentDescription = "Wi-Fi QR code for $ssid", modifier = Modifier.size(280.dp).align(Alignment.CenterHorizontally))
                Text("Point another phone's camera at this code to join $ssid.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
