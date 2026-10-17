package ai.victorious.filemanager.ui

import ai.victorious.filemanager.core.archive.ArchiveItem
import ai.victorious.filemanager.core.archive.Archives
import ai.victorious.filemanager.core.archive.ArchiveFormat
import ai.victorious.filemanager.core.archive.EncryptedZip
import ai.victorious.filemanager.core.archive.WrongPasswordException
import ai.victorious.filemanager.core.syntax.FindReplace
import ai.victorious.filemanager.core.syntax.SyntaxHighlighter
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.outlined.FindReplace
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import ai.victorious.filemanager.core.hex.Hex
import ai.victorious.filemanager.core.hex.HexDocument
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

enum class ViewerMode { TEXT, HEX, IMAGE, MEDIA_INFO, ARCHIVE }

data class ViewerRequest(val path: String, val mode: ViewerMode)

@Composable
fun ViewerHost(request: ViewerRequest, extractDir: String, onClose: () -> Unit, onMessage: (String) -> Unit) {
    when (request.mode) {
        ViewerMode.TEXT -> TextEditorScreen(File(request.path), onClose, onMessage)
        ViewerMode.HEX -> HexEditorScreen(File(request.path), onClose, onMessage)
        ViewerMode.IMAGE -> ImagePreviewScreen(File(request.path), onClose)
        ViewerMode.MEDIA_INFO -> MediaInfoScreen(File(request.path), onClose)
        ViewerMode.ARCHIVE -> ArchiveScreen(File(request.path), File(extractDir), onClose, onMessage)
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
    LaunchedEffect(file) {
        withContext(Dispatchers.IO) { runCatching { HexDocument.load(file) } }
            .onSuccess { doc = it }.onFailure { error = it.message ?: "Cannot open" }
    }
    val d = doc
    val dirty = version >= 0 && d?.isDirty == true
    val close = { if (dirty) confirmClose = true else onClose() }
    BackHandler(onBack = close)
    ViewerScaffold(
        title = (if (dirty) "• " else "") + file.name + "  (hex)",
        onClose = close,
        actions = {
            if (d != null) {
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
                LazyColumn(Modifier.fillMaxSize()) {
                    items(d.rowCount) { index ->
                        @Suppress("UNUSED_EXPRESSION") version
                        val row = d.row(index)
                        val changed = row.values.indices.any { (row.offset + it) in d.changedOffsets }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(if (changed) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surface)
                                .clickable { editRow = index }
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
    val dest = File(extractTo, file.name.substringBefore('.').ifBlank { "extracted" })
    fun runExtract(password: CharArray?) {
        busy = true
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { if (encrypted) EncryptedZip.extract(file, dest, password) else Archives.extract(file, dest) }
            }
                .onSuccess { onMessage("Extracted $it file(s) to ${dest.path}") }
                .onFailure { onMessage(if (it is WrongPasswordException) "Wrong password" else "Extract failed: ${it.message}") }
            busy = false
        }
    }
    LaunchedEffect(file) {
        encrypted = withContext(Dispatchers.IO) { Archives.format(file) == ArchiveFormat.ZIP && EncryptedZip.isEncrypted(file) }
        withContext(Dispatchers.IO) { runCatching { Archives.list(file) } }
            .onSuccess { items = it }.onFailure { error = it.message ?: "Cannot read archive" }
    }
    BackHandler(onBack = onClose)
    ViewerScaffold(
        title = file.name,
        onClose = onClose,
        actions = {
            IconButton(enabled = items != null && !busy, onClick = {
                if (encrypted) askPassword = true else runExtract(null)
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
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(item.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!item.isDirectory) Text("${item.size} B", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    if (askPassword) {
        PasswordDialog(
            title = "Password for ${file.name}",
            onConfirm = { askPassword = false; runExtract(it) },
            onDismiss = { askPassword = false },
        )
    }
}

@Composable
fun PasswordDialog(title: String, confirmLabel: String = "OK", onConfirm: (CharArray) -> Unit, onDismiss: () -> Unit) {
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = pw, onValueChange = { pw = it }, singleLine = true, label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(pw.toCharArray()) }, enabled = pw.isNotEmpty()) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
