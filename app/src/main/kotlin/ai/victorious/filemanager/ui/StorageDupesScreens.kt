package ai.victorious.filemanager.ui

import ai.victorious.filemanager.FileManagerApplication
import ai.victorious.filemanager.core.dupes.DupeOptions
import ai.victorious.filemanager.core.dupes.DupePhase
import ai.victorious.filemanager.core.dupes.DupeProgress
import ai.victorious.filemanager.core.dupes.DuplicateFinder
import ai.victorious.filemanager.core.dupes.DuplicateGroup
import ai.victorious.filemanager.core.dupes.HashCache
import ai.victorious.filemanager.core.storage.FolderBreakdown
import ai.victorious.filemanager.core.storage.ScanProgress
import ai.victorious.filemanager.core.storage.StorageAnalyzer
import ai.victorious.filemanager.core.storage.StorageScan
import ai.victorious.filemanager.core.storage.StorageTree
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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Full hashes from earlier duplicate scans, kept while the app is running so re-scans are quick. */
internal object DupeHashCache {
    val cache = HashCache()
}

/** Theme-driven chart colours (fleet cyan / magenta / violet on dark), then dimmer copies. */
@Composable
private fun chartPalette(): List<Color> {
    val c = MaterialTheme.colorScheme
    return listOf(c.primary, c.secondary, c.tertiary, c.primary.copy(alpha = 0.55f), c.secondary.copy(alpha = 0.55f), c.tertiary.copy(alpha = 0.55f))
}

private enum class StorageTab(val label: String) { FOLDERS("Folders"), LARGEST("Largest files"), TYPES("By type") }

/**
 * Storage analyzer: one scan of [root] (with live progress and cancel), then instant drill-down,
 * a "largest files" list with show-in-folder / trash, and a by-type breakdown.
 */
@Composable
fun StorageScreen(root: File, onClose: () -> Unit, onEvent: (UiEvent) -> Unit) {
    val app = LocalContext.current.applicationContext as FileManagerApplication
    val scope = rememberCoroutineScope()
    val stack = remember { mutableStateListOf(root) }
    val current = stack.last()
    var tree by remember { mutableStateOf<StorageTree?>(null) }
    var progress by remember { mutableStateOf<ScanProgress?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var rescan by remember { mutableIntStateOf(0) }
    var tab by remember { mutableStateOf(StorageTab.FOLDERS) }
    var breakdown by remember { mutableStateOf<FolderBreakdown?>(null) }
    val trashed = remember { mutableStateListOf<String>() }
    LaunchedEffect(root, rescan) {
        tree = null; error = null; progress = null; trashed.clear()
        val ctx = currentCoroutineContext()
        withContext(Dispatchers.IO) {
            runCatching { StorageScan.scan(root, topFiles = 100, isCancelled = { !ctx.isActive }, onProgress = { progress = it }) }
        }.onSuccess { tree = it }.onFailure { if (it !is CancellationException) error = it.message ?: "Cannot analyze" }
    }
    LaunchedEffect(tree, current) {
        val t = tree ?: return@LaunchedEffect
        breakdown = null
        breakdown = withContext(Dispatchers.IO) {
            runCatching { StorageScan.breakdown(t, current) ?: StorageAnalyzer.analyze(current) }.getOrNull()
        }
    }
    val back = { if (stack.size > 1) stack.removeAt(stack.lastIndex) else onClose() }
    BackHandler(onBack = { back() })
    val palette = chartPalette()
    ToolScaffold(
        "Storage · ${current.name.ifEmpty { current.path }}",
        onBack = { back() },
        actions = { IconButton(enabled = tree != null || error != null, onClick = { rescan++ }) { Icon(Icons.Outlined.Refresh, "Scan again") } },
    ) { modifier ->
        val t = tree
        if (t == null) {
            Column(modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (error != null) {
                    Text(error!!)
                } else {
                    Text("Scanning ${root.path}…", style = MaterialTheme.typography.titleSmall)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    progress?.let { p ->
                        Text(
                            "${p.files} files · ${p.folders} folders · ${StorageAnalyzer.human(p.bytes)}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(p.currentDir.removePrefix(root.path).ifEmpty { "/" }, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = onClose) { Text("Cancel") }
                }
            }
            return@ToolScaffold
        }
        Column(modifier) {
            Text(
                "${StorageAnalyzer.human(t.root.bytes)} in ${t.root.files} files · ${t.folders} folders · " +
                    "scanned in ${"%.1f".format(t.elapsedMs / 1000.0)} s" + if (t.skipped > 0) " · ${t.skipped} unreadable skipped" else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StorageTab.entries.forEach { s -> FilterChip(tab == s, { tab = s }, label = { Text(s.label) }) }
            }
            when (tab) {
                StorageTab.FOLDERS -> FolderBreakdownList(breakdown, palette) { stack.add(it) }
                StorageTab.LARGEST -> LazyColumn(Modifier.fillMaxSize()) {
                    items(t.largest.filter { it.path !in trashed }, key = { it.path }) { f ->
                        val file = File(f.path)
                        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Outlined.InsertDriveFile, null)
                            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                                Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${StorageAnalyzer.human(f.bytes)} · ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(f.modified))} · " +
                                        (file.parent ?: "").removePrefix(root.path).ifEmpty { "/" },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(onClick = {
                                onEvent(UiEvent.CloseTool)
                                onEvent(UiEvent.OpenBookmark(file.parent ?: root.path))
                            }) { Icon(Icons.Outlined.FolderOpen, "Show in folder") }
                            IconButton(onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { runCatching { app.trash.moveToTrash(f.path) } }
                                        .onSuccess { trashed += f.path; onEvent(UiEvent.ShowMessage("Moved ${file.name} to trash")) }
                                        .onFailure { onEvent(UiEvent.ShowMessage("Trash failed: ${it.message}")) }
                                }
                            }) { Icon(Icons.Outlined.Delete, "Move to trash") }
                        }
                    }
                }
                StorageTab.TYPES -> {
                    val cats = t.categoriesBySize()
                    val total = t.root.bytes.coerceAtLeast(1)
                    LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
                        items(cats, key = { it.first?.name ?: "other" }) { (cat, sum) ->
                            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(cat?.label ?: "Other", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                    Text("${StorageAnalyzer.human(sum.bytes)} · ${sum.files} files", style = MaterialTheme.typography.bodySmall)
                                }
                                LinearProgressIndicator(progress = { sum.bytes.toFloat() / total }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderBreakdownList(r: FolderBreakdown?, palette: List<Color>, onOpen: (File) -> Unit) {
    if (r == null) { LinearProgressIndicator(Modifier.fillMaxWidth().padding(12.dp)); return }
    Column {
        Text("Total ${StorageAnalyzer.human(r.total)} · ${r.entries.size} item(s)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(12.dp))
        val rest = MaterialTheme.colorScheme.outlineVariant
        Row(Modifier.fillMaxWidth().height(18.dp).padding(horizontal = 12.dp)) {
            r.entries.take(palette.size).forEachIndexed { i, e ->
                val f = r.fraction(e)
                if (f > 0.005f) Box(Modifier.weight(f).fillMaxHeight().background(palette[i]))
            }
            val remainder = 1f - r.entries.take(palette.size).sumOf { r.fraction(it).toDouble() }.toFloat()
            if (remainder > 0.005f) Box(Modifier.weight(remainder).fillMaxHeight().background(rest))
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(r.entries.size, key = { r.entries[it].file.path }) { i ->
                val e = r.entries[i]
                Column(
                    Modifier.fillMaxWidth().clickable(enabled = e.isDirectory) { onOpen(e.file) }.padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.width(10.dp).height(10.dp).background(if (i < palette.size) palette[i] else rest))
                        Icon(if (e.isDirectory) Icons.Outlined.Folder else Icons.AutoMirrored.Outlined.InsertDriveFile, contentDescription = null)
                        Text(e.file.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(StorageAnalyzer.human(e.bytes), style = MaterialTheme.typography.bodySmall)
                    }
                    LinearProgressIndicator(progress = { r.fraction(e) }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    if (e.isDirectory) Text("${e.fileCount} file(s) · ${"%.1f".format(r.fraction(e) * 100)}%", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private enum class KeepRule(val label: String) { SHORTEST("Keep shortest path"), NEWEST("Keep newest"), OLDEST("Keep oldest") }

private val MIN_SIZES = listOf(1L to "Any size", 100L * 1024 to "≥ 100 KB", 1L shl 20 to "≥ 1 MB", 10L shl 20 to "≥ 10 MB")

private fun phaseText(p: DupeProgress): String = when (p.phase) {
    DupePhase.LISTING -> "Listing files… ${p.filesSeen}"
    DupePhase.QUICK_CHECK -> "Quick check ${p.done}/${p.total} same-size files"
    DupePhase.FULL_CHECK -> "Full check ${p.done}/${p.total} likely copies"
    DupePhase.DONE -> "Done"
} + if (p.bytesRead > 0) " · read ${StorageAnalyzer.human(p.bytesRead)}" else ""

/**
 * Duplicate finder with live progress, cancel (leaving the screen stops it), size / hidden-folder
 * filters, keep-rules, and an in-memory hash cache so a second scan only re-reads changed files.
 */
@Composable
fun DuplicatesScreen(root: File, onClose: () -> Unit, onEvent: (UiEvent) -> Unit) {
    val app = LocalContext.current.applicationContext as FileManagerApplication
    val scope = rememberCoroutineScope()
    var groups by remember { mutableStateOf<List<DuplicateGroup>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<DupeProgress?>(null) }
    val selected = remember { mutableStateListOf<File>() }
    var busy by remember { mutableStateOf(false) }
    var minSize by remember { mutableLongStateOf(1L) }
    var skipHidden by remember { mutableStateOf(true) }
    var keep by remember { mutableStateOf(KeepRule.SHORTEST) }
    var rescan by remember { mutableIntStateOf(0) }
    var elapsed by remember { mutableLongStateOf(0L) }
    fun applyKeep(g: List<DuplicateGroup>) {
        selected.clear()
        selected.addAll(
            when (keep) {
                KeepRule.SHORTEST -> DuplicateFinder.defaultSelection(g)
                KeepRule.NEWEST -> DuplicateFinder.keepNewestSelection(g)
                KeepRule.OLDEST -> DuplicateFinder.keepOldestSelection(g)
            },
        )
    }
    LaunchedEffect(root, minSize, skipHidden, rescan) {
        groups = null; error = null; progress = null
        val ctx = currentCoroutineContext()
        val started = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            runCatching {
                DuplicateFinder.scan(root, DupeOptions(minSize = minSize, skipHidden = skipHidden), DupeHashCache.cache, { !ctx.isActive }) { progress = it }
            }
        }.onSuccess { groups = it; elapsed = System.currentTimeMillis() - started; applyKeep(it) }
            .onFailure { if (it !is CancellationException) error = it.message ?: "Scan failed" }
    }
    ToolScaffold("Duplicates · ${root.name.ifEmpty { root.path }}", onClose, actions = {
        IconButton(enabled = groups != null && !busy, onClick = { rescan++ }) { Icon(Icons.Outlined.Refresh, "Scan again") }
        TextButton(enabled = selected.isNotEmpty() && !busy, onClick = {
            busy = true
            val toTrash = selected.toList()
            scope.launch {
                val moved = withContext(Dispatchers.IO) { toTrash.filter { runCatching { app.trash.moveToTrash(it.path) }.isSuccess } }
                groups = groups?.map { g -> g.copy(files = g.files - moved.toSet()) }?.filter { it.files.size > 1 }
                selected.removeAll(moved.toSet())
                busy = false
                onEvent(UiEvent.ShowMessage("Moved ${moved.size} file(s) to trash" + if (moved.size < toTrash.size) " (${toTrash.size - moved.size} failed)" else ""))
                onEvent(UiEvent.Refresh)
            }
        }) { Text("Trash ${selected.size}") }
    }) { modifier ->
        Column(modifier) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MIN_SIZES.forEach { (v, label) -> FilterChip(minSize == v, { minSize = v }, label = { Text(label) }) }
                FilterChip(!skipHidden, { skipHidden = !skipHidden }, label = { Text("Include hidden folders") })
            }
            val g = groups
            when {
                g == null && error != null -> Text(error!!, Modifier.padding(24.dp))
                g == null -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val p = progress
                    if (p != null && p.total > 0) LinearProgressIndicator(progress = { p.done.toFloat() / p.total }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(p?.let(::phaseText) ?: "Starting…", style = MaterialTheme.typography.bodyMedium)
                    Text("Only files with the same size are read, and big files are first compared by their start and end.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = onClose) { Text("Cancel") }
                }
                g.isEmpty() -> Text("No duplicates found (${"%.1f".format(elapsed / 1000.0)} s).", Modifier.padding(24.dp))
                else -> {
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        KeepRule.entries.forEach { k -> FilterChip(keep == k, { keep = k; applyKeep(g) }, label = { Text(k.label) }) }
                        FilterChip(false, { selected.clear() }, label = { Text("Select none") })
                    }
                    Text(
                        "${g.size} group(s) · ${StorageAnalyzer.human(g.sumOf { it.wasted })} reclaimable · " +
                            "selected ${StorageAnalyzer.human(selected.sumOf { it.length() })} · ${"%.1f".format(elapsed / 1000.0)} s",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    LazyColumn(Modifier.fillMaxSize()) {
                        g.forEach { group ->
                            item(key = "h" + group.hash + group.files.first().path) {
                                Text("${group.files.size} × ${StorageAnalyzer.human(group.size)}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 12.dp, top = 12.dp))
                            }
                            items(group.files, key = { group.hash + it.path }) { f ->
                                Row(
                                    Modifier.fillMaxWidth().clickable { if (f in selected) selected.remove(f) else selected.add(f) }.padding(horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(checked = f in selected, onCheckedChange = { if (it) selected.add(f) else selected.remove(f) })
                                    Column(Modifier.weight(1f)) {
                                        Text(f.path.removePrefix(root.path).trimStart('/'), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(f.lastModified())), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            item(key = "d" + group.hash + group.files.first().path) { HorizontalDivider(Modifier.padding(top = 4.dp)) }
                        }
                    }
                }
            }
        }
    }
}
