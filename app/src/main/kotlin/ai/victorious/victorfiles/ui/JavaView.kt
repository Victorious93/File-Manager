package ai.victorious.victorfiles.ui

import ai.victorious.victorfiles.FileManagerApplication
import ai.victorious.victorfiles.core.apk.DexClassInfo
import ai.victorious.victorfiles.core.apk.DexSource
import ai.victorious.victorfiles.core.apk.DexTools
import ai.victorious.victorfiles.core.javadec.DecompileOrigin
import ai.victorious.victorfiles.core.javadec.DexInsights
import ai.victorious.victorfiles.core.javadec.JavaExport
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Root folder for exported .java (sibling of the smali export root). */
fun javaExportRoot(target: File): File = File(surgeryExportRoot(target), "java")

/**
 * Readable Java for one class, decompiled off the main thread via the shared [DecompileService]
 * (cancelled automatically when this leaves composition). Supports in-code search, copy, export
 * to a .java file, and handing the code to the AI assistant's Explain.
 */
@Composable
fun JavaClassView(target: File, source: DexSource, cls: DexClassInfo, onEvent: (UiEvent) -> Unit) {
    val app = LocalContext.current.applicationContext as FileManagerApplication
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val input = remember(source) { source.container }

    var code by remember(cls, source) { mutableStateOf<String?>(null) }
    var origin by remember(cls, source) { mutableStateOf<DecompileOrigin?>(null) }
    var error by remember(cls, source) { mutableStateOf<String?>(null) }
    var query by remember(cls, source) { mutableStateOf("") }
    var showAi by remember(cls, source) { mutableStateOf(false) }

    LaunchedEffect(cls, source) {
        error = null; code = null
        runCatching { app.decompiler.decompile(input, cls.javaName) }
            .onSuccess { code = it.code; origin = it.origin }
            .onFailure { error = it.message ?: "Decompilation failed" }
    }

    error?.let { Loading("Java: $it"); return }
    val java = code ?: return Loading("Decompiling ${cls.javaName}…")

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { clipboard.setText(AnnotatedString(java)); onEvent(UiEvent.ShowMessage("Copied Java to clipboard")) }) { Text("Copy") }
            TextButton(onClick = {
                scope.launch {
                    val out = runCatching {
                        val f = JavaExport.fileIn(javaExportRoot(target), cls.javaName)
                        withContext(Dispatchers.IO) { f.parentFile?.mkdirs(); f.writeText(java) }
                        f
                    }
                    out.onSuccess { onEvent(UiEvent.OpenFile(it.absolutePath, ViewerMode.TEXT)) }
                        .onFailure { onEvent(UiEvent.ShowMessage(it.message ?: "Export failed")) }
                }
            }) { Text("Export .java") }
            TextButton(onClick = { showAi = !showAi }) { Text(if (showAi) "Hide AI" else "Explain") }
        }
        origin?.let {
            val tag = when (it) {
                DecompileOrigin.MEMORY -> "cached (memory)"
                DecompileOrigin.DISK -> "cached (disk)"
                DecompileOrigin.DECOMPILED -> "jadx"
            }
            Text("  $tag · ${java.length} chars", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp))
        }
        if (showAi) {
            AiPanel(
                fileName = cls.javaName.substringAfterLast('.') + ".java",
                language = "java",
                fullText = java,
                selection = null,
                onApply = { /* decompiled Java is read-only */ },
                onClose = { showAi = false },
            )
        }
        OutlinedTextField(query, { query = it }, label = { Text("Search in Java") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(8.dp))
        if (query.isBlank()) {
            CodeText(java)
        } else {
            val hits = remember(java, query) {
                java.lineSequence().withIndex()
                    .filter { it.value.contains(query, ignoreCase = true) }
                    .map { "${it.index + 1}: ${it.value}" }
                    .take(1000).toList()
            }
            if (hits.isEmpty()) Loading("No matches for \"$query\"")
            else CodeText(hits.joinToString("\n"))
        }
    }
}

/** String-constant (const-string) listing for one dex, with a filter. */
@Composable
fun DexStringsView(source: DexSource) {
    var strings by remember(source) { mutableStateOf<List<String>?>(null) }
    var error by remember(source) { mutableStateOf<String?>(null) }
    var filter by remember(source) { mutableStateOf("") }
    LaunchedEffect(source) {
        withContext(Dispatchers.Default) { runCatching { DexInsights.stringConstants(DexTools.load(source)) } }
            .onSuccess { strings = it }.onFailure { error = it.message ?: "Failed" }
    }
    error?.let { Loading(it); return }
    val all = strings ?: return Loading("Reading string constants…")
    val shown = remember(all, filter) { if (filter.isBlank()) all else all.filter { it.contains(filter, ignoreCase = true) } }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(filter, { filter = it }, label = { Text("Filter ${all.size} string constants") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(8.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(shown.take(5000)) { s -> Text(s, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** Class-list + method-signature diff between two dex sources. */
@Composable
fun DexCompareView(a: DexSource, b: DexSource) {
    var text by remember(a, b) { mutableStateOf<String?>(null) }
    LaunchedEffect(a, b) {
        withContext(Dispatchers.Default) {
            runCatching {
                val da = DexTools.load(a); val db = DexTools.load(b)
                val c = DexInsights.compareClasses(da, db)
                val m = DexInsights.compareMethods(da, db)
                buildString {
                    appendLine("# ${a.displayName}  vs  ${b.displayName}")
                    appendLine()
                    appendLine("Classes: ${c.common} common, ${c.onlyInA.size} only in A, ${c.onlyInB.size} only in B")
                    appendLine("Methods: ${m.common} common, ${m.onlyInA.size} only in A, ${m.onlyInB.size} only in B")
                    appendLine()
                    if (c.onlyInA.isNotEmpty()) { appendLine("## Classes only in A"); c.onlyInA.take(500).forEach { appendLine("  $it") }; appendLine() }
                    if (c.onlyInB.isNotEmpty()) { appendLine("## Classes only in B"); c.onlyInB.take(500).forEach { appendLine("  $it") }; appendLine() }
                    if (m.onlyInA.isNotEmpty()) { appendLine("## Methods only in A"); m.onlyInA.take(500).forEach { appendLine("  $it") } ; appendLine() }
                    if (m.onlyInB.isNotEmpty()) { appendLine("## Methods only in B"); m.onlyInB.take(500).forEach { appendLine("  $it") } }
                }
            }
        }.onSuccess { text = it }.onFailure { text = "Compare failed: ${it.message}" }
    }
    val t = text ?: return Loading("Comparing dex files…")
    CodeText(t)
}
