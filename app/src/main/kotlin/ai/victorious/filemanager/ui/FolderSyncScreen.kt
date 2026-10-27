package ai.victorious.filemanager.ui

import ai.victorious.filemanager.FileManagerApplication
import ai.victorious.filemanager.core.net.RemoteBackend
import ai.victorious.filemanager.core.net.SavedConnection
import ai.victorious.filemanager.core.sync.ConflictPolicy
import ai.victorious.filemanager.core.sync.FolderSync
import ai.victorious.filemanager.core.sync.RemoteSyncFs
import ai.victorious.filemanager.core.sync.SyncDirection
import ai.victorious.filemanager.core.sync.SyncFs
import ai.victorious.filemanager.core.sync.SyncJob
import ai.victorious.filemanager.core.sync.SyncJobRules
import ai.victorious.filemanager.core.sync.SyncJobRunner
import kotlinx.coroutines.sync.withLock
import ai.victorious.filemanager.sync.FolderSyncJobs
import ai.victorious.filemanager.sync.FolderSyncWorker
import ai.victorious.filemanager.sync.LocalSyncFs
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Folder sync UI. Each side is a local folder or a folder on a saved connection
 * (SFTP/FTP/SMB/WebDAV/S3/cloud). Dry-run preview, conflict policy, deletes → trash, and
 * WorkManager schedules with network (Wi-Fi only) and charging constraints for any pair.
 */
@Composable
internal fun FolderSyncScreen(defaultDir: String, onClose: () -> Unit, onEvent: (UiEvent) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as FileManagerApplication
    val scope = rememberCoroutineScope()
    val connections = remember { runCatching { app.connections.list() }.getOrDefault(emptyList()) }
    var aConn by remember { mutableStateOf<String?>(null) }
    var bConn by remember { mutableStateOf<String?>(null) }
    var aRoot by remember { mutableStateOf(defaultDir) }
    var bRoot by remember { mutableStateOf("") }
    var direction by remember { mutableStateOf(SyncDirection.TWO_WAY) }
    var conflict by remember { mutableStateOf(ConflictPolicy.NEWEST) }
    var preview by remember { mutableStateOf<List<String>>(emptyList()) }
    var scheduleHours by remember { mutableStateOf("24") }
    var wifiOnly by remember { mutableStateOf(true) }
    var charging by remember { mutableStateOf(false) }
    var jobs by remember { mutableStateOf(FolderSyncJobs.all(context)) }

    fun currentJob(id: String = "adhoc") = SyncJob(
        id = id, aConnectionId = aConn, aRoot = aRoot.trim(), bConnectionId = bConn, bRoot = bRoot.trim(),
        direction = direction, conflict = conflict, intervalHours = scheduleHours.toLongOrNull() ?: 24,
        wifiOnly = wifiOnly, requiresCharging = charging,
    )

    ToolScaffold("Folder sync", onBack = onClose) { modifier ->
        Column(modifier.padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Each side can be a local folder or a folder on a saved connection. Local deletes go to trash.", style = MaterialTheme.typography.bodySmall)
            SidePicker("Side A", connections, aConn, { c -> aConn = c; if (c != null) aRoot = connections.firstOrNull { it.id == c }?.initialPath ?: "/" }, aRoot) { aRoot = it }
            SidePicker("Side B", connections, bConn, { c -> bConn = c; if (c != null) bRoot = connections.firstOrNull { it.id == c }?.initialPath ?: "/" }, bRoot) { bRoot = it }
            Text("Direction", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState())) { SyncDirection.entries.forEach { d ->
                FilterChip(selected = direction == d, onClick = { direction = d }, label = { Text(d.name.replace('_', ' ').lowercase(), style = MaterialTheme.typography.labelSmall) })
            } }
            Text("Conflicts", style = MaterialTheme.typography.labelLarge)
            Row { ConflictPolicy.entries.forEach { c ->
                FilterChip(selected = conflict == c, onClick = { conflict = c }, label = { Text(c.name, style = MaterialTheme.typography.labelSmall) })
            } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        val job = currentJob()
                        val lines = withContext(Dispatchers.IO) {
                            runCatching {
                                withSides(app, job) { afs, bfs ->
                                    val plan = FolderSync.plan(
                                        afs.listRecursive(job.aRoot).filter { !it.isDirectory }.associateBy { it.relativePath },
                                        bfs.listRecursive(job.bRoot).filter { !it.isDirectory }.associateBy { it.relativePath },
                                        direction, conflict,
                                    )
                                    listOf("Dry-run: ${plan.copyCount} copies, ${plan.deleteCount} deletes, ${plan.conflicts.size} conflicts") +
                                        plan.actions.map { "${it.kind} ${it.relativePath} ${it.detail}" }
                                }
                            }.getOrElse { listOf("Preview failed: ${it.message}") }
                        }
                        preview = lines
                    }
                }) { Text("Dry-run preview") }
                Button(onClick = {
                    scope.launch {
                        val job = currentJob()
                        SyncJobRules.problem(job)?.let { onEvent(UiEvent.ShowMessage(it)); return@launch }
                        val msg = withContext(Dispatchers.IO) {
                            if (FolderSyncJobs.runLock.isLocked) return@withContext "A sync is already running; try again when it finishes"
                            FolderSyncJobs.runLock.withLock { runCatching {
                                withSides(app, job) { afs, bfs ->
                                    val out = SyncJobRunner.run(job, afs, bfs, trashOnDelete = { side, rel ->
                                        val local = if (side == 'A') job.aRoot.takeIf { !job.aRemote } else job.bRoot.takeIf { !job.bRemote }
                                        local != null && runCatching { app.trash.moveToTrash(File(local, rel).absolutePath) }.isSuccess
                                    })
                                    "Synced: ${out.summary}"
                                }
                            }.getOrElse { it.message ?: "sync failed" } }
                        }
                        onEvent(UiEvent.ShowMessage(msg)); onEvent(UiEvent.Refresh)
                    }
                }) { Text("Run sync") }
            }
            Text("Schedule", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(scheduleHours, { scheduleHours = it.filter(Char::isDigit) }, label = { Text("Every N hours") }, singleLine = true)
            SwitchRow("Wi-Fi only (remote sides)", wifiOnly) { wifiOnly = it }
            SwitchRow("Only while charging", charging) { charging = it }
            TextButton(onClick = {
                val job = currentJob(UUID.randomUUID().toString())
                val problem = SyncJobRules.problem(job)
                if (problem != null) onEvent(UiEvent.ShowMessage(problem))
                else scope.launch {
                    jobs = withContext(Dispatchers.IO) {
                        FolderSyncWorker.schedule(context, job)
                        FolderSyncJobs.all(context)
                    }
                    onEvent(UiEvent.ShowMessage("Scheduled every ${job.intervalHours} h"))
                }
            }) { Text("Save + schedule") }
            if (jobs.isNotEmpty()) {
                Text("Scheduled jobs", style = MaterialTheme.typography.labelLarge)
                jobs.values.sortedBy { it.id }.forEach { j ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${sideLabel(connections, j.aConnectionId, j.aRoot)} ⇄ ${sideLabel(connections, j.bConnectionId, j.bRoot)}", style = MaterialTheme.typography.bodySmall)
                            val constraints = listOfNotNull(
                                "every ${j.intervalHours} h",
                                if (j.involvesRemote) (if (j.wifiOnly) "Wi-Fi only" else "any network") else null,
                                if (j.requiresCharging) "charging" else null,
                            ).joinToString(" · ")
                            val last = j.lastRunEpochMs?.let { "last ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))}: ${j.lastResult}" } ?: "not run yet"
                            Text("$constraints — $last", style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(onClick = { FolderSyncWorker.cancel(context, j.id); jobs = FolderSyncJobs.all(context) }) { Text("Remove") }
                    }
                }
            }
            // Not a LazyColumn: this Column already scrolls, and a nested lazy list crashes on infinite height.
            Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                preview.take(PREVIEW_LIMIT).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (preview.size > PREVIEW_LIMIT) {
                    Text("… ${preview.size - PREVIEW_LIMIT} more", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun SidePicker(
    title: String,
    connections: List<SavedConnection>,
    selected: String?,
    onSelect: (String?) -> Unit,
    path: String,
    onPath: (String) -> Unit,
) {
    Text(title, style = MaterialTheme.typography.labelLarge)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text("Local") })
        connections.forEach { c ->
            FilterChip(selected = selected == c.id, onClick = { onSelect(c.id) }, label = { Text("${c.name} (${c.protocol.name})") })
        }
    }
    OutlinedTextField(
        path, onPath,
        label = { Text(if (selected == null) "$title local folder" else "$title folder on the server") },
        modifier = Modifier.fillMaxWidth(), singleLine = true,
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun sideLabel(connections: List<SavedConnection>, id: String?, root: String): String =
    if (id.isNullOrBlank()) root else "${connections.firstOrNull { it.id == id }?.name ?: id}:$root"

/** Opens both sides (fresh remote sessions), runs [block], then closes the sessions. */
private fun <T> withSides(app: FileManagerApplication, job: SyncJob, block: (SyncFs, SyncFs) -> T): T {
    require(job.aRoot.isNotBlank() && job.bRoot.isNotBlank()) { "Both sides need a folder" }
    val opened = mutableListOf<RemoteBackend>()
    fun open(id: String?): SyncFs =
        if (id.isNullOrBlank()) LocalSyncFs() else RemoteSyncFs(app.connections.openDetached(id).also { opened += it })
    try {
        return block(open(job.aConnectionId), open(job.bConnectionId))
    } finally {
        opened.forEach { runCatching { it.close() } }
    }
}

private const val PREVIEW_LIMIT = 500
