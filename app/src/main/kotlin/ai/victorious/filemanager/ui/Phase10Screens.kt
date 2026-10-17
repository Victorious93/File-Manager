package ai.victorious.filemanager.ui

import ai.victorious.filemanager.core.apk.ApkCompare
import ai.victorious.filemanager.core.apk.ApkComparison
import ai.victorious.filemanager.core.apk.DexTools
import ai.victorious.filemanager.core.deobf.DecryptionHint
import ai.victorious.filemanager.core.deobf.DexHit
import ai.victorious.filemanager.core.deobf.DexHitKind
import ai.victorious.filemanager.core.deobf.DexSearch
import ai.victorious.filemanager.core.deobf.ProguardMapping
import ai.victorious.filemanager.core.deobf.SmaliExport
import ai.victorious.filemanager.core.deobf.StringDecryptionHints
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Manifest / permission / DEX class / zip-entry diff of two APKs (read-only). */
@Composable
internal fun ApkCompareScreen(left: File, right: File, saveDir: String, onClose: () -> Unit, onEvent: (UiEvent) -> Unit) {
    var result by remember { mutableStateOf<ApkComparison?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val report = remember(result) { result?.let(ApkCompare::report) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(left, right) {
        withContext(Dispatchers.IO) { runCatching { ApkCompare.compare(left, right) } }
            .onSuccess { result = it }.onFailure { error = "Cannot compare: ${it.message ?: it.javaClass.simpleName}" }
    }
    ToolScaffold("Compare APKs", onBack = onClose, actions = {
        TextButton(enabled = report != null, onClick = {
            val text = report ?: return@TextButton
            scope.launch {
                val out = File(saveDir, "apk-compare-${left.nameWithoutExtension}-vs-${right.nameWithoutExtension}.txt")
                withContext(Dispatchers.IO) { runCatching { out.writeText(text) } }
                    .onSuccess { onEvent(UiEvent.ShowMessage("Saved ${out.name}")); onEvent(UiEvent.Refresh) }
                    .onFailure { onEvent(UiEvent.ShowMessage("Save failed: ${it.message}")) }
            }
        }) { Text("Save report") }
    }) { modifier ->
        val r = result
        if (r == null || report == null) Loading(modifier, error) else Column(modifier.padding(12.dp)) {
            Text(
                if (r.identical) "No differences" else
                    "Permissions +${r.permissions.added.size}/−${r.permissions.removed.size} · " +
                        "classes +${r.classes.added.size}/−${r.classes.removed.size}/~${r.changedClasses.size} · " +
                        "entries +${r.entries.added.size}/−${r.entries.removed.size}/~${r.changedEntries.size}",
                style = MaterialTheme.typography.titleSmall,
            )
            r.warnings.forEach { Text("⚠ $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            SelectionContainer(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                Text(report, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * Optional deobfuscation helpers for an APK / .dex: class, method and string search (mapping-aware),
 * string-decryption hints, and exporting smali with a ProGuard/R8 mapping.txt applied.
 */
@Composable
internal fun SmaliToolsScreen(target: File, mappingFile: File?, outDir: String, onClose: () -> Unit, onEvent: (UiEvent) -> Unit) {
    var dexes by remember { mutableStateOf<List<Pair<String, com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile>>?>(null) }
    var mapping by remember { mutableStateOf<ProguardMapping?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var kinds by remember { mutableStateOf(DexHitKind.entries.toSet()) }
    var hits by remember { mutableStateOf<List<DexHit>>(emptyList()) }
    var hints by remember { mutableStateOf<List<DecryptionHint>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var exportJob by remember { mutableStateOf<Job?>(null) }
    var exported by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(target, mappingFile) {
        withContext(Dispatchers.IO) {
            runCatching {
                val d = DexTools.listDex(target).map { it.displayName to DexTools.load(it) }
                require(d.isNotEmpty()) { "No classes*.dex in ${target.name}" }
                d to mappingFile?.let { ProguardMapping.parse(it.readText()) }
            }
        }.onSuccess { (d, m) -> dexes = d; mapping = m }
            .onFailure { error = "Cannot read ${target.name}: ${it.message}" }
    }
    LaunchedEffect(query, kinds, dexes, mapping) {
        val d = dexes ?: return@LaunchedEffect
        if (query.trim().length < 2) { hits = emptyList(); return@LaunchedEffect }
        delay(250) // debounce; a new keystroke cancels this effect
        hits = withContext(Dispatchers.Default) { DexSearch.search(d, query, kinds, mapping, limit = 300) }
    }

    ToolScaffold("Smali tools · ${target.name}", onBack = { exportJob?.cancel(); onClose() }) { modifier ->
        val d = dexes
        if (d == null) Loading(modifier, error) else Column(modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "${d.size} dex · ${d.sumOf { it.second.classes.size }} classes · " +
                    (mapping?.let { "mapping.txt: ${it.classCount} classes" } ?: "no mapping (select a mapping.txt with the APK to apply one)"),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(query, { query = it }, label = { Text("Search classes / methods / strings") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (k in DexHitKind.entries) {
                    FilterChip(selected = k in kinds, onClick = { kinds = if (k in kinds) kinds - k else kinds + k },
                        label = { Text(k.name.lowercase().replaceFirstChar { it.uppercase() }) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = busy == null, onClick = {
                    scope.launch {
                        busy = "Scanning for decryption helpers…"
                        hints = withContext(Dispatchers.Default) { runCatching { StringDecryptionHints.find(d.map { it.second }) }.getOrDefault(emptyList()) }
                        busy = null
                    }
                }) { Text("String decryption hints") }
                Button(enabled = busy == null, onClick = {
                    val base = target.nameWithoutExtension + if (mapping != null) "-deobf-smali" else "-smali"
                    val out = File(outDir, base)
                    exportJob = scope.launch {
                        busy = "Exporting smali…"; exported = 0
                        val job = coroutineContext[Job]
                        withContext(Dispatchers.IO) {
                            runCatching {
                                SmaliExport.export(d.map { it.second }, out, mapping, isCancelled = { job?.isActive == false }) { n -> exported = n }
                            }
                        }.onSuccess { onEvent(UiEvent.ShowMessage("Exported $it classes to ${out.name}")); onEvent(UiEvent.Refresh) }
                            .onFailure { if (isActive) onEvent(UiEvent.ShowMessage("Export failed: ${it.message}")) }
                        busy = null
                    }
                }) { Text(if (mapping != null) "Export deobfuscated smali" else "Export smali") }
            }
            busy?.let {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(if (exported > 0) "$it $exported classes" else it, style = MaterialTheme.typography.bodySmall)
            }
            LazyColumn(Modifier.fillMaxSize()) {
                hints?.let { hs ->
                    item { Text("Decryption hints (${hs.size}) — heuristics only, nothing is executed", style = MaterialTheme.typography.titleSmall) }
                    items(hs) { h ->
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text(h.signature, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            Text("score ${h.score} · ${h.callSites} call sites · ${h.reason}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if (query.trim().length >= 2) item { Text("${hits.size} match(es)", style = MaterialTheme.typography.titleSmall) }
                items(hits) { h ->
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text("${h.kind.name.lowercase()} · ${h.dexName}", style = MaterialTheme.typography.labelSmall)
                        Text(h.detail, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
