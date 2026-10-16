package ai.victorious.filemanager.ui

import ai.victorious.filemanager.FileManagerApplication
import ai.victorious.filemanager.core.diff.DiffLine
import ai.victorious.filemanager.core.diff.DiffOp
import ai.victorious.filemanager.core.diff.LineDiff
import ai.victorious.filemanager.core.dupes.DuplicateFinder
import ai.victorious.filemanager.core.dupes.DuplicateGroup
import ai.victorious.filemanager.core.storage.FolderBreakdown
import ai.victorious.filemanager.core.storage.StorageAnalyzer
import ai.victorious.filemanager.core.text.TextFiles
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Compare
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ToolRequest {
    data class Compare(val left: String, val right: String) : ToolRequest
    data class Duplicates(val root: String) : ToolRequest
    data class Storage(val root: String) : ToolRequest
}

@Composable
fun ToolsPane(state: UiState, onEvent: (UiEvent) -> Unit) {
    val pane = if (state.focusedPane == 0) state.left else state.right
    val dir = pane.active.path
    val files = selectedFiles(state)
    var askZipPassword by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                "Tools act on the focused pane: $dir",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            ToolItem(Icons.Outlined.Compare, "Compare two files", "Select exactly 2 text files (${files.size} selected)") {
                if (files.size == 2) onEvent(UiEvent.OpenTool(ToolRequest.Compare(files[0].path, files[1].path)))
                else onEvent(UiEvent.ShowMessage("Select exactly 2 files in Files, then come back"))
            }
        }
        item {
            ToolItem(Icons.Outlined.ContentCopy, "Find duplicates", "Scan this folder recursively") {
                onEvent(UiEvent.OpenTool(ToolRequest.Duplicates(dir)))
            }
        }
        item {
            ToolItem(Icons.Outlined.PieChart, "Storage analyzer", "Folder size breakdown") {
                onEvent(UiEvent.OpenTool(ToolRequest.Storage(dir)))
            }
        }
        item {
            ToolItem(Icons.Outlined.Lock, "Create password ZIP", "AES-256 from selection (${pane.selected.size} selected)") {
                if (pane.selected.isEmpty()) onEvent(UiEvent.ShowMessage("Select files to zip"))
                else askZipPassword = true
            }
        }
    }
    if (askZipPassword) {
        PasswordDialog(
            title = "Encrypt ZIP",
            confirmLabel = "Create",
            onConfirm = { askZipPassword = false; onEvent(UiEvent.ZipSelectedEncrypted(it)) },
            onDismiss = { askZipPassword = false },
        )
    }
}

@Composable
private fun ToolItem(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
fun ToolHost(request: ToolRequest, onClose: () -> Unit, onEvent: (UiEvent) -> Unit) {
    BackHandler(onBack = onClose)
    when (request) {
        is ToolRequest.Compare -> CompareScreen(File(request.left), File(request.right), onClose)
        is ToolRequest.Duplicates -> DuplicatesScreen(File(request.root), onClose, onEvent)
        is ToolRequest.Storage -> StorageScreen(File(request.root), onClose)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolScaffold(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}, content: @Composable (Modifier) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") } },
                actions = { actions() },
            )
        },
    ) { padding -> content(Modifier.padding(padding).fillMaxSize()) }
}

@Composable
private fun Loading(modifier: Modifier, error: String?) {
    Box(modifier, contentAlignment = Alignment.Center) {
        if (error == null) CircularProgressIndicator() else Text(error, Modifier.padding(24.dp))
    }
}

private val addBg = Color(0x3340C057)
private val delBg = Color(0x33E5484D)

@Composable
private fun CompareScreen(left: File, right: File, onClose: () -> Unit) {
    var diff by remember { mutableStateOf<List<DiffLine>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var sideBySide by remember { mutableStateOf(false) }
    LaunchedEffect(left, right) {
        withContext(Dispatchers.Default) { runCatching { LineDiff.diff(TextFiles.load(left), TextFiles.load(right)) } }
            .onSuccess { diff = it }.onFailure { error = it.message ?: "Cannot compare" }
    }
    ToolScaffold("${left.name} ↔ ${right.name}", onClose, actions = {
        FilterChip(sideBySide, { sideBySide = !sideBySide }, label = { Text("Side by side") })
    }) { modifier ->
        val d = diff
        if (d == null) Loading(modifier, error) else Column(modifier) {
            val (adds, dels) = LineDiff.stats(d)
            Text(
                if (adds == 0 && dels == 0) "Files are identical" else "+$adds  −$dels",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            if (sideBySide) {
                val rows = remember(d) { LineDiff.sideBySide(d) }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(rows) { r ->
                        Row(Modifier.fillMaxWidth()) {
                            listOf(r.left to true, r.right to false).forEach { (l, isLeft) ->
                                val bg = when {
                                    l == null -> Color.Transparent
                                    l.op == DiffOp.DELETE -> delBg
                                    l.op == DiffOp.INSERT -> addBg
                                    else -> Color.Transparent
                                }
                                val num = if (isLeft) l?.oldLine else l?.newLine
                                Text(
                                    (num?.toString()?.padStart(4) ?: "    ") + " " + (l?.text ?: ""),
                                    style = mono, maxLines = 1, overflow = TextOverflow.Clip,
                                    modifier = Modifier.weight(1f).background(bg).padding(horizontal = 2.dp),
                                )
                            }
                        }
                    }
                }
            } else {
                val h = rememberScrollState()
                LazyColumn(Modifier.fillMaxSize().horizontalScroll(h)) {
                    items(d) { l ->
                        val (sign, bg) = when (l.op) {
                            DiffOp.EQUAL -> " " to Color.Transparent
                            DiffOp.DELETE -> "-" to delBg
                            DiffOp.INSERT -> "+" to addBg
                        }
                        Text(
                            "${l.oldLine?.toString()?.padStart(4) ?: "    "} ${l.newLine?.toString()?.padStart(4) ?: "    "} $sign ${l.text}",
                            style = mono, softWrap = false,
                            modifier = Modifier.background(bg).padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DuplicatesScreen(root: File, onClose: () -> Unit, onEvent: (UiEvent) -> Unit) {
    val app = LocalContext.current.applicationContext as FileManagerApplication
    val scope = rememberCoroutineScope()
    var groups by remember { mutableStateOf<List<DuplicateGroup>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val selected = remember { mutableStateListOf<File>() }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(root) {
        withContext(Dispatchers.IO) { runCatching { DuplicateFinder.scan(root) } }
            .onSuccess { groups = it; selected.clear(); selected.addAll(DuplicateFinder.defaultSelection(it)) }
            .onFailure { error = it.message ?: "Scan failed" }
    }
    ToolScaffold("Duplicates · ${root.name}", onClose, actions = {
        TextButton(enabled = selected.isNotEmpty() && !busy, onClick = {
            busy = true
            val toTrash = selected.toList()
            scope.launch {
                val moved = withContext(Dispatchers.IO) { toTrash.count { runCatching { app.trash.moveToTrash(it.path) }.isSuccess } }
                groups = groups?.map { g -> g.copy(files = g.files - toTrash.toSet()) }?.filter { it.files.size > 1 }
                selected.clear()
                busy = false
                onEvent(UiEvent.ShowMessage("Moved $moved file(s) to trash"))
                onEvent(UiEvent.Refresh)
            }
        }) { Text("Trash ${selected.size}") }
    }) { modifier ->
        val g = groups
        if (g == null) Loading(modifier, error) else if (g.isEmpty()) Loading(modifier, "No duplicates found") else Column(modifier) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                "${g.size} group(s) · ${StorageAnalyzer.human(g.sumOf { it.wasted })} reclaimable · " +
                    "selected ${StorageAnalyzer.human(selected.sumOf { it.length() })}",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            LazyColumn(Modifier.fillMaxSize()) {
                g.forEach { group ->
                    item(key = "h" + group.hash) {
                        Text(
                            "${group.files.size} × ${StorageAnalyzer.human(group.size)}",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(start = 12.dp, top = 12.dp),
                        )
                    }
                    items(group.files, key = { group.hash + it.path }) { f ->
                        Row(
                            Modifier.fillMaxWidth().clickable { if (f in selected) selected.remove(f) else selected.add(f) }.padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = f in selected, onCheckedChange = { if (it) selected.add(f) else selected.remove(f) })
                            Text(f.path.removePrefix(root.path).trimStart('/'), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    item(key = "d" + group.hash) { HorizontalDivider(Modifier.padding(top = 4.dp)) }
                }
            }
        }
    }
}

@Composable
private fun StorageScreen(root: File, onClose: () -> Unit) {
    val stack = remember { mutableStateListOf(root) }
    val current = stack.last()
    var result by remember { mutableStateOf<FolderBreakdown?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(current) {
        result = null; error = null
        withContext(Dispatchers.IO) { runCatching { StorageAnalyzer.analyze(current) } }
            .onSuccess { result = it }.onFailure { error = it.message ?: "Cannot analyze" }
    }
    val back = { if (stack.size > 1) stack.removeAt(stack.lastIndex) else onClose() }
    BackHandler(onBack = { back() })
    ToolScaffold("Storage · ${current.name.ifEmpty { current.path }}", onBack = { back() }) { modifier ->
        val r = result
        if (r == null) Loading(modifier, error) else Column(modifier) {
            Text(
                "Total ${StorageAnalyzer.human(r.total)} · ${r.entries.size} item(s)",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(12.dp),
            )
            // Stacked bar "chart" of the top entries.
            val palette = listOf(Color(0xFF4F8EF7), Color(0xFF34C38F), Color(0xFFF1B44C), Color(0xFFF46A6A), Color(0xFF9C6ADE), Color(0xFF50A5F1))
            Row(Modifier.fillMaxWidth().height(18.dp).padding(horizontal = 12.dp)) {
                r.entries.take(6).forEachIndexed { i, e ->
                    val f = r.fraction(e)
                    if (f > 0.005f) Box(Modifier.weight(f).fillMaxHeight().background(palette[i]))
                }
                val rest = 1f - r.entries.take(6).sumOf { r.fraction(it).toDouble() }.toFloat()
                if (rest > 0.005f) Box(Modifier.weight(rest).fillMaxHeight().background(Color.Gray))
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                items(r.entries, key = { it.file.path }) { e ->
                    val i = r.entries.indexOf(e)
                    Column(
                        Modifier.fillMaxWidth()
                            .clickable(enabled = e.isDirectory) { stack.add(e.file) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.width(10.dp).height(10.dp).background(if (i < 6) palette[i] else Color.Gray))
                            Icon(if (e.isDirectory) Icons.Outlined.Folder else Icons.Outlined.InsertDriveFile, contentDescription = null)
                            Text(e.file.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(StorageAnalyzer.human(e.bytes), style = MaterialTheme.typography.bodySmall)
                        }
                        LinearProgressIndicator(
                            progress = { r.fraction(e) },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                        if (e.isDirectory) Text(
                            "${e.fileCount} file(s) · ${"%.1f".format(r.fraction(e) * 100)}%",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}
