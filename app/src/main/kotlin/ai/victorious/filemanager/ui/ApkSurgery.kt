package ai.victorious.filemanager.ui

import ai.victorious.filemanager.core.apk.ApkResources
import ai.victorious.filemanager.core.apk.Arsc
import ai.victorious.filemanager.core.apk.ArscString
import ai.victorious.filemanager.core.apk.DexClassInfo
import ai.victorious.filemanager.core.apk.DexSource
import ai.victorious.filemanager.core.apk.DexTools
import ai.victorious.filemanager.core.apk.ResEntry
import ai.victorious.filemanager.core.apk.ResKind
import ai.victorious.filemanager.core.apk.SmaliText
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** True for targets the APK surgery screen can open. */
fun isSurgeryTarget(path: String): Boolean {
    val f = File(path)
    return if (f.isDirectory) File(f, "AndroidManifest.xml").isFile || DexTools.listDex(f).isNotEmpty()
    else isApk(path) || path.endsWith(".dex", ignoreCase = true)
}

/** Root folder for exported smali / decoded XML for a target. */
// Always a sibling folder so exports never end up inside an extracted APK tree (and thus in a rebuild).
fun surgeryExportRoot(target: File): File {
    val f = target.absoluteFile
    val suffix = if (f.isDirectory) "_export" else "_" + f.extension.lowercase() + "_export"
    return File(f.parentFile, (if (f.isDirectory) f.name else f.nameWithoutExtension) + suffix)
}

private sealed interface DexNav {
    data object Files : DexNav
    data class Classes(val source: DexSource) : DexNav
    data class ClassView(val source: DexSource, val cls: DexClassInfo) : DexNav
    data class MethodView(val source: DexSource, val cls: DexClassInfo, val signature: String) : DexNav
}

/**
 * APK surgery: DEX → classes → methods → smali (edit + assemble), ARSC string editor,
 * res/ + assets/ with binary-XML decoding (@ref names resolved), and readable AndroidManifest.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApkSurgeryScreen(path: String, onEvent: (UiEvent) -> Unit) {
    val target = remember(path) { File(path) }
    val isDexOnly = remember(path) { target.isFile && target.extension.equals("dex", true) }
    var tab by remember { mutableIntStateOf(0) }
    var nav by remember { mutableStateOf<DexNav>(DexNav.Files) }
    val close = { onEvent(UiEvent.CloseSurgery) }
    BackHandler {
        nav = when (val n = nav) {
            DexNav.Files -> { close(); n }
            is DexNav.Classes -> if (isDexOnly) { close(); n } else DexNav.Files
            is DexNav.ClassView -> DexNav.Classes(n.source)
            is DexNav.MethodView -> DexNav.ClassView(n.source, n.cls)
        }
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("APK surgery · ${target.name}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = close) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Close") } },
            actions = {
                if (target.isDirectory && File(target, "AndroidManifest.xml").isFile) {
                    TextButton(onClick = { onEvent(UiEvent.RebuildApk(target.absolutePath)) }) { Text("Rebuild") }
                } else if (isApk(target.path)) {
                    TextButton(onClick = { onEvent(UiEvent.RequestSign(target.absolutePath)) }) { Text("Sign…") }
                }
            },
        )
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!isDexOnly) {
                TabRow(selectedTabIndex = tab) {
                    listOf("DEX", "Strings", "Resources", "Manifest").forEachIndexed { i, t ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                    }
                }
            }
            when (if (isDexOnly) 0 else tab) {
                0 -> DexPane(target, isDexOnly, nav, { nav = it }, onEvent)
                1 -> StringsPane(target, onEvent)
                2 -> ResourcesPane(target, onEvent)
                else -> ManifestPane(target, onEvent)
            }
        }
    }
}

@Composable
private fun Loading(text: String? = null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (text == null) CircularProgressIndicator() else Text(text, Modifier.padding(24.dp))
    }
}

@Composable
private fun CodeText(text: String, modifier: Modifier = Modifier) {
    SelectionContainer(modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
        Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
    }
}

@Composable
private fun Row2(title: String, subtitle: String?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    HorizontalDivider()
}

/** Writes [text] to [file] on IO and opens it in the existing text editor. */
private suspend fun exportAndOpen(file: File, text: String, onEvent: (UiEvent) -> Unit) {
    val r = withContext(Dispatchers.IO) { runCatching { file.parentFile?.mkdirs(); file.writeText(text) } }
    r.onSuccess {
        onEvent(UiEvent.ShowMessage("Exported → ${file.absolutePath}"))
        onEvent(UiEvent.OpenFile(file.absolutePath, ViewerMode.TEXT))
    }.onFailure { onEvent(UiEvent.ShowMessage(it.message ?: "Export failed")) }
}

@Composable
private fun DexPane(target: File, isDexOnly: Boolean, nav: DexNav, setNav: (DexNav) -> Unit, onEvent: (UiEvent) -> Unit) {
    val scope = rememberCoroutineScope()
    var sources by remember(target) { mutableStateOf<List<DexSource>?>(null) }
    var error by remember(target) { mutableStateOf<String?>(null) }
    // Cache the parsed dex for the currently open source.
    var loaded by remember(target) { mutableStateOf<Pair<DexSource, DexBackedDexFile>?>(null) }
    var classes by remember(target) { mutableStateOf<List<DexClassInfo>?>(null) }
    var filter by remember(target) { mutableStateOf("") }
    var classSmali by remember(target) { mutableStateOf<Pair<String, String>?>(null) }

    LaunchedEffect(target) {
        withContext(Dispatchers.IO) { runCatching { DexTools.listDex(target) } }
            .onSuccess { s -> sources = s; if (isDexOnly && s.isNotEmpty()) setNav(DexNav.Classes(s.first())) }
            .onFailure { error = it.message ?: "Cannot read" }
    }
    val source = when (nav) {
        is DexNav.Classes -> nav.source
        is DexNav.ClassView -> nav.source
        is DexNav.MethodView -> nav.source
        DexNav.Files -> null
    }
    LaunchedEffect(source) {
        if (source != null && loaded?.first != source) {
            classes = null; error = null
            withContext(Dispatchers.IO) { runCatching { val d = DexTools.load(source); d to DexTools.classes(d) } }
                .onSuccess { (d, c) -> loaded = source to d; classes = c }
                .onFailure { error = it.message ?: "Cannot parse ${source.displayName}" }
        }
    }
    val clsDesc = (nav as? DexNav.ClassView)?.cls?.descriptor ?: (nav as? DexNav.MethodView)?.cls?.descriptor
    LaunchedEffect(clsDesc, loaded) {
        val d = loaded?.second
        if (clsDesc != null && d != null && classSmali?.first != clsDesc) {
            classSmali = null
            withContext(Dispatchers.Default) { runCatching { DexTools.classSmali(d, clsDesc) } }
                .onSuccess { classSmali = clsDesc to it }.onFailure { error = it.message ?: "Disassembly failed" }
        }
    }
    error?.let { Loading(it); return }
    when (nav) {
        DexNav.Files -> {
            val s = sources ?: return Loading()
            if (s.isEmpty()) return Loading("No classes*.dex found")
            LazyColumn(Modifier.fillMaxSize()) {
                items(s, key = { it.displayName }) { src ->
                    Row2(src.displayName, if (src.entryName != null) "inside ${src.container.name}" else src.container.parent) {
                        setNav(DexNav.Classes(src))
                    }
                }
            }
        }
        is DexNav.Classes -> {
            val c = classes ?: return Loading()
            val shown = remember(c, filter) { if (filter.isBlank()) c else c.filter { it.javaName.contains(filter, ignoreCase = true) } }
            Column(Modifier.fillMaxSize()) {
                OutlinedTextField(filter, { filter = it }, label = { Text("Filter ${c.size} classes in ${nav.source.displayName}") },
                    singleLine = true, modifier = Modifier.fillMaxWidth().padding(8.dp))
                LazyColumn(Modifier.fillMaxSize()) {
                    items(shown.take(2000), key = { it.descriptor }) { cls ->
                        Row2(cls.javaName, "${cls.methodCount} methods · ${cls.fieldCount} fields") { setNav(DexNav.ClassView(nav.source, cls)) }
                    }
                    if (shown.size > 2000) item { Text("…${shown.size - 2000} more — refine the filter", Modifier.padding(16.dp)) }
                }
            }
        }
        is DexNav.ClassView -> {
            val smali = classSmali?.takeIf { it.first == nav.cls.descriptor }?.second ?: return Loading()
            val blocks = remember(smali) { SmaliText.methods(smali) }
            Column(Modifier.fillMaxSize()) {
                val exportFile = remember(nav.cls) { DexTools.smaliExportFile(surgeryExportRoot(target), nav.cls.descriptor) }
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(nav.cls.javaName, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = {
                        scope.launch { exportAndOpen(exportFile, smali, onEvent) }
                    }) { Text("Edit") }
                    if (target.isDirectory) {
                        TextButton(onClick = {
                            if (!exportFile.isFile) {
                                onEvent(UiEvent.ShowMessage("Tap Edit, save changes, then Assemble"))
                            } else {
                                onEvent(UiEvent.AssembleSmali(exportFile.absolutePath, target.absolutePath))
                            }
                        }) { Text("Assemble") }
                    }
                }
                var showWhole by remember(nav.cls) { mutableStateOf(false) }
                TextButton(onClick = { showWhole = !showWhole }) { Text(if (showWhole) "Show method list" else "Show whole class smali") }
                if (showWhole) CodeText(smali)
                else LazyColumn(Modifier.fillMaxSize()) {
                    items(blocks, key = { it.signature + it.header.hashCode() }) { b ->
                        Row2(b.signature, b.header.removePrefix(".method ").substringBeforeLast(' ').ifBlank { null }) {
                            setNav(DexNav.MethodView(nav.source, nav.cls, b.signature))
                        }
                    }
                }
            }
        }
        is DexNav.MethodView -> {
            val smali = classSmali?.takeIf { it.first == nav.cls.descriptor }?.second ?: return Loading()
            val text = remember(smali, nav.signature) { SmaliText.method(smali, nav.signature) ?: "Method not found" }
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(nav.signature, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = {
                        val base = DexTools.smaliExportFile(surgeryExportRoot(target), nav.cls.descriptor)
                        val safe = nav.signature.substringBefore('(').replace(Regex("[^A-Za-z0-9_$-]"), "_")
                        scope.launch { exportAndOpen(File(base.parentFile, base.nameWithoutExtension + "." + safe + ".smali"), text, onEvent) }
                    }) { Text("Export") }
                }
                CodeText(text)
            }
        }
    }
}

@Composable
private fun ResourcesPane(target: File, onEvent: (UiEvent) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember(target) { mutableStateOf<List<ResEntry>?>(null) }
    var error by remember(target) { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf("") }
    LaunchedEffect(target) {
        withContext(Dispatchers.IO) { runCatching { ApkResources.list(target) } }
            .onSuccess { entries = it }.onFailure { error = it.message ?: "Cannot read" }
    }
    error?.let { Loading(it); return }
    val all = entries ?: return Loading()
    if (all.isEmpty()) return Loading("No res/ or assets/ files")
    val shown = remember(all, filter) { if (filter.isBlank()) all else all.filter { it.path.contains(filter, ignoreCase = true) } }
    fun open(e: ResEntry) = scope.launch {
        val r = withContext(Dispatchers.IO) {
            runCatching {
                when (e.kind) {
                    ResKind.XML -> {
                        val out = File(surgeryExportRoot(target), "decoded/" + e.path)
                        out.parentFile?.mkdirs(); out.writeText(ApkResources.readableXml(target, e.path))
                        out to ViewerMode.TEXT
                    }
                    else -> {
                        val f = if (target.isDirectory) File(target, e.path) else File(context.cacheDir, "surgery/" + e.path).also {
                            it.parentFile?.mkdirs(); it.writeBytes(ApkResources.read(target, e.path))
                        }
                        f to if (e.kind == ResKind.IMAGE) ViewerMode.IMAGE else defaultMode(f)
                    }
                }
            }
        }
        r.onSuccess { (f, mode) -> onEvent(UiEvent.OpenFile(f.absolutePath, mode)) }
            .onFailure { onEvent(UiEvent.ShowMessage(it.message ?: "Cannot open ${e.name}")) }
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(filter, { filter = it }, label = { Text("Filter ${all.size} files in res/ + assets/") },
            singleLine = true, modifier = Modifier.fillMaxWidth().padding(8.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(shown.take(3000), key = { it.path }) { e ->
                Row2(e.path, "${e.kind.name.lowercase()} · ${e.size} B") { open(e) }
            }
        }
    }
}

@Composable
private fun ManifestPane(target: File, onEvent: (UiEvent) -> Unit) {
    val scope = rememberCoroutineScope()
    var text by remember(target) { mutableStateOf<String?>(null) }
    var error by remember(target) { mutableStateOf<String?>(null) }
    LaunchedEffect(target) {
        withContext(Dispatchers.IO) { runCatching { ApkResources.manifestXml(target) } }
            .onSuccess { text = it }.onFailure { error = it.message ?: "No AndroidManifest.xml" }
    }
    error?.let { Loading(it); return }
    val t = text ?: return Loading()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (target.isDirectory) "Edits to the binary manifest need Rebuild APK + Sign." else "Read-only view.",
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { scope.launch { exportAndOpen(File(surgeryExportRoot(target), "decoded/AndroidManifest.xml"), t, onEvent) } }) {
                Text("Export")
            }
        }
        CodeText(t)
    }
}

@Composable
private fun StringsPane(target: File, onEvent: (UiEvent) -> Unit) {
    var arsc by remember(target) { mutableStateOf<Arsc?>(null) }
    var error by remember(target) { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf("") }
    var locale by remember { mutableStateOf<String?>(null) }
    var edits by remember(target) { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var editing by remember { mutableStateOf<ArscString?>(null) }
    var draft by remember { mutableStateOf("") }
    LaunchedEffect(target) {
        withContext(Dispatchers.IO) { runCatching { Arsc.loadFromTarget(target) } }
            .onSuccess { arsc = it }.onFailure { error = it.message ?: "No resources.arsc" }
    }
    error?.let { Loading(it); return }
    val a = arsc ?: return Loading()
    val locales = remember(a) { a.locales() }
    val shown = remember(a, filter, locale, edits) {
        a.search(filter, locale).map { s -> edits[s.poolIndex]?.let { s.copy(value = it) } ?: s }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(filter, { filter = it }, label = { Text("Search ${a.strings.size} strings") },
                singleLine = true, modifier = Modifier.weight(1f))
            TextButton(enabled = edits.isNotEmpty() && target.isDirectory, onClick = {
                onEvent(UiEvent.SaveArscEdits(target.absolutePath, edits))
                edits = emptyMap()
            }) { Text("Save (${edits.size})") }
        }
        if (!target.isDirectory) {
            Text("Open an extracted folder to edit & save strings.", Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
            TextButton(onClick = { locale = null }) { Text(if (locale == null) "[all]" else "all") }
            locales.forEach { loc ->
                TextButton(onClick = { locale = loc }) { Text(if (locale == loc) "[$loc]" else loc) }
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(shown.take(2000), key = { "${it.resId}:${it.locale}:${it.poolIndex}" }) { s ->
                Row2(
                    "0x${Integer.toHexString(s.resId)} · ${s.name} (${s.locale})",
                    s.value,
                ) {
                    editing = s; draft = s.value
                }
            }
        }
    }
    editing?.let { s ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Edit ${s.name}") },
            text = {
                OutlinedTextField(draft, { draft = it }, modifier = Modifier.fillMaxWidth(), label = { Text(s.locale) })
            },
            confirmButton = {
                TextButton(onClick = {
                    edits = edits + (s.poolIndex to draft)
                    editing = null
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}
