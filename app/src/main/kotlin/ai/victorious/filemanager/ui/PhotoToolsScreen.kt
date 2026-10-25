package ai.victorious.filemanager.ui

import ai.victorious.filemanager.core.photo.BatchRename
import ai.victorious.filemanager.core.photo.ImageBatch
import ai.victorious.filemanager.photo.ExifTools
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "heic", "gif", "bmp")

@Composable
internal fun PhotoToolsScreen(paths: List<String>, outDir: String, onClose: () -> Unit, onEvent: (UiEvent) -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    val files = remember(paths) { paths.map(::File).filter { it.isFile } }
    val images = remember(files) { files.filter { it.extension.lowercase() in IMAGE_EXT } }
    ToolScaffold("Photo tools", onBack = onClose) { modifier ->
        Column(modifier.padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("EXIF", "Rename", "Resize").forEachIndexed { i, label ->
                    FilterChip(selected = tab == i, onClick = { tab = i }, label = { Text(label) })
                }
            }
            Text("${files.size} files · ${images.size} images", style = MaterialTheme.typography.bodySmall)
            when (tab) {
                0 -> ExifTab(images, onEvent)
                1 -> RenameTab(files, onEvent)
                else -> ResizeTab(images, outDir, onEvent)
            }
        }
    }
}

@Composable
private fun ExifTab(images: List<File>, onEvent: (UiEvent) -> Unit) {
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(images.firstOrNull()) }
    var attrs by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var editKey by remember { mutableStateOf(ExifInterfaceTag.DATETIME) }
    var editVal by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(0.35f)) {
            items(images) { f ->
                OutlinedButton(onClick = {
                    selected = f
                    attrs = runCatching { ExifTools.read(f) }.getOrDefault(emptyMap())
                }, modifier = Modifier.fillMaxWidth()) { Text(f.name) }
            }
        }
        Text(selected?.name ?: "Pick an image", style = MaterialTheme.typography.titleSmall)
        LazyColumn(Modifier.weight(0.4f)) {
            items(attrs.entries.toList()) { (k, v) ->
                Text("$k = $v", style = MaterialTheme.typography.bodySmall)
            }
        }
        OutlinedTextField(editKey, { editKey = it }, label = { Text("Tag") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(editVal, { editVal = it }, label = { Text("Value") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = selected != null && editKey.isNotBlank(), onClick = {
                val f = selected ?: return@Button
                scope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching { ExifTools.set(f, mapOf(editKey to editVal)); ExifTools.read(f) }
                    }.onSuccess { attrs = it; onEvent(UiEvent.ShowMessage("EXIF saved")) }
                        .onFailure { onEvent(UiEvent.ShowMessage(it.message ?: "EXIF write failed")) }
                }
            }) { Text("Set tag") }
            Button(enabled = selected != null, onClick = {
                val f = selected ?: return@Button
                scope.launch {
                    withContext(Dispatchers.IO) { runCatching { ExifTools.strip(f) } }
                        .onSuccess {
                            attrs = runCatching { ExifTools.read(f) }.getOrDefault(emptyMap())
                            onEvent(UiEvent.ShowMessage(if (it) "EXIF stripped" else "Strip failed"))
                        }
                        .onFailure { onEvent(UiEvent.ShowMessage(it.message ?: "failed")) }
                }
            }) { Text("Strip EXIF") }
        }
    }
}

private object ExifInterfaceTag {
    const val DATETIME = "DateTime"
}

@Composable
private fun RenameTab(files: List<File>, onEvent: (UiEvent) -> Unit) {
    val scope = rememberCoroutineScope()
    var pattern by remember { mutableStateOf("{name}-{index:00}.{ext}") }
    var start by remember { mutableStateOf("1") }
    val parent = files.firstOrNull()?.parentFile?.name.orEmpty()
    val preview = remember(files, pattern, start) {
        BatchRename.preview(files.map { it.name }, pattern, parent, start.toIntOrNull() ?: 1)
    }
    Column(Modifier.fillMaxSize()) {
        Text("Tokens: {name} {ext} {index} {index:00} {date:yyyyMMdd} {parent}", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(pattern, { pattern = it }, label = { Text("Pattern") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(start, { start = it }, label = { Text("Start index") }, singleLine = true)
        LazyColumn(Modifier.weight(1f).padding(vertical = 8.dp)) {
            items(preview) { p -> Text("${p.from} → ${p.to}", style = MaterialTheme.typography.bodySmall) }
        }
        Button(enabled = files.isNotEmpty(), onClick = {
            scope.launch {
                val msg = withContext(Dispatchers.IO) {
                    runCatching {
                        var n = 0
                        preview.forEachIndexed { i, p ->
                            if (p.from == p.to) return@forEachIndexed
                            val src = files[i]
                            val dest = File(src.parentFile, p.to)
                            if (dest.exists()) error("Exists: ${dest.name}")
                            if (!src.renameTo(dest)) error("Rename failed: ${src.name}")
                            n++
                        }
                        "Renamed $n files"
                    }.getOrElse { it.message ?: "failed" }
                }
                onEvent(UiEvent.ShowMessage(msg)); onEvent(UiEvent.Refresh)
            }
        }) { Text("Apply rename") }
    }
}

@Composable
private fun ResizeTab(images: List<File>, outDir: String, onEvent: (UiEvent) -> Unit) {
    val scope = rememberCoroutineScope()
    var maxW by remember { mutableStateOf("1920") }
    var maxH by remember { mutableStateOf("1920") }
    var quality by remember { mutableStateOf("85") }
    var format by remember { mutableStateOf("jpeg") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Batch resize / convert / compress into $outDir", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(maxW, { maxW = it }, label = { Text("Max width") }, singleLine = true)
        OutlinedTextField(maxH, { maxH = it }, label = { Text("Max height") }, singleLine = true)
        OutlinedTextField(quality, { quality = it }, label = { Text("JPEG/WebP quality 1–100") }, singleLine = true)
        Row { listOf("jpeg", "png", "webp").forEach { f ->
            FilterChip(selected = format == f, onClick = { format = f }, label = { Text(f) })
        } }
        val w = maxW.toIntOrNull() ?: 1920
        val h = maxH.toIntOrNull() ?: 1920
        Text("Example fit 4000×3000 → ${ImageBatch.fitWithin(4000, 3000, w, h)}")
        Button(enabled = images.isNotEmpty(), onClick = {
            scope.launch {
                val msg = withContext(Dispatchers.IO) {
                    runCatching {
                        File(outDir).mkdirs()
                        var ok = 0
                        for (img in images) {
                            val dest = File(outDir, ImageBatch.outputName(img.name, format))
                            if (ExifTools.resizeCompress(img, dest, w, h, quality.toIntOrNull() ?: 85, format)) ok++
                        }
                        "Wrote $ok / ${images.size} into ${File(outDir).name}"
                    }.getOrElse { it.message ?: "failed" }
                }
                onEvent(UiEvent.ShowMessage(msg)); onEvent(UiEvent.Refresh)
            }
        }) { Text("Run batch") }
    }
}
