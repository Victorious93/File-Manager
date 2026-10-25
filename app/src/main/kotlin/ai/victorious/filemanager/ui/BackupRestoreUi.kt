package ai.victorious.filemanager.ui

import ai.victorious.filemanager.FileManagerApplication
import ai.victorious.filemanager.core.fleet.BackupConflictPolicy
import ai.victorious.filemanager.core.fleet.BackupWrapping
import ai.victorious.filemanager.core.fleet.EnvelopeException
import ai.victorious.filemanager.core.fleet.ImportReport
import ai.victorious.filemanager.core.fleet.UnifiedFleetBackupCodec
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings > Backup & restore. Local only: config.export / config.import stay
 * remoteAllowed:false on the bridge. Import = pick file -> dry-run preview -> choose mode -> confirm.
 */
@Composable
fun BackupRestoreSection(onEvent: (UiEvent) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as FileManagerApplication
    val scope = rememberCoroutineScope()
    var pendingText by remember { mutableStateOf<String?>(null) }
    var policy by remember { mutableStateOf(BackupConflictPolicy.SKIP) }
    var preview by remember { mutableStateOf<ImportReport?>(null) }

    fun msg(t: Throwable) = (t as? EnvelopeException)?.let { "${it.category}: ${it.message}" } ?: "Failed: ${t.javaClass.simpleName}"

    fun runPreview(text: String, p: BackupConflictPolicy) {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { app.configBackup.import(text, p, dryRun = true) } }
                .onSuccess { preview = it }
                .onFailure { pendingText = null; preview = null; onEvent(UiEvent.ShowMessage(msg(it))) }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val bundle = app.configBackup.exportBundle(clearPayload = false, wrapping = BackupWrapping.ANDROID_KEYSTORE)
                    context.contentResolver.openOutputStream(uri, "wt")!!.use {
                        it.write(UnifiedFleetBackupCodec.encode(bundle).toString().toByteArray())
                    }
                }
            }.onSuccess { onEvent(UiEvent.ShowMessage("Settings exported (encrypted for this device)")) }
                .onFailure { onEvent(UiEvent.ShowMessage(msg(it))) }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)!!.use { s -> s.readBytes().takeIf { it.size <= 1_000_000 }?.decodeToString() }
                }
            }.getOrNull()
            if (text == null) { onEvent(UiEvent.ShowMessage("Could not read that file (max 1 MB)")); return@launch }
            pendingText = text
            runPreview(text, policy)
        }
    }

    Text("Backup & restore", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    Text(
        "Preferences (appearance) and bookmarks only. Never files, passwords, tokens or cloud logins. " +
            "Encrypted with a key kept in this device's keystore.",
        style = MaterialTheme.typography.bodySmall,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { exportLauncher.launch("victorfiles-settings.json") }) { Text("Export to file") }
        OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) }) { Text("Import from file") }
    }

    val text = pendingText
    val report = preview
    if (text != null && report != null) {
        AlertDialog(
            onDismissRequest = { pendingText = null; preview = null },
            title = { Text("Import settings?") },
            text = {
                Column {
                    Text("Preview (dry run, nothing written yet):", style = MaterialTheme.typography.labelLarge)
                    Text("Preferences: +${report.prefsAdded.size} added, ${report.prefsOverwritten.size} overwritten, ${report.prefsSkipped.size} skipped")
                    Text("Bookmarks: +${report.bookmarksAdded.size} added, ${report.bookmarksOverwritten.size} overwritten, ${report.bookmarksSkipped.size} skipped")
                    Text("Mode", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        BackupConflictPolicy.entries.forEach { p ->
                            FilterChip(selected = policy == p, onClick = { policy = p; runPreview(text, p) }, label = { Text(p.wire) })
                        }
                    }
                    Text("External control will be turned off after importing.", style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val p = policy
                    pendingText = null; preview = null
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { app.configBackup.import(text, p, dryRun = false) } }
                            .onSuccess { onEvent(UiEvent.ShowMessage("Settings imported (${p.wire})")) }
                            .onFailure { onEvent(UiEvent.ShowMessage(msg(it))) }
                    }
                }) { Text("Confirm import") }
            },
            dismissButton = { TextButton(onClick = { pendingText = null; preview = null }) { Text("Cancel") } },
        )
    }
}
