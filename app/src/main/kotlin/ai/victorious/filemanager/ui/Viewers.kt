package ai.victorious.filemanager.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.outlined.AutoAwesome
import ai.victorious.filemanager.core.archive.ArchiveItem
import ai.victorious.filemanager.core.archive.Archives
import ai.victorious.filemanager.core.archive.ArchiveFormat
import ai.victorious.filemanager.core.archive.EncryptedZip
import ai.victorious.filemanager.core.archive.WrongPasswordException
import ai.victorious.filemanager.core.syntax.FindReplace
import ai.victorious.filemanager.core.syntax.SyntaxHighlighter
import ai.victorious.filemanager.core.archive.ZipEdit
import ai.victorious.filemanager.core.archive.ZipEditor
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.outlined.FindReplace
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import ai.victorious.filemanager.core.hex.Hex
import ai.victorious.filemanager.core.hex.HexDocument
import ai.victorious.filemanager.core.hex.HexPattern
import ai.victorious.filemanager.core.hex.HexSearch
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import ai.victorious.filemanager.core.text.TextFiles
import ai.victorious.filemanager.core.text.UndoHistory
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ViewerMode { TEXT, HEX, IMAGE, MEDIA_INFO, ARCHIVE, GALLERY, VIDEO, AUDIO, PDF, FONT }

data class ViewerRequest(val path: String, val mode: ViewerMode)

@Composable
fun ViewerHost(request: ViewerRequest, extractDir: String, onClose: () -> Unit, onMessage: (String) -> Unit) {
    when (request.mode) {
        ViewerMode.TEXT -> TextEditorScreen(File(request.path), onClose, onMessage)
        ViewerMode.HEX -> HexEditorScreen(File(request.path), onClose, onMessage)
        ViewerMode.IMAGE -> ImagePreviewScreen(File(request.path), onClose)
        ViewerMode.MEDIA_INFO -> MediaInfoScreen(File(request.path), onClose)
        ViewerMode.ARCHIVE -> ArchiveScreen(File(request.path), File(extractDir), onClose, onMessage)
        ViewerMode.GALLERY -> GalleryScreen(File(request.path), onClose, onMessage)
        ViewerMode.VIDEO -> VideoPlayerScreen(File(request.path), onClose)
        ViewerMode.AUDIO -> AudioPlayerScreen(File(request.path), onClose)
        ViewerMode.PDF -> PdfViewerScreen(File(request.path), onClose, onMessage)
        ViewerMode.FONT -> FontPreviewScreen(File(request.path), onClose, onMessage)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewerScaffold(
    title: String,
    onClose: () -> Unit,
    actions: @Composable () -> Unit = {},
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Close") }
                },
                actions = { actions() },
            )
        },
    ) { padding -> content(Modifier.padding(padding).fillMaxSize()) }
}

@Composable
private fun Centered(modifier: Modifier, text: String?) {
    Box(modifier, contentAlignment = Alignment.Center) {
        if (text == null) CircularProgressIndicator() else Text(text, modifier = Modifier.padding(24.dp))
    }
}

@Composable
private fun DiscardDialog(onDiscard: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Discard changes?") },
        text = { Text("You have unsaved edits.") },
        confirmButton = { TextButton(onClick = onDiscard) { Text("Discard") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Keep editing") } },
    )
}

@Composable
private fun TextEditorScreen(file: File, onClose: () -> Unit, onMessage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var history by remember { mutableStateOf<UndoHistory<String>?>(null) }
    var text by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf("") }
    var version by remember { mutableIntStateOf(0) }
    var confirmClose by remember { mutableStateOf(false) }
    var showFind by remember { mutableStateOf(false) }
    var find by remember { mutableStateOf(FindState()) }
    var field by remember { mutableStateOf(TextFieldValue("")) }
    var showAi by remember { mutableStateOf(false) }
    val aiEnabled by (LocalContext.current.applicationContext as ai.victorious.filemanager.FileManagerApplication).aiSettings.enabled.collectAsState()
    val language = remember(file) { SyntaxHighlighter.languageFor(file.name) }
    val dark = isSystemInDarkTheme()
    LaunchedEffect(file) {
        val r = withContext(Dispatchers.IO) { runCatching { TextFiles.load(file) } }
        r.onSuccess { history = UndoHistory(it); text = it; saved = it; field = TextFieldValue(it) }.onFailure { error = it.message ?: "Cannot open" }
    }
    val dirty = text != saved
    val close = { if (dirty) confirmClose = true else onClose() }
    BackHandler(onBack = close)
    ViewerScaffold(
        title = (if (dirty) "• " else "") + file.name,
        onClose = close,
        actions = {
            val h = history
            if (h != null) {
                IconButton(onClick = { showFind = !showFind }) {
                    Icon(Icons.Outlined.FindReplace, contentDescription = "Find & replace")
                }
                if (aiEnabled) {
                    IconButton(onClick = { showAi = !showAi }) {
                        Icon(Icons.Outlined.AutoAwesome, contentDescription = "AI assistant")
                    }
                }
                key(version) {
                    IconButton(onClick = { text = h.undo(); field = TextFieldValue(text); version++ }, enabled = h.canUndo) {
                        Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = "Undo")
                    }
                    IconButton(onClick = { text = h.redo(); field = TextFieldValue(text); version++ }, enabled = h.canRedo) {
                        Icon(Icons.AutoMirrored.Outlined.Redo, contentDescription = "Redo")
                    }
                }
                IconButton(
                    enabled = dirty,
                    onClick = {
                        val toSave = text
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { runCatching { TextFiles.save(file, toSave) } }
                            r.onSuccess { saved = toSave; onMessage("Saved ${file.name}") }
                                .onFailure { onMessage("Save failed: ${it.message}") }
                        }
                    },
                ) { Icon(Icons.Outlined.Save, contentDescription = "Save") }
            }
        },
    ) { modifier ->
        val h = history
        if (h == null) Centered(modifier, error) else Column(modifier) {
            val (matches, findError) = remember(text, find, showFind) {
                if (showFind) findMatches(text, find) else emptyList<IntRange>() to null
            }
            fun applyEdit(newText: String) {
                if (newText != text) { text = newText; h.push(newText); field = TextFieldValue(newText); version++ }
            }
            if (showFind) {
                FindReplaceBar(
                    state = find,
                    matchCount = matches.size,
                    error = findError,
                    onChange = { find = it },
                    onReplaceNext = {
                        runCatching { FindReplace.replaceNext(text, find.query, find.replacement, field.selection.start, find.options) }
                            .onSuccess { applyEdit(it.text) }.onFailure { onMessage("Replace failed: ${it.message}") }
                    },
                    onReplaceAll = {
                        runCatching { FindReplace.replaceAll(text, find.query, find.replacement, find.options) }
                            .onSuccess { applyEdit(it.text); onMessage("Replaced ${it.count} occurrence(s)") }
                            .onFailure { onMessage("Replace failed: ${it.message}") }
                    },
                )
            }
            if (showAi) {
                val sel = field.selection
                AiPanel(
                    fileName = file.name,
                    language = language.name,
                    fullText = text,
                    selection = if (sel.collapsed) null else sel.min until sel.max,
                    onApply = { replacement ->
                        val s = field.selection
                        applyEdit(ai.victorious.filemanager.core.ai.AiResponseParser.applyEdit(text, if (s.collapsed) null else s.min until s.max, replacement))
                        onMessage("Applied AI edit (undo to revert)")
                    },
                    onClose = { showAi = false },
                )
            }
            val transformation = remember(language, dark, matches) { HighlightTransformation(language, dark, matches) }
            TextField(
                value = field,
                onValueChange = {
                    field = it
                    if (it.text != text) { text = it.text; h.push(it.text); version++ }
                },
                modifier = Modifier.fillMaxSize(),
                visualTransformation = transformation,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
        }
    }
    if (confirmClose) DiscardDialog(onDiscard = onClose, onCancel = { confirmClose = false })
}

@Composable
private fun key(@Suppress("UNUSED_PARAMETER") v: Int, content: @Composable () -> Unit) = androidx.compose.runtime.key(v) { content() }

@Composable
private fun HexEditorScreen(file: File, onClose: () -> Unit, onMessage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var doc by remember { mutableStateOf<HexDocument?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var version by remember { mutableIntStateOf(0) }
    var editRow by remember { mutableStateOf<Int?>(null) }
    var confirmClose by remember { mutableStateOf(false) }
    // Find bar state. matchAt/matchLen mark the current hit (matchAt < 0 = none).
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var findOpen by remember { mutableStateOf(false) }
    var findText by remember { mutableStateOf("") }
    var findAsText by remember { mutableStateOf(false) }
    var findStatus by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    var matchAt by remember { mutableIntStateOf(-1) }
    var matchLen by remember { mutableIntStateOf(0) }
    LaunchedEffect(file) {
        withContext(Dispatchers.IO) { runCatching { HexDocument.load(file) } }
            .onSuccess { doc = it }.onFailure { error = it.message ?: "Cannot open" }
    }
    val d = doc
    val dirty = version >= 0 && d?.isDirty == true
    val close = { if (dirty) confirmClose = true else onClose() }
    fun runFind(forward: Boolean) {
        val doc0 = doc ?: return
        val pattern = if (findAsText) HexPattern.fromText(findText) else HexPattern.fromHex(findText)
        if (pattern == null) {
            findStatus = if (findAsText) "Enter text to find" else "Invalid hex (pairs like 48 65 ?? 6C; not all ??)"
            return
        }
        val from = if (matchAt < 0) (if (forward) 0 else doc0.size) else if (forward) matchAt + 1 else matchAt - 1
        searching = true
        scope.launch {
            val hit = withContext(Dispatchers.IO) { HexSearch.find(doc0, pattern, from, forward, wrap = true) }
            searching = false
            if (hit < 0) {
                matchAt = -1; findStatus = "Not found"
            } else {
                matchAt = hit; matchLen = pattern.length
                findStatus = "Match at 0x%08X".format(hit)
                listState.animateScrollToItem(hit / HexDocument.BYTES_PER_ROW)
            }
        }
    }
    BackHandler(onBack = close)
    ViewerScaffold(
        title = (if (dirty) "• " else "") + file.name + "  (hex)",
        onClose = close,
        actions = {
            if (d != null) {
                IconButton(onClick = { findOpen = !findOpen }) {
                    Icon(Icons.Outlined.Search, contentDescription = "Find")
                }
                IconButton(onClick = { d.undo(); version++ }, enabled = version >= 0 && d.canUndo) {
                    Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = "Undo")
                }
                IconButton(enabled = dirty, onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { runCatching { d.saveTo(file) } }
                            .onSuccess { version++; onMessage("Saved ${file.name}") }
                            .onFailure { onMessage("Save failed: ${it.message}") }
                    }
                }) { Icon(Icons.Outlined.Save, contentDescription = "Save") }
            }
        },
    ) { modifier ->
        if (d == null) Centered(modifier, error) else {
            Column(modifier) {
                Text(
                    "${d.size} bytes · tap a row to edit",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
                if (findOpen) {
                    Column(Modifier.padding(horizontal = 8.dp)) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            androidx.compose.material3.OutlinedTextField(
                                value = findText,
                                onValueChange = { findText = it; matchAt = -1; findStatus = null },
                                singleLine = true,
                                label = { Text(if (findAsText) "Text" else "Hex bytes (?? = any)") },
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(enabled = !searching, onClick = { runFind(forward = false) }) {
                                Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "Previous match")
                            }
                            IconButton(enabled = !searching, onClick = { runFind(forward = true) }) {
                                Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "Next match")
                            }
                        }
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            androidx.compose.material3.FilterChip(
                                selected = !findAsText, onClick = { findAsText = false; matchAt = -1; findStatus = null },
                                label = { Text("Hex") },
                            )
                            Spacer(Modifier.width(6.dp))
                            androidx.compose.material3.FilterChip(
                                selected = findAsText, onClick = { findAsText = true; matchAt = -1; findStatus = null },
                                label = { Text("Text") },
                            )
                            Text(
                                (if (searching) "Searching…" else findStatus.orEmpty()),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }
                }
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    items(d.rowCount) { index ->
                        @Suppress("UNUSED_EXPRESSION") version
                        val row = d.row(index)
                        val changed = row.values.indices.any { (row.offset + it) in d.changedOffsets }
                        val hit = matchAt >= 0 && row.offset < matchAt + matchLen && row.offset + row.values.size > matchAt
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    when {
                                        hit -> MaterialTheme.colorScheme.secondaryContainer
                                        changed -> MaterialTheme.colorScheme.tertiaryContainer
                                        else -> MaterialTheme.colorScheme.surface
                                    },
                                )
                                .clickable(enabled = !searching) { editRow = index }
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            val style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                            Text(row.offsetLabel, style = style, color = MaterialTheme.colorScheme.primary)
                            Text("  " + row.hex.padEnd(47), style = style)
                            Text("  " + row.ascii, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
    val r = editRow
    if (d != null && r != null) {
        val row = d.row(r)
        var input by remember(r) { mutableStateOf(row.hex) }
        AlertDialog(
            onDismissRequest = { editRow = null },
            title = { Text("Edit ${row.offsetLabel}") },
            text = {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.uppercase() },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    supportingText = { Text("${row.values.size} space-separated hex bytes (overwrite)") },
                )
            },
            confirmButton = {
                Button(onClick = {
                    val parts = input.trim().split(Regex("\\s+"))
                    val parsed = parts.map { Hex.parseByte(it) }
                    if (parts.size != row.values.size || parsed.any { it == null }) {
                        onMessage("Need exactly ${row.values.size} valid hex bytes")
                    } else {
                        parsed.forEachIndexed { i, b -> if (b != row.values[i]) d.set(row.offset + i, b!!) }
                        version++
                        editRow = null
                    }
                }) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { editRow = null }) { Text("Cancel") } },
        )
    }
    if (confirmClose) DiscardDialog(onDiscard = onClose, onCancel = { confirmClose = false })
}

@Composable
private fun ImagePreviewScreen(file: File, onClose: () -> Unit) {
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf("") }
    LaunchedEffect(file) {
        withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
                info = "${bounds.outWidth}×${bounds.outHeight} · ${bounds.outMimeType ?: "image"}"
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?.asImageBitmap() ?: error("Unsupported or corrupt image")
            }
        }.onSuccess { image = it }.onFailure { error = it.message ?: "Cannot decode image" }
    }
    BackHandler(onBack = onClose)
    ViewerScaffold(title = file.name, onClose = onClose) { modifier ->
        val img = image
        if (img == null) Centered(modifier, error) else Column(modifier) {
            Text(info, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(8.dp))
            Image(img, contentDescription = file.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun MediaInfoScreen(file: File, onClose: () -> Unit) {
    var rows by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(file) {
        withContext(Dispatchers.IO) {
            runCatching {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(file.path)
                    val keys = listOf(
                        "Title" to MediaMetadataRetriever.METADATA_KEY_TITLE,
                        "Artist" to MediaMetadataRetriever.METADATA_KEY_ARTIST,
                        "Album" to MediaMetadataRetriever.METADATA_KEY_ALBUM,
                        "Duration (ms)" to MediaMetadataRetriever.METADATA_KEY_DURATION,
                        "MIME type" to MediaMetadataRetriever.METADATA_KEY_MIMETYPE,
                        "Bitrate" to MediaMetadataRetriever.METADATA_KEY_BITRATE,
                        "Video width" to MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH,
                        "Video height" to MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT,
                        "Rotation" to MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION,
                        "Date" to MediaMetadataRetriever.METADATA_KEY_DATE,
                    )
                    listOf("File size" to "${file.length()} bytes") +
                        keys.mapNotNull { (label, k) -> r.extractMetadata(k)?.let { label to it } }
                } finally {
                    r.release()
                }
            }
        }.onSuccess { rows = it }.onFailure { error = "No media info: ${it.message}" }
    }
    BackHandler(onBack = onClose)
    ViewerScaffold(title = file.name, onClose = onClose) { modifier ->
        val list = rows
        if (list == null) Centered(modifier, error) else LazyColumn(modifier.padding(16.dp)) {
            items(list) { (k, v) ->
                Row(Modifier.padding(vertical = 6.dp)) {
                    Text(k, modifier = Modifier.weight(0.4f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(v, modifier = Modifier.weight(0.6f))
                }
            }
        }
    }
}

@Composable
private fun ArchiveScreen(file: File, extractTo: File, onClose: () -> Unit, onMessage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<ArchiveItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var askPassword by remember { mutableStateOf(false) }
    var encrypted by remember { mutableStateOf(false) }
    var isRar by remember { mutableStateOf(false) }
    val dest = File(extractTo, file.name.substringBefore('.').ifBlank { "extracted" })
    val savedPasswords = (LocalContext.current.applicationContext as ai.victorious.filemanager.FileManagerApplication).archivePasswords
    var reload by remember { mutableIntStateOf(0) }
    var picked by remember { mutableStateOf<ArchiveItem?>(null) }
    var editText by remember { mutableStateOf<Pair<String, String>?>(null) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var zipFormat by remember { mutableStateOf(false) }
    fun editArchive(edit: ZipEdit, done: String) {
        busy = true
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { ZipEditor.apply(file, listOf(edit)) } }
                .onSuccess { reload++; onMessage("$done — v1 signature removed; sign the APK again before installing") }
                .onFailure { onMessage("Edit failed: ${it.message}") }
            busy = false
        }
    }
    fun runExtract(password: CharArray?, remember: Boolean = false) {
        busy = true
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    when {
                        isRar -> Archives.extractRar(file, dest, password?.let(::String))
                        encrypted -> EncryptedZip.extract(file, dest, password)
                        else -> Archives.extract(file, dest)
                    }.also {
                        // Only a password that just worked is remembered. Saving is best-effort.
                        if (remember && password != null) runCatching { savedPasswords.add(String(password)) }
                    }
                }
            }
                .onSuccess { onMessage("Extracted $it file(s) to ${dest.path}") }
                .onFailure {
                    onMessage(
                        when {
                            it is WrongPasswordException -> "Wrong password"
                            it is Archives.RarPasswordException -> it.message ?: "Wrong password"
                            else -> "Extract failed: ${it.message}"
                        },
                    )
                }
            busy = false
        }
    }
    LaunchedEffect(file, reload) {
        // Corrupt / unknown archives must surface as an error, not crash the LaunchedEffect.
        val format = withContext(Dispatchers.IO) { runCatching { Archives.format(file) }.getOrDefault(ArchiveFormat.UNKNOWN) }
        isRar = format == ArchiveFormat.RAR
        zipFormat = format == ArchiveFormat.ZIP
        encrypted = withContext(Dispatchers.IO) {
            runCatching {
                when (format) {
                    ArchiveFormat.ZIP -> EncryptedZip.isEncrypted(file)
                    ArchiveFormat.RAR -> Archives.isRarPasswordProtected(file)
                    else -> false
                }
            }.getOrDefault(false)
        }
        withContext(Dispatchers.IO) { runCatching { Archives.list(file) } }
            .onSuccess { items = it }
            .onFailure {
                // RAR5 with encrypted headers cannot be listed without the password; still allow extract.
                if (it is Archives.RarPasswordException) { items = emptyList(); encrypted = true }
                else error = it.message ?: "Cannot read archive"
            }
    }
    BackHandler(onBack = onClose)
    ViewerScaffold(
        title = file.name,
        onClose = onClose,
        actions = {
            IconButton(enabled = items != null && !busy, onClick = {
                if (!encrypted) {
                    runExtract(null)
                } else if (isRar) {
                    askPassword = true // RAR can't be verified without writing, so it is never auto-tried
                } else {
                    busy = true
                    scope.launch {
                        // Verify saved passwords without writing anything; extract only with a confirmed match.
                        val match = withContext(Dispatchers.IO) {
                            runCatching { savedPasswords.list().firstOrNull { EncryptedZip.verifyPassword(file, it.toCharArray()) } }
                                .getOrNull()
                        }
                        busy = false
                        if (match != null) runExtract(match.toCharArray(), remember = true) else askPassword = true
                    }
                }
            }) { Icon(Icons.Outlined.Unarchive, contentDescription = "Extract to other pane") }
        },
    ) { modifier ->
        val list = items
        if (list == null) Centered(modifier, error) else Column(modifier) {
            Text(
                (if (encrypted) "🔒 password protected · " else "") +
                    "${list.count { !it.isDirectory }} file(s) · extract goes to ${extractTo.path}",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            LazyColumn(Modifier.fillMaxSize()) {
                items(list) { item ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable(enabled = zipFormat && !encrypted && !busy && !item.isDirectory) { picked = item }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(item.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!item.isDirectory && item.size >= 0) Text("${item.size} B", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    picked?.let { item ->
        AlertDialog(
            onDismissRequest = { picked = null },
            title = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = { Text("Edit this entry inside the archive without extracting it.") },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        picked = null
                        scope.launch {
                            val loaded = withContext(Dispatchers.IO) {
                                runCatching {
                                    require(item.size <= 1_000_000) { "Too large to edit as text (>1 MB)" }
                                    val bytes = java.util.zip.ZipFile(file).use { z -> z.getInputStream(z.getEntry(item.name)).readBytes() }
                                    require(bytes.none { it == 0.toByte() }) { "Binary entry — use the hex editor after extracting" }
                                    String(bytes, Charsets.UTF_8)
                                }
                            }
                            loaded.onSuccess { editText = item.name to it }.onFailure { onMessage(it.message ?: "Cannot open") }
                        }
                    }) { Text("Edit text") }
                    TextButton(onClick = { renaming = item.name; picked = null }) { Text("Rename") }
                    TextButton(onClick = { picked = null; editArchive(ZipEdit.Delete(item.name), "Deleted ${item.name}") }) { Text("Delete") }
                }
            },
            dismissButton = { TextButton(onClick = { picked = null }) { Text("Cancel") } },
        )
    }
    editText?.let { (name, initial) ->
        var text by remember(name) { mutableStateOf(initial) }
        AlertDialog(
            onDismissRequest = { editText = null },
            title = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = { OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(onClick = { editText = null; editArchive(ZipEdit.Put(name, text.toByteArray(Charsets.UTF_8)), "Saved $name") }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editText = null }) { Text("Cancel") } },
        )
    }
    renaming?.let { old ->
        var value by remember(old) { mutableStateOf(old) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename entry") },
            text = { OutlinedTextField(value, { value = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(enabled = value.isNotBlank() && value != old, onClick = {
                    renaming = null; editArchive(ZipEdit.Rename(old, value.trim()), "Renamed to ${value.trim()}")
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    if (askPassword) {
        PasswordDialog(
            title = "Password for ${file.name}",
            onConfirm = { askPassword = false; runExtract(it) },
            onConfirmRemember = { pw, keep -> askPassword = false; runExtract(pw, keep) },
            onDismiss = { askPassword = false },
        )
    }
}

@Composable
fun PasswordDialog(
    title: String,
    confirmLabel: String = "OK",
    onConfirm: (CharArray) -> Unit,
    onDismiss: () -> Unit,
    /** When set, the dialog shows a "Remember" checkbox and calls this instead of [onConfirm]. */
    onConfirmRemember: ((CharArray, Boolean) -> Unit)? = null,
) {
    var pw by remember { mutableStateOf("") }
    var rememberPw by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = pw, onValueChange = { pw = it }, singleLine = true, label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                )
                if (onConfirmRemember != null) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(checked = rememberPw, onCheckedChange = { rememberPw = it })
                        Text("Remember (encrypted on this device)", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (onConfirmRemember != null) onConfirmRemember(pw.toCharArray(), rememberPw) else onConfirm(pw.toCharArray()) },
                enabled = pw.isNotEmpty(),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
