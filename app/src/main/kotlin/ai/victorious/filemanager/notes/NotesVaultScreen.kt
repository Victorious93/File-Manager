package ai.victorious.filemanager.notes

import ai.victorious.filemanager.FileManagerApplication
import ai.victorious.filemanager.core.notes.BacklinkIndex
import ai.victorious.filemanager.core.notes.GraphEdge
import ai.victorious.filemanager.core.notes.GraphNode
import ai.victorious.filemanager.core.notes.MdBlock
import ai.victorious.filemanager.core.notes.MdSpan
import ai.victorious.filemanager.core.notes.MarkdownPreview
import ai.victorious.filemanager.core.notes.NoteGraph
import ai.victorious.filemanager.core.notes.NotePaths
import ai.victorious.filemanager.core.notes.Vault
import ai.victorious.filemanager.core.notes.VaultConfig
import ai.victorious.filemanager.core.notes.WikiLinkParser
import ai.victorious.filemanager.core.syntax.Language
import ai.victorious.filemanager.core.syntax.SyntaxHighlighter
import ai.victorious.filemanager.core.syntax.TokenType
import ai.victorious.filemanager.ui.UiEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.net.Uri
import android.content.Intent
import ai.victorious.filemanager.core.notes.NoteIo
import ai.victorious.filemanager.core.notes.ForceDirectedLayout
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private enum class NotesPane { EDITOR, PREVIEW, SPLIT, GRAPH, SEARCH }

/**
 * Obsidian-style notes vault UI. Written from scratch — no Obsidian source.
 * Any folder can be a vault. Wikilinks, backlinks, tags, graph, daily notes, templates.
 */
@Composable
fun NotesVaultPane(onEvent: (UiEvent) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as FileManagerApplication
    val prefs = remember { context.getSharedPreferences("notes_vault", 0) }
    var vaultRoot by remember { mutableStateOf(prefs.getString("root", null)) }
    var vaultTreeUri by remember { mutableStateOf(prefs.getString("tree_uri", null)) }
    var vault by remember { mutableStateOf<Vault?>(null) }
    fun noteIo(): NoteIo {
        val root = vaultRoot
        val tree = vaultTreeUri
        return when {
            root != null && root.startsWith("content:") && tree != null -> SafNoteIo(context, Uri.parse(tree))
            tree != null && (root == null || root.startsWith("content:")) -> SafNoteIo(context, Uri.parse(tree))
            else -> FileNoteIo()
        }
    }
    var currentPath by remember { mutableStateOf<String?>(null) }
    var body by remember { mutableStateOf("") }
    var dirty by remember { mutableStateOf(false) }
    var pane by remember { mutableStateOf(NotesPane.SPLIT) }
    var showSwitcher by remember { mutableStateOf(false) }
    var showNew by remember { mutableStateOf(false) }
    var searchQ by remember { mutableStateOf("") }
    var searchHits by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    val scope = rememberCoroutineScope()

    fun openVault(root: String, treeUri: String? = null) {
        vaultRoot = root
        vaultTreeUri = treeUri
        prefs.edit().putString("root", root).apply()
        if (treeUri != null) prefs.edit().putString("tree_uri", treeUri).apply()
        else prefs.edit().remove("tree_uri").apply()
        scope.launch {
            val v = withContext(Dispatchers.IO) {
                Vault(noteIo(), VaultConfig(root)).also { it.rebuildIndex() }
            }
            vault = v
            currentPath = null
            body = ""
            dirty = false
            onEvent(UiEvent.ShowMessage("Vault: $root (${v.index.noteCount()} notes)"))
        }
    }

    fun openNote(path: String) {
        val v = vault ?: return
        if (dirty && currentPath != null) {
            v.save(currentPath!!, body)
            dirty = false
        }
        scope.launch {
            val text = withContext(Dispatchers.IO) { v.open(path) }
            currentPath = path
            body = text
            dirty = false
        }
    }

    fun save() {
        val v = vault ?: return
        val p = currentPath ?: return
        scope.launch {
            withContext(Dispatchers.IO) { v.save(p, body) }
            dirty = false
            onEvent(UiEvent.ShowMessage("Saved ${NotePaths.titleOf(p)}"))
        }
    }

    if (vaultRoot == null || vault == null) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Notes vault", style = MaterialTheme.typography.titleLarge)
            Text("Pick any folder as a vault. Markdown files become notes with [[wikilinks]], #tags, backlinks and a graph.", style = MaterialTheme.typography.bodyMedium)
            val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
                if (uri == null) return@rememberLauncherForActivityResult
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
                val path = SafTreePaths.primaryPath(uri)
                if (path != null) {
                    File(path).mkdirs()
                    File(path, "Templates").mkdirs()
                    File(path, "Daily").mkdirs()
                    openVault(path, treeUri = uri.toString())
                } else {
                    // Pure SAF vault — root is the tree URI; paths are relative.
                    openVault(uri.toString(), treeUri = uri.toString())
                }
                onEvent(UiEvent.ShowMessage("Vault folder granted"))
            }
            TextButton(onClick = { treePicker.launch(null) }) { Text("Pick folder (Storage Access Framework)") }
            val candidates = listOf(
                File(app.getExternalFilesDir(null), "Notes").absolutePath,
                File(app.filesDir, "Notes").absolutePath,
                "/storage/emulated/0/Documents/Notes",
                "/storage/emulated/0/Notes",
            )
            candidates.forEach { path ->
                TextButton(onClick = {
                    File(path).mkdirs()
                    File(path, "Templates").mkdirs()
                    File(path, "Daily").mkdirs()
                    if (!File(path, "Welcome.md").exists()) {
                        File(path, "Welcome.md").writeText(
                            "---\ntitle: Welcome\n---\n\n# Welcome\n\nThis is your vault. Create notes with [[wikilinks]] and #tags.\n\nTry the daily note or the graph view.\n",
                        )
                    }
                    openVault(path)
                }) { Text("Use $path") }
            }
            var custom by remember { mutableStateOf("") }
            OutlinedTextField(custom, { custom = it }, label = { Text("Or paste a folder path") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            TextButton(enabled = custom.isNotBlank(), onClick = {
                File(custom.trim()).mkdirs(); openVault(custom.trim())
            }) { Text("Open path") }
        }
        return
    }

    val v = vault!!
    val path = currentPath
    val links = remember(body) { WikiLinkParser.links(body) }
    val tags = remember(body) { WikiLinkParser.tags(body) }
    val backlinks = remember(path, body, dirty) { path?.let { v.index.backlinks(it) }.orEmpty() }
    val outgoing = remember(path, body) { path?.let { v.index.outgoing(it) }.orEmpty() }

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Vault", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { showSwitcher = true }) { Text("Quick switch") }
            TextButton(onClick = { showNew = true }) { Text("New") }
            TextButton(onClick = {
                scope.launch {
                    val p = withContext(Dispatchers.IO) { v.ensureDaily() }
                    openNote(p)
                }
            }) { Text("Daily") }
            TextButton(enabled = dirty, onClick = { save() }) { Text(if (dirty) "Save*" else "Saved") }
            TextButton(onClick = { vaultRoot = null; vaultTreeUri = null; vault = null; prefs.edit().remove("root").remove("tree_uri").apply() }) { Text("Change") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            NotesPane.entries.forEach { p ->
                FilterChip(pane == p, { pane = p }, label = { Text(p.name.lowercase()) })
            }
        }
        when (pane) {
            NotesPane.SEARCH -> {
                OutlinedTextField(searchQ, {
                    searchQ = it
                    searchHits = v.search(it)
                    // Also use FileIndex if available for the vault root
                    runCatching {
                        val extra = app.index.search(it).filter { h ->
                            h.entry.path.startsWith(v.config.root) && h.entry.path.endsWith(".md", true)
                        }
                        if (extra.isNotEmpty()) {
                            searchHits = (searchHits + extra.map { h -> h.entry.path to h.relativePath }).distinctBy { it.first }
                        }
                    }
                }, label = { Text("Search notes") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                LazyColumn {
                    items(searchHits) { (p, snip) ->
                        Column(Modifier.fillMaxWidth().clickable { openNote(p); pane = NotesPane.SPLIT }.padding(8.dp)) {
                            Text(NotePaths.titleOf(p), style = MaterialTheme.typography.titleSmall)
                            Text(snip, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        }
                    }
                }
            }
            NotesPane.GRAPH -> {
                val tagCounts = remember(v.index.noteCount()) {
                    v.index.allTitles().associate { t ->
                        val p = v.index.pathForTitle(t)!!
                        p to WikiLinkParser.tags(runCatching { v.open(p) }.getOrDefault("")).size
                    }
                }
                val (nodes, edges) = remember(v.index.noteCount()) { NoteGraph.build(v.index, tagCounts) }
                GraphCanvas(nodes, edges, onOpen = { openNote(it); pane = NotesPane.SPLIT })
            }
            else -> {
                Row(Modifier.weight(1f)) {
                    // Note list
                    LazyColumn(Modifier.width(160.dp).fillMaxHeight()) {
                        items(v.index.allTitles()) { title ->
                            val p = v.index.pathForTitle(title)!!
                            Text(
                                title,
                                Modifier.fillMaxWidth().clickable { openNote(p) }
                                    .background(if (p == path) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                    .padding(8.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                            )
                        }
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Text(path?.let { NotePaths.titleOf(it) } ?: "No note open", style = MaterialTheme.typography.titleSmall)
                        when (pane) {
                            NotesPane.EDITOR -> EditorField(body, links.map { it.target }, v.index) { body = it; dirty = true }
                            NotesPane.PREVIEW -> PreviewPane(body, onWiki = { t ->
                                v.index.pathForTitle(t)?.let { openNote(it) }
                                    ?: onEvent(UiEvent.ShowMessage("Missing note: $t"))
                            })
                            NotesPane.SPLIT -> Row(Modifier.weight(1f)) {
                                Box(Modifier.weight(1f)) { EditorField(body, links.map { it.target }, v.index) { body = it; dirty = true } }
                                Box(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                                    PreviewPane(body, onWiki = { t ->
                                        v.index.pathForTitle(t)?.let { openNote(it) }
                                            ?: onEvent(UiEvent.ShowMessage("Missing note: $t"))
                                    })
                                }
                            }
                            else -> Unit
                        }
                        // Backlinks / outgoing / tags
                        Row(Modifier.fillMaxWidth().height(120.dp)) {
                            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(4.dp)) {
                                Text("Backlinks", style = MaterialTheme.typography.labelLarge)
                                if (backlinks.isEmpty()) Text("(none)", style = MaterialTheme.typography.bodySmall)
                                backlinks.forEach { e ->
                                    Text(NotePaths.titleOf(e.fromPath), Modifier.clickable { openNote(e.fromPath) }, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(4.dp)) {
                                Text("Outgoing", style = MaterialTheme.typography.labelLarge)
                                outgoing.forEach { e ->
                                    val label = e.toTarget + if (e.toPath == null) " ?" else ""
                                    Text(label, Modifier.clickable {
                                        e.toPath?.let { openNote(it) } ?: run {
                                            val created = v.newNote(e.toTarget)
                                            openNote(created)
                                        }
                                    }, style = MaterialTheme.typography.bodySmall)
                                }
                                Text("Tags", style = MaterialTheme.typography.labelLarge)
                                Text(tags.joinToString { "#${it.name}" }.ifBlank { "(none)" }, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSwitcher) {
        var q by remember { mutableStateOf("") }
        val suggestions = remember(q) { v.index.suggest(q) }
        AlertDialog(
            onDismissRequest = { showSwitcher = false },
            title = { Text("Quick switcher") },
            text = {
                Column {
                    OutlinedTextField(q, { q = it }, label = { Text("Note title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    LazyColumn(Modifier.height(240.dp)) {
                        items(suggestions) { title ->
                            Text(title, Modifier.fillMaxWidth().clickable {
                                v.index.pathForTitle(title)?.let { openNote(it) }
                                showSwitcher = false
                            }.padding(8.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSwitcher = false }) { Text("Close") } },
        )
    }
    if (showNew) {
        var title by remember { mutableStateOf("") }
        var template by remember { mutableStateOf<String?>(null) }
        val templates = remember { listOf(null as String?) + v.templates().map { NotePaths.titleOf(it) } }
        AlertDialog(
            onDismissRequest = { showNew = false },
            title = { Text("New note") },
            text = {
                Column {
                    OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true)
                    Text("Template", style = MaterialTheme.typography.labelLarge)
                    templates.forEach { t ->
                        FilterChip(template == t, { template = t }, label = { Text(t ?: "(none)") })
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = title.isNotBlank(), onClick = {
                    val p = v.newNote(title.trim(), template)
                    openNote(p); showNew = false
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showNew = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EditorField(body: String, linkTargets: List<String>, index: BacklinkIndex, onChange: (String) -> Unit) {
    var autocomplete by remember { mutableStateOf<List<String>>(emptyList()) }
    Column {
        BasicTextField(
            value = body,
            onValueChange = { text ->
                onChange(text)
                // Detect [[ partial at end of current "word"
                val open = text.lastIndexOf("[[")
                val close = text.lastIndexOf("]]")
                autocomplete = if (open > close && open >= 0) {
                    val partial = text.substring(open + 2).substringBefore(']').substringBefore('|').substringBefore('#')
                    index.suggest(partial)
                } else emptyList()
            },
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth().weight(1f, fill = true).padding(4.dp),
        )
        if (autocomplete.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                autocomplete.take(6).forEach { s ->
                    FilterChip(false, {
                        val open = body.lastIndexOf("[[")
                        if (open >= 0) {
                            val before = body.substring(0, open + 2)
                            val after = body.substring(open + 2).let { rest ->
                                val end = rest.indexOfFirst { it == ']' || it == '|' || it == '#' }.let { if (it < 0) rest.length else it }
                                rest.substring(end)
                            }
                            onChange("$before$s]]$after")
                            autocomplete = emptyList()
                        }
                    }, label = { Text(s) })
                }
            }
        }
    }
}

@Composable
private fun PreviewPane(body: String, onWiki: (String) -> Unit) {
    val blocks = remember(body) { MarkdownPreview.parse(body) }
    Column(Modifier.padding(8.dp)) {
        blocks.forEach { b ->
            when (b) {
                is MdBlock.Heading -> Text(b.text, style = when (b.level) {
                    1 -> MaterialTheme.typography.headlineMedium
                    2 -> MaterialTheme.typography.headlineSmall
                    3 -> MaterialTheme.typography.titleLarge
                    else -> MaterialTheme.typography.titleMedium
                }, modifier = Modifier.padding(vertical = 4.dp))
                is MdBlock.Paragraph -> InlineSpans(b.spans, onWiki)
                is MdBlock.Bullet -> Row { Text("• "); InlineSpans(b.spans, onWiki) }
                is MdBlock.Numbered -> Row { Text("${b.n}. "); InlineSpans(b.spans, onWiki) }
                is MdBlock.Quote -> Row(Modifier.padding(start = 8.dp)) { Text("│ "); InlineSpans(b.spans, onWiki) }
                is MdBlock.Rule -> Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline).padding(vertical = 8.dp))
                is MdBlock.Code -> CodeBlock(b.language, b.code)
            }
        }
    }
}

@Composable
private fun InlineSpans(spans: List<MdSpan>, onWiki: (String) -> Unit) {
    val annotated = buildAnnotatedString {
        spans.forEach { s ->
            when (s) {
                is MdSpan.Text -> append(s.text)
                is MdSpan.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.text) }
                is MdSpan.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(s.text) }
                is MdSpan.Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x22000000))) { append(s.text) }
                is MdSpan.Wiki -> {
                    val start = length
                    withStyle(SpanStyle(color = Color(0xFF1565C0), fontWeight = FontWeight.Medium)) {
                        append(s.link.alias ?: s.link.target)
                    }
                    addStringAnnotation("wiki", s.link.target, start, length)
                }
                is MdSpan.Tag -> withStyle(SpanStyle(color = Color(0xFF6A1B9A))) { append("#${s.name}") }
            }
        }
    }
    androidx.compose.foundation.text.ClickableText(
        text = annotated,
        style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        onClick = { offset ->
            annotated.getStringAnnotations("wiki", offset, offset).firstOrNull()?.let { onWiki(it.item) }
        },
    )
}

@Composable
private fun CodeBlock(language: String, code: String) {
    val lang = when (language.lowercase()) {
        "kt", "kotlin" -> Language.KOTLIN
        "java" -> Language.JAVA
        "js", "javascript" -> Language.JAVASCRIPT
        "py", "python" -> Language.PYTHON
        "sh", "bash", "shell" -> Language.SHELL
        "xml" -> Language.XML
        "json" -> Language.JSON
        "md", "markdown" -> Language.MARKDOWN
        else -> Language.PLAIN
    }
    val tokens = remember(code, lang) { SyntaxHighlighter.tokenize(code, lang) }
    val annotated = buildAnnotatedString {
        append(code)
        tokens.forEach { t ->
            val color = when (t.type) {
                TokenType.KEYWORD -> Color(0xFF1565C0)
                TokenType.STRING -> Color(0xFF2E7D32)
                TokenType.COMMENT -> Color(0xFF757575)
                TokenType.NUMBER -> Color(0xFF6A1B9A)
                TokenType.TAG -> Color(0xFFAD1457)
                TokenType.ATTRIBUTE -> Color(0xFF00838F)
                TokenType.ANNOTATION -> Color(0xFFEF6C00)
                TokenType.HEADING -> Color(0xFF283593)
                TokenType.PUNCTUATION -> Color(0xFF424242)
            }
            if (t.start in 0..code.length && t.end in 0..code.length && t.start < t.end) {
                addStyle(SpanStyle(color = color), t.start, t.end)
            }
        }
    }
    Text(
        annotated,
        Modifier.fillMaxWidth().background(Color(0x11000000)).padding(8.dp),
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun GraphCanvas(nodes: List<GraphNode>, edges: List<GraphEdge>, onOpen: (String) -> Unit) {
    if (nodes.isEmpty()) {
        Text("No notes yet.", Modifier.padding(16.dp))
        return
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val layout = remember(nodes, edges) {
        ForceDirectedLayout(
            nodeIds = nodes.map { it.path },
            edges = edges.map { it.from to it.to },
        )
    }
    // Drive a few simulation steps while visible.
    LaunchedEffect(layout) {
        var temp = 60f
        while (isActive) {
            withContext(Dispatchers.Default) { layout.step(2, temp) }
            temp = (temp * 0.992f).coerceAtLeast(4f)
            delay(32)
        }
    }
    var dragging by remember { mutableStateOf<String?>(null) }
    val edgeColor = MaterialTheme.colorScheme.outline
    val nodeColor = MaterialTheme.colorScheme.primary
    val textColor = MaterialTheme.colorScheme.onSurface
    // Snapshot positions each frame for Compose invalidation
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(layout) {
        while (isActive) {
            delay(32)
            tick++
        }
    }
    @Suppress("UNUSED_VARIABLE")
    val _tick = tick
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            detectTransformGestures { _, pan, zoom, _ ->
                if (dragging == null) {
                    scale = (scale * zoom).coerceIn(0.3f, 3f)
                    offset += pan
                }
            }
        },
    ) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(nodes, scale, offset) {
                detectDragGestures(
                    onDragStart = { start ->
                        val hit = nodes.minByOrNull { n ->
                            val p = layout.positions[n.path] ?: return@minByOrNull Float.MAX_VALUE
                            val c = Offset(p.x * scale + offset.x, p.y * scale + offset.y)
                            (c - start).getDistance()
                        }
                        val p = hit?.let { layout.positions[it.path] }
                        if (hit != null && p != null) {
                            val c = Offset(p.x * scale + offset.x, p.y * scale + offset.y)
                            if ((c - start).getDistance() < 48f) {
                                dragging = hit.path
                                layout.pinned += hit.path
                            }
                        }
                    },
                    onDragEnd = {
                        dragging?.let { layout.release(it) }
                        dragging = null
                    },
                    onDragCancel = {
                        dragging?.let { layout.release(it) }
                        dragging = null
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        val id = dragging ?: return@detectDragGestures
                        val cur = layout.positions[id] ?: return@detectDragGestures
                        layout.dragTo(id, cur.x + amount.x / scale, cur.y + amount.y / scale)
                        tick++
                    },
                )
            },
        ) {
            val o = offset
            val s = scale
            edges.forEach { e ->
                val a = layout.positions[e.from] ?: return@forEach
                val b = layout.positions[e.to] ?: return@forEach
                drawLine(edgeColor, Offset(a.x * s + o.x, a.y * s + o.y), Offset(b.x * s + o.x, b.y * s + o.y), strokeWidth = 2f)
            }
            nodes.forEach { n ->
                val p = layout.positions[n.path] ?: return@forEach
                val c = Offset(p.x * s + o.x, p.y * s + o.y)
                drawCircle(nodeColor, radius = 18f + n.tagCount * 2f, center = c)
            }
        }
        Text(
            "Drag nodes · pinch to zoom",
            Modifier.align(Alignment.TopCenter).padding(8.dp),
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
        )
        LazyColumn(Modifier.align(Alignment.BottomStart).height(140.dp).fillMaxWidth().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)).padding(8.dp)) {
            items(nodes) { n ->
                Text(n.title, Modifier.fillMaxWidth().clickable { onOpen(n.path) }.padding(4.dp), color = textColor)
            }
        }
    }
}
