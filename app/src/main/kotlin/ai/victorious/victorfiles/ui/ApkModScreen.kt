package ai.victorious.victorfiles.ui

import ai.victorious.victorfiles.FileManagerApplication
import ai.victorious.victorfiles.apk.ApkInstaller
import ai.victorious.victorfiles.apk.ApkRepository
import ai.victorious.victorfiles.core.apk.ApkMod
import ai.victorious.victorfiles.core.apk.ApkSigning
import ai.victorious.victorfiles.core.apk.Arsc
import ai.victorious.victorfiles.core.apk.ArscEditor
import ai.victorious.victorfiles.core.apk.ManifestEdit
import ai.victorious.victorfiles.core.apk.ManifestEditor
import ai.victorious.victorfiles.core.apk.ManifestSummary
import ai.victorious.victorfiles.core.apk.ModPlan
import ai.victorious.victorfiles.core.apk.ApkResources
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One-tap APK mod: edit manifest fields / string resources / paste smali, then rebuild, sign
 * (File-Manager debug key or a user keystore — never a bundled release key), verify, and optionally install.
 */
@Composable
fun ApkModScreen(apkPath: String, outDir: String, onClose: () -> Unit, onEvent: (UiEvent) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as FileManagerApplication
    val apk = remember(apkPath) { File(apkPath) }
    var summary by remember { mutableStateOf<ManifestSummary?>(null) }
    var strings by remember { mutableStateOf<List<ArscEditor.StringRes>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var resultPath by remember { mutableStateOf<String?>(null) }
    var resultNote by remember { mutableStateOf<String?>(null) }

    var pkg by remember { mutableStateOf("") }
    var versionCode by remember { mutableStateOf("") }
    var versionName by remember { mutableStateOf("") }
    var minSdk by remember { mutableStateOf("") }
    var targetSdk by remember { mutableStateOf("") }
    var debuggable by remember { mutableStateOf(false) }
    var addPerm by remember { mutableStateOf("") }
    var removePerms by remember { mutableStateOf("") }
    var smali by remember { mutableStateOf("") }
    var useDebugKey by remember { mutableStateOf(true) }
    var keystore by remember { mutableStateOf("") }
    var storePass by remember { mutableStateOf("") }
    var alias by remember { mutableStateOf("") }
    var keyPass by remember { mutableStateOf("") }
    var stringQuery by remember { mutableStateOf("") }
    val stringEdits = remember { mutableStateMapOf<String, String>() } // "resId|locale" -> value
    val exportedEdits = remember { mutableStateMapOf<String, Boolean>() }

    val scope = rememberCoroutineScope()

    LaunchedEffect(apkPath) {
        withContext(Dispatchers.IO) {
            runCatching {
                val s = ManifestEditor.summary(ApkResources.read(apk, "AndroidManifest.xml"))
                val arsc = runCatching { Arsc.loadFromTarget(apk) }.getOrNull()
                s to (arsc?.let { ArscEditor.strings(it) } ?: emptyList())
            }
        }.onSuccess { (s, str) ->
            summary = s
            strings = str
            pkg = s.packageName
            versionCode = s.versionCode?.toString().orEmpty()
            versionName = s.versionName.orEmpty()
            minSdk = s.minSdk?.toString().orEmpty()
            targetSdk = s.targetSdk?.toString().orEmpty()
            debuggable = s.debuggable
        }.onFailure { error = it.message ?: "Cannot open APK" }
    }

    fun buildPlan(): ModPlan {
        val s = summary ?: error("not loaded")
        val rem = removePerms.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        val add = addPerm.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        val manifest = ManifestEdit(
            packageName = pkg.takeIf { it.isNotBlank() && it != s.packageName },
            versionCode = versionCode.toIntOrNull()?.takeIf { it != s.versionCode },
            versionName = versionName.takeIf { it.isNotBlank() && it != s.versionName },
            minSdk = minSdk.toIntOrNull()?.takeIf { it != s.minSdk },
            targetSdk = targetSdk.toIntOrNull()?.takeIf { it != s.targetSdk },
            debuggable = debuggable.takeIf { it != s.debuggable },
            addPermissions = add,
            removePermissions = rem,
            exported = exportedEdits.toMap(),
        )
        val strEdits = stringEdits.mapNotNull { (k, v) ->
            val (idHex, loc) = k.split('|', limit = 2)
            val id = idHex.toLongOrNull(16)?.toInt() ?: return@mapNotNull null
            val orig = strings.firstOrNull { it.resId == id && it.locale == loc }?.value
            if (orig == v) null else ArscEditor.ResTarget(id, loc) to v
        }.toMap()
        val smaliList = smali.trim().takeIf { it.isNotEmpty() }?.let { listOf(it) } ?: emptyList()
        return ModPlan(manifest, strEdits, smaliList)
    }

    BackHandler(onBack = onClose)
    ToolScaffold("APK mod · ${apk.name}", onBack = onClose) { modifier ->
        if (summary == null) Loading(modifier, error) else Column(modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Edits stay inside the sealed APK. Sign with the File-Manager debug key or your own keystore — never a bundled release key.", style = MaterialTheme.typography.bodySmall)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Manifest", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(pkg, { pkg = it }, label = { Text("Package name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(versionCode, { versionCode = it }, label = { Text("versionCode") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(versionName, { versionName = it }, label = { Text("versionName") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(minSdk, { minSdk = it }, label = { Text("minSdk") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(targetSdk, { targetSdk = it }, label = { Text("targetSdk") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Debuggable", Modifier.weight(1f))
                    Switch(checked = debuggable, onCheckedChange = { debuggable = it })
                }
                OutlinedTextField(addPerm, { addPerm = it }, label = { Text("Add permissions (comma-separated)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(removePerms, { removePerms = it }, label = { Text("Remove permissions (comma-separated)") }, modifier = Modifier.fillMaxWidth())
                Text("Exported components", style = MaterialTheme.typography.titleSmall)
                summary!!.components.forEach { c ->
                    val value = exportedEdits[c.className] ?: c.exported
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.className, style = MaterialTheme.typography.bodySmall)
                            Text(c.tag, style = MaterialTheme.typography.labelSmall)
                        }
                        if (value != null) {
                            FilterChip(
                                selected = value,
                                onClick = { exportedEdits[c.className] = !value },
                                label = { Text(if (value) "exported" else "not exported") },
                            )
                        }
                    }
                }
                Text("String resources", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(stringQuery, { stringQuery = it }, label = { Text("Filter strings") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                strings.filter {
                    stringQuery.isBlank() || it.name.contains(stringQuery, true) || it.value.contains(stringQuery, true) || it.locale.contains(stringQuery, true)
                }.take(40).forEach { s ->
                    val key = Integer.toHexString(s.resId) + "|" + s.locale
                    val value = stringEdits[key] ?: s.value
                    OutlinedTextField(
                        value = value,
                        onValueChange = { stringEdits[key] = it },
                        label = { Text("${s.name} (${s.locale})") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (strings.size > 40) Text("Showing 40 of ${strings.size} — refine the filter.", style = MaterialTheme.typography.labelSmall)
                Text("Smali (optional — paste one class)", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(smali, { smali = it }, label = { Text(".class …") }, minLines = 4, modifier = Modifier.fillMaxWidth())
                Text("Signing", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("File-Manager debug key", Modifier.weight(1f))
                    Switch(checked = useDebugKey, onCheckedChange = { useDebugKey = it })
                }
                if (!useDebugKey) {
                    OutlinedTextField(keystore, { keystore = it }, label = { Text("Keystore path") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(storePass, { storePass = it }, label = { Text("Store password") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(alias, { alias = it }, label = { Text("Alias") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(keyPass, { keyPass = it }, label = { Text("Key password (blank = store)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            }
            busy?.let {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            resultNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = busy == null, onClick = {
                    scope.launch {
                        busy = "Applying mod…"
                        resultNote = null
                        resultPath = null
                        val plan = runCatching { buildPlan() }
                        if (plan.isFailure) {
                            onEvent(UiEvent.ShowMessage(plan.exceptionOrNull()?.message ?: "Invalid edit"))
                            busy = null
                            return@launch
                        }
                        if (plan.getOrThrow().isEmpty) {
                            onEvent(UiEvent.ShowMessage("Nothing to change"))
                            busy = null
                            return@launch
                        }
                        val out = File(outDir, apk.nameWithoutExtension + "_mod_signed.apk")
                        val r = withContext(Dispatchers.IO) {
                            runCatching {
                                val key = if (useDebugKey) {
                                    ApkRepository(app).debugKey()
                                } else {
                                    ApkSigning.loadKey(
                                        File(keystore.trim()),
                                        storePass.toCharArray(),
                                        alias.ifBlank { null },
                                        keyPass.takeIf { it.isNotEmpty() }?.toCharArray(),
                                    )
                                }
                                ApkMod.applyTo(apk, plan.getOrThrow(), out, key)
                            }
                        }
                        busy = null
                        r.onSuccess {
                            resultPath = out.absolutePath
                            resultNote = "Signed + verified (${it.changedEntries.joinToString()}). ${out.name}"
                            onEvent(UiEvent.ShowMessage(resultNote!!))
                            onEvent(UiEvent.Refresh)
                        }.onFailure { onEvent(UiEvent.ShowMessage("Mod failed: ${it.message}")) }
                    }
                }) { Text("Rebuild + sign + verify") }
                OutlinedButton(
                    enabled = resultPath != null && busy == null,
                    onClick = {
                        val path = resultPath ?: return@OutlinedButton
                        scope.launch {
                            busy = "Installing…"
                            val msg = withContext(Dispatchers.IO) {
                                runCatching { ApkInstaller.install(context, listOf(File(path))) }.fold({ it }, { it.message })
                            }
                            busy = null
                            onEvent(UiEvent.ShowMessage(msg ?: "Install started"))
                        }
                    },
                ) { Text("Install") }
            }
        }
    }
}
