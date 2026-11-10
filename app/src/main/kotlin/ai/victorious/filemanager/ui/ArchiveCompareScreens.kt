package ai.victorious.filemanager.ui

import ai.victorious.filemanager.core.archive.CompressionLevel
import ai.victorious.filemanager.core.archive.CreateFormat
import ai.victorious.filemanager.core.diff.EntryStatus
import ai.victorious.filemanager.core.diff.FolderCompare
import ai.victorious.filemanager.core.diff.FolderDiff
import ai.victorious.filemanager.core.diff.FolderDiffEntry
import ai.victorious.filemanager.core.diff.InlineSegment
import ai.victorious.filemanager.core.diff.NewerSide
import ai.victorious.filemanager.core.viewer.FileKind
import ai.victorious.filemanager.core.viewer.FileKinds
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Create archive" options: format, compression level, file name (without extension) and an optional
 * password (ZIP: AES-256, 7z: 7-Zip AES-256). The password is passed on as a CharArray, never logged.
 */
@Composable
fun CreateArchiveDialog(
    suggestedBase: String,
    count: Int,
    onCreate: (CreateFormat, CompressionLevel, String, CharArray?) -> Unit,
    onDismiss: () -> Unit,
) {
    var format by remember { mutableStateOf(CreateFormat.ZIP) }
    var level by remember { mutableStateOf(CompressionLevel.NORMAL) }
    var name by remember { mutableStateOf(suggestedBase) }
    var protect by remember { mutableStateOf(false) }
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var showPw by remember { mutableStateOf(false) }
    val usePassword = protect && format.supportsPassword
    val pwProblem = when {
        !usePassword -> null
        pw.isEmpty() -> "Enter a password"
        pw != pw2 -> "Passwords don't match"
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create archive") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("$count item(s). Folders are added with everything inside.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = name, onValueChange = { name = it.replace('/', '_') }, singleLine = true,
                    label = { Text("Name") }, suffix = { Text(".${format.extension}") },
                )
                Text("Format", style = MaterialTheme.typography.labelMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CreateFormat.entries.forEach { f -> FilterChip(format == f, { format = f }, label = { Text(f.label) }) }
                }
                Text("Compression", style = MaterialTheme.typography.labelMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val levels = if (format == CreateFormat.TAR) listOf(CompressionLevel.STORE) else CompressionLevel.entries
                    levels.forEach { l ->
                        FilterChip(level == l || levels.size == 1, { level = l }, label = { Text(l.name.lowercase().replaceFirstChar { it.uppercase() }) })
                    }
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = usePassword, enabled = format.supportsPassword, onCheckedChange = { protect = it })
                    Text(
                        if (format.supportsPassword) "Password protect (AES-256)" else "Password: only for 7z and ZIP",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (usePassword) {
                    val visual = if (showPw) androidx.compose.ui.text.input.VisualTransformation.None
                    else androidx.compose.ui.text.input.PasswordVisualTransformation()
                    OutlinedTextField(value = pw, onValueChange = { pw = it }, singleLine = true, label = { Text("Password") }, visualTransformation = visual)
                    OutlinedTextField(
                        value = pw2, onValueChange = { pw2 = it }, singleLine = true, label = { Text("Repeat password") },
                        visualTransformation = visual, isError = pw2.isNotEmpty() && pw != pw2,
                    )
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(checked = showPw, onCheckedChange = { showPw = it })
                        Text("Show password", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        "File contents are encrypted; file names stay visible. There is no way to recover a lost password.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    pwProblem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
                Text(
                    when (format) {
                        CreateFormat.ZIP -> "Opens everywhere. Password ZIPs need an app that supports AES (7-Zip, WinRAR, most file managers)."
                        CreateFormat.SEVEN_Z -> "Usually the smallest. Needs a 7-Zip compatible app to open (this app opens it too)."
                        CreateFormat.TAR -> "No compression; keeps folders together for Linux tools."
                        CreateFormat.TAR_GZ -> "Common on Linux and servers. Fast."
                        CreateFormat.TAR_BZ2 -> "Smaller than .tar.gz, slower."
                        CreateFormat.TAR_XZ -> "Small like 7z, standard on Linux."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = pwProblem == null, onClick = {
                val effective = if (format == CreateFormat.TAR) CompressionLevel.STORE else level
                onCreate(format, effective, name.ifBlank { suggestedBase }, if (usePassword) pw.toCharArray() else null)
                pw = ""; pw2 = ""
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Renders one side of a changed line with the changed words emphasised. */
internal fun inlineDiffText(prefix: String, segments: List<InlineSegment>, strong: Color): AnnotatedString = buildAnnotatedString {
    append(prefix)
    segments.forEach { s ->
        if (s.changed) withStyle(SpanStyle(background = strong, fontWeight = FontWeight.Bold)) { append(s.text) } else append(s.text)
    }
}

private enum class FolderFilter(val label: String) { CHANGES("Differences"), ALL("All"), LEFT("Only left"), RIGHT("Only right") }

/**
 * Two-folder compare. Lists files that differ, exist on one side only, or clash (file vs folder).
 * Tapping a differing text file opens the line/word compare. "Copy missing" never overwrites.
 */
@Composable
fun FolderCompareScreen(left: File, right: File, onClose: () -> Unit, onEvent: (UiEvent) -> Unit) {
    val scope = rememberCoroutineScope()
    var diff by remember { mutableStateOf<FolderDiff?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var deep by remember { mutableStateOf(true) }
    var filter by remember { mutableStateOf(FolderFilter.CHANGES) }
    var reload by remember { mutableIntStateOf(0) }
    var openFile by remember { mutableStateOf<FolderDiffEntry?>(null) }
    var confirmCopy by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(left, right, deep, reload) {
        diff = null; error = null
        withContext(Dispatchers.IO) { runCatching { FolderCompare.compare(left, right, deep) } }
            .onSuccess { diff = it }.onFailure { error = it.message ?: "Cannot compare" }
    }
    openFile?.let { e ->
        CompareScreen(File(left, e.relPath), File(right, e.relPath)) { openFile = null }
        return
    }
    ToolScaffold("${left.name} ↔ ${right.name}", onClose, actions = {
        FilterChip(deep, { deep = !deep }, label = { Text(if (deep) "Exact" else "Quick") })
    }) { modifier ->
        val d = diff
        if (d == null) Loading(modifier, error) else Column(modifier) {
            val s = d.summary
            Text(
                if (s.identical) "Folders match (${s.same} file(s))"
                else "${s.different} changed · ${s.onlyLeft} only left · ${s.onlyRight} only right" +
                    (if (s.typeMismatch > 0) " · ${s.typeMismatch} file/folder clash" else "") + " · ${s.same} same",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Text(
                "Left: ${left.path}\nRight: ${right.path}" + if (!deep) "\nQuick mode compares size + time only; tap Quick for byte-exact." else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FolderFilter.entries.forEach { f -> FilterChip(filter == f, { filter = f }, label = { Text(f.label) }) }
            }
            Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(enabled = s.onlyLeft > 0, onClick = { confirmCopy = true }) { Text("Copy only-left → right") }
                TextButton(enabled = s.onlyRight > 0, onClick = { confirmCopy = false }) { Text("Copy only-right → left") }
            }
            HorizontalDivider()
            val rows = when (filter) {
                FolderFilter.CHANGES -> d.entries.filter { it.status != EntryStatus.SAME }
                FolderFilter.ALL -> d.entries
                FolderFilter.LEFT -> d.entries.filter { it.status == EntryStatus.ONLY_LEFT }
                FolderFilter.RIGHT -> d.entries.filter { it.status == EntryStatus.ONLY_RIGHT }
            }
            if (rows.isEmpty()) Text("Nothing to show for this filter.", Modifier.padding(16.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                items(rows, key = { it.relPath }) { e ->
                    val (label, color) = when (e.status) {
                        EntryStatus.SAME -> "same" to MaterialTheme.colorScheme.onSurfaceVariant
                        EntryStatus.DIFFERENT -> "changed" to MaterialTheme.colorScheme.secondary
                        EntryStatus.ONLY_LEFT -> "only left" to MaterialTheme.colorScheme.primary
                        EntryStatus.ONLY_RIGHT -> "only right" to MaterialTheme.colorScheme.tertiary
                        EntryStatus.TYPE_MISMATCH -> "file vs folder" to MaterialTheme.colorScheme.error
                    }
                    val textual = e.status == EntryStatus.DIFFERENT && FileKinds.byName(e.relPath.substringAfterLast('/')).let { it == FileKind.TEXT || it == null }
                    ListItem(
                        headlineContent = { Text(e.relPath + if (e.isDirectory) "/" else "") },
                        supportingContent = {
                            val sizes = listOfNotNull(e.leftSize?.let { "L ${compareSize(it)}" }, e.rightSize?.let { "R ${compareSize(it)}" }).joinToString("  ")
                            val newer = when (e.newer) { NewerSide.LEFT -> " · left is newer"; NewerSide.RIGHT -> " · right is newer"; null -> "" }
                            Text("$label · $sizes$newer" + if (textual) " · tap to see lines" else "", color = color)
                        },
                        modifier = Modifier.fillMaxWidth().clickable(enabled = textual) { openFile = e },
                    )
                }
            }
        }
    }
    confirmCopy?.let { toRight ->
        val n = diff?.summary?.let { if (toRight) it.onlyLeft else it.onlyRight } ?: 0
        AlertDialog(
            onDismissRequest = { confirmCopy = null },
            title = { Text(if (toRight) "Copy to right?" else "Copy to left?") },
            text = { Text("Copies $n item(s) that are missing on the ${if (toRight) "right" else "left"}. Nothing that already exists is overwritten.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmCopy = null
                    val d = diff ?: return@TextButton
                    scope.launch {
                        withContext(Dispatchers.IO) { runCatching { FolderCompare.copyMissing(d, toRight) } }
                            .onSuccess { (c, skip) -> onEvent(UiEvent.ShowMessage("Copied $c item(s)" + if (skip > 0) ", skipped $skip that already existed" else "")) }
                            .onFailure { onEvent(UiEvent.ShowMessage("Copy failed: ${it.message}")) }
                        reload++
                    }
                }) { Text("Copy") }
            },
            dismissButton = { TextButton(onClick = { confirmCopy = null }) { Text("Cancel") } },
        )
    }
}

internal fun compareSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}
