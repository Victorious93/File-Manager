package ai.victorious.filemanager.ui

import ai.victorious.filemanager.core.translate.StringsXmlTranslate
import ai.victorious.filemanager.core.util.Codec
import ai.victorious.filemanager.core.util.Colors
import ai.victorious.filemanager.core.util.Hashes
import ai.victorious.filemanager.core.util.RegexTester
import ai.victorious.filemanager.core.util.Timestamps
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Side-by-side strings.xml translation. Selection: source strings.xml, optional target strings.xml / CSV. */
@Composable
fun TranslateStringsScreen(paths: List<String>, dir: String, onClose: () -> Unit, onEvent: (UiEvent) -> Unit) {
    val scope = rememberCoroutineScope()
    fun msg(s: String) = onEvent(UiEvent.ShowMessage(s))
    val xmls = paths.filter { it.endsWith(".xml", true) }
    val csvIn = paths.firstOrNull { it.endsWith(".csv", true) }
    val sourcePath = xmls.firstOrNull { File(it).parentFile?.name == "values" } ?: xmls.firstOrNull()
    var targetPath by remember { mutableStateOf(xmls.firstOrNull { it != sourcePath }) }
    var locale by remember { mutableStateOf("") }
    var onlyMissing by remember { mutableStateOf(false) }
    val edits = remember { mutableStateMapOf<String, String>() }
    var source by remember { mutableStateOf<List<StringsXmlTranslate.Entry>>(emptyList()) }
    var rows by remember { mutableStateOf<List<StringsXmlTranslate.Row>>(emptyList()) }
    fun load() {
        val sp = sourcePath ?: return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val s = StringsXmlTranslate.parse(File(sp).readText())
                    val t = targetPath?.let { File(it) }?.takeIf { it.exists() }?.let { StringsXmlTranslate.parse(it.readText()) } ?: emptyList()
                    s to StringsXmlTranslate.sideBySide(s, t)
                }
            }.onSuccess { (s, r) -> source = s; rows = r; edits.clear(); r.forEach { row -> row.target?.let { edits[row.name] = it } } }
                .onFailure { msg("Load failed: ${it.message}") }
        }
    }
    androidx.compose.runtime.LaunchedEffect(sourcePath, targetPath) { load() }
    ToolScaffold("Translate strings.xml", onClose) { modifier ->
        if (sourcePath == null) {
            Text("Select a decoded res/values/strings.xml (and optionally the target values-xx/strings.xml or a CSV) in Files, then open this tool.",
                modifier = modifier.padding(16.dp))
            return@ToolScaffold
        }
        Column(modifier.padding(horizontal = 12.dp)) {
            Text("Source: $sourcePath", style = MaterialTheme.typography.bodySmall)
            if (targetPath == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(locale, { locale = it }, label = { Text("Target locale (e.g. es, pt-rBR)") }, singleLine = true, modifier = Modifier.weight(1f))
                    Button(enabled = locale.isNotBlank(), onClick = { targetPath = StringsXmlTranslate.targetFile(File(sourcePath), locale).path }) { Text("Set") }
                }
            } else Text("Target: $targetPath", style = MaterialTheme.typography.bodySmall)
            val done = rows.count { !edits[it.name].isNullOrEmpty() }
            Text("$done / ${rows.size} translated", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(onlyMissing, { onlyMissing = !onlyMissing }, label = { Text("Only untranslated") })
                OutlinedButton(enabled = targetPath != null, onClick = {
                    val tp = targetPath ?: return@OutlinedButton
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { File(tp).also { it.parentFile?.mkdirs() }.writeText(StringsXmlTranslate.write(source, edits.toMap())) } }
                            .onSuccess { msg("Saved $tp") }.onFailure { msg("Save failed: ${it.message}") }
                    }
                }) { Text("Save target") }
                OutlinedButton(onClick = {
                    val out = File(dir, "strings-${locale.ifBlank { "translation" }}.csv")
                    val cur = rows.map { it.copy(target = edits[it.name]) }
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { out.writeText(StringsXmlTranslate.exportCsv(cur)) } }
                            .onSuccess { msg("Exported ${out.path}") }.onFailure { msg("Export failed: ${it.message}") }
                    }
                }) { Text("Export CSV") }
                OutlinedButton(enabled = csvIn != null, onClick = {
                    val c = csvIn ?: return@OutlinedButton
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { StringsXmlTranslate.importCsv(File(c).readText(), source) } }
                            .onSuccess { r -> edits.putAll(r.values); msg("Imported ${r.values.size}, skipped ${r.skipped.size}") }
                            .onFailure { msg("Import failed: ${it.message}") }
                    }
                }) { Text(if (csvIn != null) "Import ${File(csvIn).name}" else "Import CSV (select one)") }
            }
            val shown = if (onlyMissing) rows.filter { edits[it.name].isNullOrEmpty() } else rows
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(shown, key = { it.name }) { r ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(r.name, style = MaterialTheme.typography.labelSmall)
                            Text(r.source, style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedTextField(edits[r.name] ?: "", { edits[r.name] = it }, modifier = Modifier.weight(1f),
                            textStyle = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/** Hash, base64/hex, timestamp, colour and regex utilities. */
@Composable
fun UtilitiesScreen(selected: List<String>, onClose: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Hash", "Base64 / Hex", "Timestamp", "Color", "Regex")
    ToolScaffold("Utilities", onClose) { modifier ->
        Column(modifier) {
            ScrollableTabRow(selectedTabIndex = tab) { tabs.forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) } }
            Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (tab) {
                    0 -> HashTab(selected.map(::File).firstOrNull { it.isFile })
                    1 -> CodecTab()
                    2 -> TimestampTab()
                    3 -> ColorTab()
                    else -> RegexTab()
                }
            }
        }
    }
}

@Composable
private fun Mono(s: String) = SelectionContainer { Text(s, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }

@Composable
private fun HashTab(file: File?) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var expected by remember { mutableStateOf("") }
    var fileHashes by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    OutlinedTextField(text, { text = it }, label = { Text("Text") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(expected, { expected = it }, label = { Text("Expected hash (optional, to verify)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    for (a in Hashes.algorithms) {
        val h = Hashes.ofText(a, text)
        Text(a + if (expected.isNotBlank() && Hashes.matches(h, expected)) "  ✓ match" else "", style = MaterialTheme.typography.labelMedium)
        Mono(h)
    }
    if (file != null) {
        Button(onClick = {
            scope.launch { fileHashes = withContext(Dispatchers.IO) { Hashes.algorithms.map { it to runCatching { Hashes.ofFile(it, file) }.getOrElse { e -> "error: ${e.message}" } } } }
        }) { Text("Hash file ${file.name}") }
        fileHashes?.forEach { (a, h) ->
            Text(a + if (expected.isNotBlank() && Hashes.matches(h, expected)) "  ✓ match" else "", style = MaterialTheme.typography.labelMedium); Mono(h)
        }
    } else Text("Select a file in Files to hash it.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun CodecTab() {
    var input by remember { mutableStateOf("") }
    var out by remember { mutableStateOf("") }
    fun run(f: (String) -> String) { out = runCatching { f(input) }.getOrElse { "Error: ${it.message}" } }
    OutlinedTextField(input, { input = it }, label = { Text("Input") }, modifier = Modifier.fillMaxWidth())
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { run { Codec.base64Encode(it) } }) { Text("Base64 encode") }
        OutlinedButton(onClick = { run { Codec.base64Encode(it, urlSafe = true) } }) { Text("Base64url encode") }
        OutlinedButton(onClick = { run(Codec::base64Decode) }) { Text("Base64 decode") }
        OutlinedButton(onClick = { run(Codec::hexEncode) }) { Text("Hex encode") }
        OutlinedButton(onClick = { run(Codec::hexDecode) }) { Text("Hex decode") }
    }
    Mono(out)
}

@Composable
private fun TimestampTab() {
    var input by remember { mutableStateOf(System.currentTimeMillis().div(1000).toString()) }
    OutlinedTextField(input, { input = it }, label = { Text("Unix seconds / ms, or a date") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedButton(onClick = { input = (System.currentTimeMillis() / 1000).toString() }) { Text("Now") }
    runCatching { Timestamps.fromText(input) }.onSuccess {
        Mono("Seconds: ${it.epochSeconds}\nMillis:  ${it.epochMillis}\nUTC:     ${it.utc}\nLocal:   ${it.local}")
    }.onFailure { Text(it.message ?: "Invalid", color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun ColorTab() {
    var input by remember { mutableStateOf("#3F51B5") }
    val parsed = runCatching { Colors.parse(input) }
    OutlinedTextField(input, { input = it }, label = { Text("#RGB, #RRGGBB, #AARRGGBB or rgb()/rgba()") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    parsed.onSuccess { c ->
        Box(Modifier.size(72.dp).background(Color(c.argb)))
        for ((label, v) in listOf("R" to c.r, "G" to c.g, "B" to c.b, "A" to c.a)) {
            Text("$label $v", style = MaterialTheme.typography.labelSmall)
            Slider(v.toFloat(), { n ->
                val x = n.toInt()
                input = when (label) { "R" -> c.copy(r = x); "G" -> c.copy(g = x); "B" -> c.copy(b = x); else -> c.copy(a = x) }.hex()
            }, valueRange = 0f..255f)
        }
        val (h, s, v) = c.hsv()
        Mono("${c.hex()}\n${c.rgb()}\nhsv(%.0f, %.0f%%, %.0f%%)\nAndroid int: 0x%08X".format(h, s * 100, v * 100, c.argb))
    }.onFailure { Text(it.message ?: "Invalid", color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun RegexTab() {
    var pattern by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var ic by remember { mutableStateOf(false) }
    var ml by remember { mutableStateOf(false) }
    var da by remember { mutableStateOf(false) }
    OutlinedTextField(pattern, { pattern = it }, label = { Text("Pattern") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(ic, { ic = !ic }, label = { Text("Ignore case") })
        FilterChip(ml, { ml = !ml }, label = { Text("Multiline") })
        FilterChip(da, { da = !da }, label = { Text("Dot all") })
    }
    OutlinedTextField(input, { input = it }, label = { Text("Test text") }, minLines = 3, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(replacement, { replacement = it }, label = { Text("Replacement (optional, \$1 for groups)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    if (pattern.isNotEmpty()) {
        val r = RegexTester.run(pattern, input, ic, ml, da, replacement.ifEmpty { null })
        r.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("${r.hits.size} match(es)", style = MaterialTheme.typography.labelMedium)
        r.hits.take(100).forEach { h ->
            Mono("[${h.range.first}-${h.range.last}] ${h.value}" + h.groups.mapIndexed { i, g -> "\n   \$${i + 1} = $g" }.joinToString(""))
        }
        r.replaced?.let { Text("Replaced:", style = MaterialTheme.typography.labelMedium); Mono(it) }
    }
}
