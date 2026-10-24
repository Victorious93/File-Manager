package ai.victorious.filemanager.ui

import ai.victorious.filemanager.FileManagerApplication
import ai.victorious.filemanager.apk.ApkInstaller
import ai.victorious.filemanager.apk.ApkRepository
import ai.victorious.filemanager.core.apk.DexTools
import ai.victorious.filemanager.core.patch.FileChange
import ai.victorious.filemanager.core.patch.ManifestFlag
import ai.victorious.filemanager.core.patch.PatchApplied
import ai.victorious.filemanager.core.patch.PatchGate
import ai.victorious.filemanager.core.patch.PatchNotAuthorizedException
import ai.victorious.filemanager.core.patch.PatchSession
import ai.victorious.filemanager.core.patch.PatchStep
import ai.victorious.filemanager.core.patch.SmaliPatch
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phase 15 one-tap static APK patch presets. Every apply path goes through [PatchGate] — the user
 * must confirm ownership/authorization for this package+signing-cert before anything is written.
 * No runtime hooks; no third-party APK/tooling is used.
 */
@Composable
fun PatchPresetsScreen(apkPath: String, outDir: String, onClose: () -> Unit, onEvent: (UiEvent) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as FileManagerApplication
    val apk = remember(apkPath) { File(apkPath) }
    val gate = remember {
        PatchGate(File(app.filesDir, "patch/authorizations.json"))
    }
    var session by remember { mutableStateOf<PatchSession?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var authorized by remember { mutableStateOf(false) }
    var showGate by remember { mutableStateOf(false) }
    var gateNote by remember { mutableStateOf("") }
    var gateAck by remember { mutableStateOf(false) }

    // Manifest presets (null = leave unchanged)
    var debuggable by remember { mutableStateOf<Boolean?>(null) }
    var allowBackup by remember { mutableStateOf<Boolean?>(null) }
    var cleartext by remember { mutableStateOf<Boolean?>(null) }
    var extractNative by remember { mutableStateOf<Boolean?>(null) }

    // Smali candidates from pattern scan
    var candidates by remember { mutableStateOf<List<SmaliPatch.MethodMatch>>(emptyList()) }
    val neutralize = remember { mutableStateMapOf<String, Boolean>() } // "type|sig" -> return true
    var toastClass by remember { mutableStateOf("") }
    var logClass by remember { mutableStateOf("") }
    var logSig by remember { mutableStateOf("") }
    var logTag by remember { mutableStateOf("FM") }
    var logMsg by remember { mutableStateOf("entered") }
    var wantTrustUserCa by remember { mutableStateOf(false) }

    var preview by remember { mutableStateOf<List<FileChange>>(emptyList()) }
    var applied by remember { mutableStateOf<PatchApplied?>(null) }
    var resultNote by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()

    fun buildSteps(): List<PatchStep> {
        val steps = mutableListOf<PatchStep>()
        debuggable?.let { steps += PatchStep.SetManifestFlag(ManifestFlag.DEBUGGABLE, it) }
        allowBackup?.let { steps += PatchStep.SetManifestFlag(ManifestFlag.ALLOW_BACKUP, it) }
        cleartext?.let { steps += PatchStep.SetManifestFlag(ManifestFlag.CLEARTEXT_TRAFFIC, it) }
        extractNative?.let { steps += PatchStep.SetManifestFlag(ManifestFlag.EXTRACT_NATIVE_LIBS, it) }
        for ((key, retTrue) in neutralize) {
            val (cls, sig) = key.split('|', limit = 2)
            steps += PatchStep.NeutralizeMethod(cls, sig, boolValue = retTrue)
        }
        if (toastClass.isNotBlank()) steps += PatchStep.RemoveToasts(toastClass.trim())
        if (logClass.isNotBlank() && logSig.isNotBlank()) {
            steps += PatchStep.InjectEntryLog(logClass.trim(), logSig.trim(), logTag.ifBlank { "FM" }, logMsg)
        }
        if (wantTrustUserCa) steps += PatchStep.TrustUserCertificates
        return steps
    }

    LaunchedEffect(apkPath) {
        withContext(Dispatchers.IO) {
            runCatching { PatchSession(apk, gate) }
        }.onSuccess { s ->
            session = s
            authorized = s.isAuthorized()
            debuggable = null
            allowBackup = null
            cleartext = null
            extractNative = null
        }.onFailure { error = it.message ?: "Cannot open APK" }
    }

    BackHandler(onBack = onClose)
    ToolScaffold("Patch presets · ${apk.name}", onBack = onClose) { modifier ->
        val s = session
        if (s == null) {
            Loading(modifier, error)
            return@ToolScaffold
        }
        Column(modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Static file edits only (manifest + smali → rebuild → re-sign). No runtime hooks. " +
                    "Authorize this package+cert before applying. See docs/PATCH_PRESETS.md.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Package: ${s.packageName}", style = MaterialTheme.typography.bodyMedium)
            Text("Cert SHA-256: ${s.certSha256}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (authorized) "Authorized for this package+cert" else "Not authorized — gate required",
                    color = if (authorized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                if (authorized) {
                    OutlinedButton(onClick = {
                        gate.revoke(s.packageName, s.certSha256)
                        authorized = false
                        onEvent(UiEvent.ShowMessage("Authorization revoked"))
                    }) { Text("Revoke") }
                } else {
                    Button(onClick = { gateAck = false; gateNote = ""; showGate = true }) { Text("Authorize…") }
                }
            }

            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Manifest flags (one-tap)", style = MaterialTheme.typography.titleSmall)
                FlagRow("debuggable", debuggable, s.summary.debuggable) { debuggable = it }
                FlagRow("allowBackup", allowBackup, s.summary.allowBackup == true) { allowBackup = it }
                FlagRow("usesCleartextTraffic", cleartext, s.summary.usesCleartextTraffic == true) { cleartext = it }
                FlagRow("extractNativeLibs", extractNative, s.summary.extractNativeLibs == true) { extractNative = it }

                Text("Signature / license check (pattern scan)", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Generic API/name signals only — no vendor list. You choose which methods to neutralize.",
                    style = MaterialTheme.typography.labelSmall,
                )
                OutlinedButton(
                    enabled = busy == null,
                    onClick = {
                        scope.launch {
                            busy = "Scanning DEX…"
                            val found = withContext(Dispatchers.Default) {
                                runCatching {
                                    val out = mutableListOf<SmaliPatch.MethodMatch>()
                                    for (src in DexTools.listDex(apk)) {
                                        val dex = DexTools.load(src)
                                        for (cls in dex.classes) {
                                            out += SmaliPatch.detect(cls.type, DexTools.classSmali(cls))
                                        }
                                    }
                                    out
                                }
                            }
                            busy = null
                            found.onSuccess {
                                candidates = it
                                neutralize.clear()
                                onEvent(UiEvent.ShowMessage("${it.size} candidate method(s)"))
                            }.onFailure { onEvent(UiEvent.ShowMessage("Scan failed: ${it.message}")) }
                        }
                    },
                ) { Text("Scan for candidates") }
                candidates.forEach { m ->
                    val key = "${m.classType}|${m.signature}"
                    val selected = neutralize.containsKey(key)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = selected,
                            onCheckedChange = { on ->
                                if (on) neutralize[key] = true else neutralize.remove(key)
                            },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(DexTools.descriptorToJava(m.classType), style = MaterialTheme.typography.bodySmall)
                            Text(m.signature, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                            Text(m.reasons.joinToString(" · "), style = MaterialTheme.typography.labelSmall)
                        }
                        if (selected && m.returnType == "Z") {
                            FilterChip(
                                selected = neutralize[key] == true,
                                onClick = { neutralize[key] = !(neutralize[key] ?: true) },
                                label = { Text(if (neutralize[key] == true) "→ true" else "→ false") },
                            )
                        }
                    }
                }

                Text("Developer aids", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    toastClass, { toastClass = it },
                    label = { Text("Remove toasts in class (e.g. Lcom/example/App;)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    logClass, { logClass = it },
                    label = { Text("Inject Log.d class type") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    logSig, { logSig = it },
                    label = { Text("Method signature (name(params)ret)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(logTag, { logTag = it }, label = { Text("tag") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(logMsg, { logMsg = it }, label = { Text("message") }, singleLine = true, modifier = Modifier.weight(2f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(wantTrustUserCa, { wantTrustUserCa = it })
                    Text(
                        "Trust user CAs (deferred — preview only; use cleartext for HTTP)",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                if (preview.isNotEmpty()) {
                    Text("Preview", style = MaterialTheme.typography.titleSmall)
                    preview.forEach { c ->
                        Text(
                            "${c.entry} — ${c.summary}" + if (!c.applicable) " (not applicable)" else "",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (c.applicable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                        )
                        Text(
                            c.diff.take(4000),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                            modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                        )
                    }
                }
            }

            busy?.let {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            resultNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = busy == null,
                    onClick = {
                        val steps = buildSteps()
                        if (steps.isEmpty()) {
                            onEvent(UiEvent.ShowMessage("Select at least one preset"))
                            return@OutlinedButton
                        }
                        scope.launch {
                            busy = "Preview…"
                            preview = withContext(Dispatchers.Default) { s.preview(steps) }
                            busy = null
                        }
                    },
                ) { Text("Preview") }
                Button(
                    enabled = busy == null,
                    onClick = {
                        if (!authorized) {
                            showGate = true
                            onEvent(UiEvent.ShowMessage("Authorize first — there is no bypass"))
                            return@Button
                        }
                        val steps = buildSteps().filter { it !is PatchStep.TrustUserCertificates }
                        if (steps.isEmpty()) {
                            onEvent(UiEvent.ShowMessage(if (wantTrustUserCa) "Trust user CAs is deferred" else "Nothing to apply"))
                            return@Button
                        }
                        scope.launch {
                            busy = "Applying + signing…"
                            resultNote = null
                            applied = null
                            val out = File(outDir, apk.nameWithoutExtension + "_patched.apk")
                            val r = withContext(Dispatchers.IO) {
                                runCatching {
                                    val key = ApkRepository(app).debugKey()
                                    s.apply(steps, out, key)
                                }
                            }
                            busy = null
                            r.onSuccess {
                                applied = it
                                resultNote = "Patched + verified (${it.result.changedEntries.joinToString()}). ${out.name}"
                                onEvent(UiEvent.ShowMessage(resultNote!!))
                                onEvent(UiEvent.Refresh)
                            }.onFailure {
                                val msg = when (it) {
                                    is PatchNotAuthorizedException -> "Not authorized (gate). Confirm ownership first."
                                    else -> "Patch failed: ${it.message}"
                                }
                                onEvent(UiEvent.ShowMessage(msg))
                            }
                        }
                    },
                ) { Text("Apply + sign") }
                OutlinedButton(
                    enabled = applied != null && busy == null,
                    onClick = {
                        val a = applied ?: return@OutlinedButton
                        a.undo()
                        resultNote = "Undone — original restored"
                        applied = null
                        onEvent(UiEvent.ShowMessage(resultNote!!))
                        onEvent(UiEvent.Refresh)
                    },
                ) { Text("Undo") }
                OutlinedButton(
                    enabled = applied != null && busy == null,
                    onClick = {
                        val path = applied?.output?.absolutePath ?: return@OutlinedButton
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

    if (showGate && session != null) {
        val s = session!!
        AlertDialog(
            onDismissRequest = { showGate = false },
            title = { Text("Authorize APK modification") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${s.packageName}", style = MaterialTheme.typography.titleSmall)
                    Text(s.certSha256, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                    Text(PatchGate.CONFIRMATION_TEXT, style = MaterialTheme.typography.bodyMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(gateAck, { gateAck = it })
                        Text("I agree to the statement above", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedTextField(
                        gateNote, { gateNote = it },
                        label = { Text("Optional note (e.g. my app / ticket)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = gateAck,
                    onClick = {
                        gate.authorize(s.packageName, s.certSha256, note = gateNote)
                        authorized = true
                        showGate = false
                        onEvent(UiEvent.ShowMessage("Authorized ${s.packageName}"))
                    },
                ) { Text("Confirm authorization") }
            },
            dismissButton = { TextButton(onClick = { showGate = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FlagRow(label: String, chosen: Boolean?, current: Boolean, onChoose: (Boolean?) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Text("now=${if (current) "true" else "false"}", style = MaterialTheme.typography.labelSmall)
        FilterChip(selected = chosen == null, onClick = { onChoose(null) }, label = { Text("skip") })
        FilterChip(selected = chosen == true, onClick = { onChoose(true) }, label = { Text("set true") })
        FilterChip(selected = chosen == false, onClick = { onChoose(false) }, label = { Text("set false") })
    }
}
