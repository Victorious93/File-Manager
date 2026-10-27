package ai.victorious.filemanager.ui

import ai.victorious.filemanager.core.apk.Arsc
import ai.victorious.filemanager.core.apk.ArscEditor
import ai.victorious.filemanager.core.apk.ArscLocaleAdder
import ai.victorious.filemanager.core.apk.ArscStringAdder
import ai.victorious.filemanager.core.apk.mtnp.StringDecryptEmulator
import ai.victorious.filemanager.core.apk.ApkMod
import ai.victorious.filemanager.core.apk.ModPlan
import ai.victorious.filemanager.FileManagerApplication
import ai.victorious.filemanager.apk.ApkRepository
import androidx.compose.ui.platform.LocalContext
import ai.victorious.filemanager.core.apk.DexTools
import ai.victorious.filemanager.core.apk.mtnp.ArscTranslate
import ai.victorious.filemanager.core.apk.mtnp.DecryptMethod
import ai.victorious.filemanager.core.apk.mtnp.DexMergeSplit
import ai.victorious.filemanager.core.apk.mtnp.DexRepair
import ai.victorious.filemanager.core.apk.mtnp.DexStringEdit
import ai.victorious.filemanager.core.apk.mtnp.StringDecrypt
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * DEX repair / merge-split / string decrypt+edit and ARSC translation mode.
 * Written against public formats + dexlib2; user approves every write.
 */
@Composable
internal fun MtNpScreen(target: File, outDir: String, onClose: () -> Unit, onEvent: (UiEvent) -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Repair", "Merge/Split", "Decrypt", "Emulate", "DEX strings", "Add strings", "Translate")
    ToolScaffold("DEX / ARSC tools", onBack = onClose) { modifier ->
        Column(modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tabs.forEachIndexed { i, label ->
                    FilterChip(selected = tab == i, onClick = { tab = i }, label = { Text(label) })
                }
            }
            when (tab) {
                0 -> RepairTab(target, outDir, onEvent)
                1 -> MergeSplitTab(target, outDir, onEvent)
                2 -> DecryptTab(target, outDir, onEvent)
                3 -> EmulateTab(target, outDir, onEvent)
                4 -> DexStringsTab(target, outDir, onEvent)
                5 -> AddStringsTab(target, outDir, onEvent)
                else -> TranslateTab(target, outDir, onEvent)
            }
        }
    }
}

@Composable
private fun RepairTab(target: File, outDir: String, onEvent: (UiEvent) -> Unit) {
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf("Loading…") }
    var dexBytes by remember { mutableStateOf<ByteArray?>(null) }
    var dexName by remember { mutableStateOf("classes.dex") }
    LaunchedEffect(target) {
        withContext(Dispatchers.IO) {
            runCatching {
                val list = DexTools.listDex(target)
                val first = list.firstOrNull() ?: error("No DEX in ${target.name}")
                dexName = first.displayName
                val b = first.readBytes()
                dexBytes = b
                report = formatReport(DexRepair.inspect(b))
            }.onFailure { report = it.message ?: "failed" }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(dexName, style = MaterialTheme.typography.titleSmall)
        Text(report, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        Button(onClick = {
            val src = dexBytes ?: return@Button
            scope.launch {
                withContext(Dispatchers.Default) {
                    runCatching {
                        val (fixed, r) = DexRepair.repair(src)
                        val out = File(outDir, "${target.nameWithoutExtension}-repaired-$dexName")
                        out.writeBytes(fixed)
                        dexBytes = fixed
                        report = formatReport(r)
                        out
                    }
                }.onSuccess { onEvent(UiEvent.ShowMessage("Wrote ${it.name}")); onEvent(UiEvent.Refresh) }
                    .onFailure { onEvent(UiEvent.ShowMessage(it.message ?: "repair failed")) }
            }
        }) { Text("Repair → save beside target") }
    }
}

private fun formatReport(r: DexRepair.Report): String = buildString {
    appendLine("healthy=${r.healthy}")
    appendLine("magic=${r.magicOk} endian=${r.endianOk} header=${r.headerSize}")
    appendLine("size declared=${r.declaredFileSize} actual=${r.actualSize}")
    appendLine("checksum=${r.checksumOk} signature=${r.signatureOk}")
    appendLine("map_off=${r.mapOff} mapOk=${r.mapOk}")
    r.mapIssues.forEach { appendLine("map: $it") }
    r.fixesApplied.forEach { appendLine("fix: $it") }
}

@Composable
private fun MergeSplitTab(target: File, outDir: String, onEvent: (UiEvent) -> Unit) {
    val scope = rememberCoroutineScope()
    var log by remember { mutableStateOf("") }
    var perDex by remember { mutableStateOf("1") }
    var policy by remember { mutableStateOf(DexMergeSplit.DuplicatePolicy.FIRST) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Merge all classes*.dex (pick duplicate winner), rebalance under 64k methods, or split by class count.", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DexMergeSplit.DuplicatePolicy.entries.forEach { p ->
                FilterChip(selected = policy == p, onClick = { policy = p }, label = { Text(p.name) })
            }
        }
        OutlinedTextField(perDex, { perDex = it }, label = { Text("Classes per DEX (split)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val parts = DexTools.listDex(target)
                            require(parts.isNotEmpty()) { "No DEX" }
                            val merged = DexMergeSplit.merge(parts.map { it.readBytes() }, policy)
                            val out = File(outDir, "${target.nameWithoutExtension}-merged.dex")
                            out.writeBytes(merged.dex)
                            log = "Merged ${parts.size} → ${merged.classCount} classes; skipped ${merged.skippedDuplicates.size}\n→ ${out.name}"
                            out
                        }
                    }.onSuccess { onEvent(UiEvent.ShowMessage(log)); onEvent(UiEvent.Refresh) }
                        .onFailure { log = it.message ?: "fail"; onEvent(UiEvent.ShowMessage(log)) }
                }
            }) { Text("Merge") }
            Button(onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val n = perDex.toIntOrNull() ?: 1
                            val first = DexTools.listDex(target).firstOrNull() ?: error("No DEX")
                            val parts = DexMergeSplit.splitByClassCount(first.readBytes(), n)
                            val dir = File(outDir, "${target.nameWithoutExtension}-split").apply { mkdirs() }
                            parts.forEach { File(dir, it.name).writeBytes(it.dex) }
                            log = "Split into ${parts.size}: " + parts.joinToString { "${it.name}(${it.classCount})" }
                            dir
                        }
                    }.onSuccess { onEvent(UiEvent.ShowMessage(log)); onEvent(UiEvent.Refresh) }
                        .onFailure { log = it.message ?: "fail"; onEvent(UiEvent.ShowMessage(log)) }
                }
            }) { Text("Split") }
            Button(onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val parts = DexTools.listDex(target)
                            require(parts.isNotEmpty()) { "No DEX" }
                            val dups = DexMergeSplit.findDuplicates(parts.map { it.readBytes() })
                            val bal = DexMergeSplit.mergeAndRebalance(parts.map { it.readBytes() }, policy)
                            val dir = File(outDir, "${target.nameWithoutExtension}-rebalanced").apply { mkdirs() }
                            bal.forEach { File(dir, it.name).writeBytes(it.dex) }
                            log = buildString {
                                appendLine("Duplicates: ${dups.size}")
                                dups.entries.take(10).forEach { (t, idxs) -> appendLine("  $t @ $idxs") }
                                appendLine("Rebalanced → ${bal.size} DEX (policy=${policy.name})")
                                bal.forEach { appendLine("  ${it.name}: ${it.classCount} classes, ${it.methodCount} methods") }
                            }
                            dir
                        }
                    }.onSuccess { onEvent(UiEvent.ShowMessage("Rebalanced")); onEvent(UiEvent.Refresh) }
                        .onFailure { log = it.message ?: "fail"; onEvent(UiEvent.ShowMessage(log)) }
                }
            }) { Text("Rebalance 64k") }
        }
        Text(log, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DecryptTab(target: File, outDir: String, onEvent: (UiEvent) -> Unit) {
    val scope = rememberCoroutineScope()
    var hits by remember { mutableStateOf<List<Pair<String, List<ai.victorious.filemanager.core.apk.mtnp.DecryptCandidate>>>>(emptyList()) }
    var selected by remember { mutableStateOf<Pair<String, ai.victorious.filemanager.core.apk.mtnp.DecryptCandidate>?>(null) }
    var keyHex by remember { mutableStateOf("") }
    var method by remember { mutableStateOf(DecryptMethod.BASE64) }
    var preview by remember { mutableStateOf("") }
    LaunchedEffect(target) {
        withContext(Dispatchers.Default) {
            runCatching {
                val dex = DexTools.listDex(target).firstOrNull()?.readBytes() ?: return@runCatching
                val strings = DexStringEdit.listStrings(dex).map { it.value }
                hits = StringDecrypt.scanDexStrings(strings)
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Text("Candidates (preview only — apply writes a new DEX beside the target).", style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.weight(1f)) {
            items(hits) { (orig, cands) ->
                Text(orig.take(80), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                cands.take(3).forEach { c ->
                    TextButton(onClick = {
                        selected = orig to c
                        method = c.method
                        keyHex = c.keyHint.removePrefix("—")
                        preview = c.preview
                    }) { Text("${c.method} key=${c.keyHint} → ${c.preview.take(60)}", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DecryptMethod.entries.forEach { m ->
                FilterChip(selected = method == m, onClick = { method = m }, label = { Text(m.name, style = MaterialTheme.typography.labelSmall) })
            }
        }
        OutlinedTextField(keyHex, { keyHex = it }, label = { Text("Key hex (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(preview, { preview = it }, label = { Text("Preview / edited plaintext") }, modifier = Modifier.fillMaxWidth())
        Button(enabled = selected != null && preview.isNotEmpty(), onClick = {
            val (orig, _) = selected ?: return@Button
            scope.launch {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val list = DexTools.listDex(target)
                        val first = list.first()
                        val plain = preview.ifBlank {
                            StringDecrypt.apply(orig, method, keyHex.ifBlank { null }) ?: error("decrypt failed")
                        }
                        val outBytes = DexStringEdit.replaceOne(first.readBytes(), orig, plain)
                        val out = File(outDir, "${target.nameWithoutExtension}-decrypted.dex")
                        out.writeBytes(outBytes)
                        out
                    }
                }.onSuccess { onEvent(UiEvent.ShowMessage("Wrote ${it.name}")); onEvent(UiEvent.Refresh) }
                    .onFailure { onEvent(UiEvent.ShowMessage(it.message ?: "failed")) }
            }
        }) { Text("Apply selected → new DEX") }
    }
}

@Composable
private fun DexStringsTab(target: File, outDir: String, onEvent: (UiEvent) -> Unit) {
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf("") }
    var strings by remember { mutableStateOf<List<DexStringEdit.StringHit>>(emptyList()) }
    var old by remember { mutableStateOf("") }
    var neu by remember { mutableStateOf("") }
    LaunchedEffect(target) {
        withContext(Dispatchers.IO) {
            runCatching {
                val b = DexTools.listDex(target).firstOrNull()?.readBytes() ?: return@runCatching
                strings = DexStringEdit.listStrings(b)
            }
        }
    }
    val shown = remember(strings, filter) {
        if (filter.isBlank()) strings.take(200) else strings.filter { it.value.contains(filter, true) }.take(200)
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(filter, { filter = it }, label = { Text("Filter") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f)) {
            items(shown) { s ->
                TextButton(onClick = { old = s.value; neu = s.value }) {
                    Text("[${s.index}] ${s.value.take(100)}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        OutlinedTextField(old, { old = it }, label = { Text("Old") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(neu, { neu = it }, label = { Text("New") }, modifier = Modifier.fillMaxWidth())
        Button(enabled = old.isNotEmpty() && old != neu, onClick = {
            scope.launch {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val b = DexTools.listDex(target).first().readBytes()
                        val out = File(outDir, "${target.nameWithoutExtension}-strings.dex")
                        out.writeBytes(DexStringEdit.replaceOne(b, old, neu))
                        out
                    }
                }.onSuccess { onEvent(UiEvent.ShowMessage("Wrote ${it.name}")); onEvent(UiEvent.Refresh) }
                    .onFailure { onEvent(UiEvent.ShowMessage(it.message ?: "failed")) }
            }
        }) { Text("Replace in first DEX") }
    }
}

@Composable
private fun TranslateTab(target: File, outDir: String, onEvent: (UiEvent) -> Unit) {
    val scope = rememberCoroutineScope()
    var arsc by remember { mutableStateOf<Arsc?>(null) }
    var rows by remember { mutableStateOf<List<ArscTranslate.Row>>(emptyList()) }
    var locs by remember { mutableStateOf<List<String>>(emptyList()) }
    var srcLoc by remember { mutableStateOf("default") }
    var dstLoc by remember { mutableStateOf("") }
    var aiDraft by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    LaunchedEffect(target) {
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = when {
                    target.isFile && target.name.endsWith(".apk", true) ->
                        ZipFile(target).use { z -> z.getInputStream(z.getEntry("resources.arsc")).readBytes() }
                    target.name.equals("resources.arsc", true) -> target.readBytes()
                    else -> error("Select an APK or resources.arsc")
                }
                val a = Arsc.open(bytes)
                arsc = a
                locs = a.locales()
                srcLoc = locs.firstOrNull() ?: "default"
                dstLoc = locs.firstOrNull { it != srcLoc }.orEmpty()
                rows = ArscTranslate.sideBySide(a)
            }.onFailure { status = it.message ?: "open failed" }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Text(status.ifBlank { "Locales: ${locs.joinToString()}" }, style = MaterialTheme.typography.bodySmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = {
                val a = arsc ?: return@OutlinedButton
                scope.launch {
                    val csv = ArscTranslate.exportCsv(a, locs)
                    val f = File(outDir, "strings-export.csv")
                    withContext(Dispatchers.IO) { f.writeText(csv) }
                    onEvent(UiEvent.ShowMessage("Exported ${f.name}")); onEvent(UiEvent.Refresh)
                }
            }) { Text("Export CSV") }
            OutlinedButton(onClick = {
                val a = arsc ?: return@OutlinedButton
                scope.launch {
                    val xml = ArscTranslate.exportXml(a, srcLoc)
                    val f = File(outDir, "strings-$srcLoc.xml")
                    withContext(Dispatchers.IO) { f.writeText(xml) }
                    onEvent(UiEvent.ShowMessage("Exported ${f.name}")); onEvent(UiEvent.Refresh)
                }
            }) { Text("Export XML ($srcLoc)") }
            OutlinedButton(onClick = {
                val a = arsc ?: return@OutlinedButton
                if (dstLoc.isBlank()) return@OutlinedButton
                aiDraft = ArscTranslate.aiPrompt(rows, srcLoc, dstLoc)
                status = "AI prompt ready — paste into assistant, then paste response below (approve before apply)."
            }) { Text("AI prompt") }
        }
        OutlinedTextField(aiDraft, { aiDraft = it }, label = { Text("AI response (name<TAB>text) or prompt") }, modifier = Modifier.fillMaxWidth().weight(0.4f))
        Button(onClick = {
            val a = arsc ?: return@Button
            if (dstLoc.isBlank()) { onEvent(UiEvent.ShowMessage("No target locale")); return@Button }
            scope.launch {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val suggestions = ArscTranslate.parseAiResponse(a, dstLoc, aiDraft)
                        require(suggestions.isNotEmpty()) { "No suggestions parsed" }
                        val edits = suggestions.associate {
                            ArscEditor.ResTarget(it.resId, dstLoc) to it.suggestedText
                        }
                        // Only apply if locale already exists
                        val present = a.locales().toSet()
                        require(dstLoc in present) { "Locale $dstLoc is not in this APK yet — use \"Add a new language\" below" }
                        val outBytes = ArscTranslate.apply(a, edits)
                        val out = File(outDir, "${target.nameWithoutExtension}-translated.arsc")
                        out.writeBytes(outBytes)
                        status = "Applied ${edits.size} suggestions → ${out.name}"
                        out
                    }
                }.onSuccess { onEvent(UiEvent.ShowMessage(status)); onEvent(UiEvent.Refresh) }
                    .onFailure { onEvent(UiEvent.ShowMessage(it.message ?: "apply failed")) }
            }
        }) { Text("Apply AI suggestions (user-approved)") }
        NewLanguageSection(target, arsc, outDir, onEvent)
        LazyColumn(Modifier.weight(0.6f)) {
            items(rows.take(100)) { r ->
                Text(
                    "${r.name}: " + locs.joinToString(" | ") { loc -> "$loc=${r.values[loc].orEmpty().take(40)}" },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}


@Composable
private fun EmulateTab(target: File, outDir: String, onEvent: (UiEvent) -> Unit) {
    val scope = rememberCoroutineScope()
    var methods by remember { mutableStateOf<List<StringDecryptEmulator.DecryptorMethod>>(emptyList()) }
    var selected by remember { mutableStateOf<StringDecryptEmulator.DecryptorMethod?>(null) }
    var results by remember { mutableStateOf<List<StringDecryptEmulator.EmulationResult>>(emptyList()) }
    var log by remember { mutableStateOf("Opt-in only. Restricted interpreter: no reflection, IO, or network; instruction + time budgets.") }
    var writeBack by remember { mutableStateOf(false) }
    LaunchedEffect(target) {
        withContext(Dispatchers.Default) {
            runCatching {
                val dexes = DexTools.listDex(target).map { DexTools.load(it.readBytes()) }
                methods = StringDecryptEmulator.findDecryptors(dexes, minCallSites = 1)
                log = if (methods.isEmpty()) "No likely decryptors found." else "Found ${methods.size} candidate(s)."
            }.onFailure { log = it.message ?: "scan failed" }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Text(log, style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.weight(1f)) {
            items(methods) { m ->
                TextButton(onClick = { selected = m; results = emptyList() }) {
                    Text("${m.signature} sites=${m.callSites} score=${m.score}\n${m.reason}", style = MaterialTheme.typography.bodySmall)
                }
            }
            items(results) { r ->
                Text(
                    if (r.ok) "${r.arg} → ${r.plaintext}" else "${r.arg} ✗ ${r.error}",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = writeBack, onClick = { writeBack = !writeBack }, label = { Text("Write const-string") })
            Button(enabled = selected != null, onClick = {
                val meth = selected ?: return@Button
                scope.launch {
                    withContext(Dispatchers.Default) {
                        runCatching {
                            val sources = DexTools.listDex(target)
                            val bytes = sources.map { it.readBytes() }
                            val dexes = bytes.map { DexTools.load(it) }
                            val emulated = StringDecryptEmulator.emulateCallSites(dexes, meth)
                            results = emulated
                            val okMap = emulated.filter { it.ok }.associate { it.arg to it.plaintext!! }
                            log = "Emulated ${emulated.size}: ${okMap.size} ok"
                            if (writeBack && okMap.isNotEmpty()) {
                                val outBytes = StringDecryptEmulator.inlineIntoDex(bytes.first(), meth, okMap)
                                val out = File(outDir, "${target.nameWithoutExtension}-inlined.dex")
                                out.writeBytes(outBytes)
                                log += " → wrote ${out.name}"
                            }
                        }
                    }.onSuccess { onEvent(UiEvent.ShowMessage(log)); if (writeBack) onEvent(UiEvent.Refresh) }
                        .onFailure { log = it.message ?: "emulate failed"; onEvent(UiEvent.ShowMessage(log)) }
                }
            }) { Text("Emulate call sites") }
        }
    }
}

@Composable
private fun AddStringsTab(target: File, outDir: String, onEvent: (UiEvent) -> Unit) {
    val app = LocalContext.current.applicationContext as FileManagerApplication
    val scope = rememberCoroutineScope()
    var arscLines by remember { mutableStateOf("hello_world\tHello, world") }
    var dexLines by remember { mutableStateOf("added-dex-constant") }
    var log by remember { mutableStateOf("Add new ARSC string resources (all configs) and/or DEX string constants, then rebuild + sign.") }
    val isApk = target.isFile && target.name.endsWith(".apk", true)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(log, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            arscLines, { arscLines = it },
            label = { Text("New ARSC strings: name<TAB>text per line") },
            modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 6,
        )
        OutlinedTextField(
            dexLines, { dexLines = it },
            label = { Text("New DEX constants (one per line)") },
            modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4,
        )
        Button(enabled = isApk, onClick = {
            scope.launch {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val arscMap = ArscStringAdder.fromLines(arscLines)
                        val dexList = dexLines.lines().map { it.trim() }.filter { it.isNotEmpty() }
                        require(arscMap.isNotEmpty() || dexList.isNotEmpty()) { "Enter at least one ARSC or DEX string" }
                        val out = File(outDir, "${target.nameWithoutExtension}-added-strings.apk")
                        ApkMod.applyTo(
                            target,
                            ModPlan(newArscStrings = arscMap, newDexStrings = dexList),
                            out,
                            ApkRepository(app).debugKey(),
                        )
                        "Added arsc=${arscMap.size} dex=${dexList.size} → ${out.name} (signed)"
                    }
                }.onSuccess { log = it; onEvent(UiEvent.ShowMessage(it)); onEvent(UiEvent.Refresh) }
                    .onFailure { log = it.message ?: "failed"; onEvent(UiEvent.ShowMessage(log)) }
            }
        }) { Text(if (isApk) "Add strings → rebuild + sign" else "Open an APK") }
    }
}

/**
 * Adds a brand-new language (values-xx) to the APK: pick or type a locale, fill in
 * `name<TAB>text` lines, and get a new signed `<name>-<locale>.apk`. Untranslated strings fall
 * back to the default language.
 */
@Composable
private fun NewLanguageSection(target: File, arsc: Arsc?, outDir: String, onEvent: (UiEvent) -> Unit) {
    val app = LocalContext.current.applicationContext as FileManagerApplication
    val scope = rememberCoroutineScope()
    var locale by remember { mutableStateOf("") }
    var lines by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val isApk = target.isFile && target.name.endsWith(".apk", true)
    val existing = arsc?.locales().orEmpty().toSet()
    val parsed = runCatching { ArscLocaleAdder.parseLocale(locale) }
    val localeError = when {
        locale.isBlank() -> null
        parsed.isFailure -> parsed.exceptionOrNull()?.message
        parsed.getOrThrow().qualifier in existing -> "Already in this APK; use Apply above to edit it"
        else -> null
    }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text("Add a new language", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ArscLocaleAdder.SUGGESTED.forEach { code ->
                val q = ArscLocaleAdder.parseLocale(code).qualifier
                FilterChip(
                    selected = locale.equals(code, true),
                    onClick = { locale = code },
                    enabled = q !in existing,
                    label = { Text(code) },
                )
            }
        }
        OutlinedTextField(
            locale, { locale = it.take(8) },
            label = { Text("Language code (e.g. eo, pt-BR)") },
            singleLine = true,
            isError = localeError != null,
            supportingText = { localeError?.let { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { arsc?.let { lines = ArscLocaleAdder.templateLines(it) } }, enabled = arsc != null) {
                Text("Fill with default strings")
            }
        }
        OutlinedTextField(
            lines, { lines = it },
            label = { Text("Translations: name<TAB>text per line") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3, maxLines = 6,
        )
        Button(
            enabled = !busy && isApk && arsc != null && locale.isNotBlank() && localeError == null,
            onClick = {
                val a = arsc ?: return@Button
                busy = true
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        runCatching {
                            val loc = ArscLocaleAdder.parseLocale(locale)
                            val texts = ArscLocaleAdder.textsFromLines(a, lines)
                            require(texts.isNotEmpty()) { "No lines matched a default string name" }
                            val out = File(outDir, "${target.nameWithoutExtension}-${loc.qualifier.replace("-r", "-")}.apk")
                            ApkMod.applyTo(target, ModPlan(newLocales = mapOf(loc.qualifier to texts)), out, ApkRepository(app).debugKey())
                            "Added ${loc.qualifier} (${texts.size} strings) → ${out.name}, signed with the File-Manager debug key"
                        }
                    }
                    busy = false
                    onEvent(UiEvent.ShowMessage(r.getOrElse { it.message ?: "Could not add language" }))
                    if (r.isSuccess) onEvent(UiEvent.Refresh)
                }
            },
        ) { Text(if (isApk) "Add language and build APK" else "Open an APK to add a language") }
    }
}
