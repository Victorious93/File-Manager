package ai.victorious.filemanager.ui

import ai.victorious.filemanager.FileManagerApplication
import ai.victorious.filemanager.core.net.DefaultRemoteBackendFactory
import ai.victorious.filemanager.core.sync.ConflictPolicy
import ai.victorious.filemanager.core.sync.FolderSync
import ai.victorious.filemanager.core.sync.RemoteSyncFs
import ai.victorious.filemanager.core.sync.SyncDirection
import ai.victorious.filemanager.core.sync.SyncFs
import ai.victorious.filemanager.sync.FolderSyncWorker
import ai.victorious.filemanager.sync.LocalSyncFs
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Folder sync UI: local paths (and optional saved network connection id for side B).
 * Dry-run preview, conflict policy, WorkManager schedule, deletes → trash.
 */
@Composable
internal fun FolderSyncScreen(defaultDir: String, onClose: () -> Unit, onEvent: (UiEvent) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as FileManagerApplication
    val scope = rememberCoroutineScope()
    var aRoot by remember { mutableStateOf(defaultDir) }
    var bRoot by remember { mutableStateOf("") }
    var direction by remember { mutableStateOf(SyncDirection.TWO_WAY) }
    var conflict by remember { mutableStateOf(ConflictPolicy.NEWEST) }
    var preview by remember { mutableStateOf<List<String>>(emptyList()) }
    var scheduleHours by remember { mutableStateOf("24") }
    var connectionIdB by remember { mutableStateOf("") } // optional: use remote for B

    fun jobsFile() = File(context.filesDir, "folder_sync_jobs.json")

    ToolScaffold("Folder sync", onBack = onClose) { modifier ->
        Column(modifier.padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Local, SMB/SFTP/WebDAV (saved connection), or paste two folders. Deletes go to trash.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(aRoot, { aRoot = it }, label = { Text("Side A (local path)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(bRoot, { bRoot = it }, label = { Text("Side B (local path)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(connectionIdB, { connectionIdB = it }, label = { Text("Or Side B connection id (SMB/SFTP/WebDAV)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Text("Direction", style = MaterialTheme.typography.labelLarge)
            Row { SyncDirection.entries.forEach { d ->
                FilterChip(selected = direction == d, onClick = { direction = d }, label = { Text(d.name.replace('_', ' ').lowercase(), style = MaterialTheme.typography.labelSmall) })
            } }
            Text("Conflicts", style = MaterialTheme.typography.labelLarge)
            Row { ConflictPolicy.entries.forEach { c ->
                FilterChip(selected = conflict == c, onClick = { conflict = c }, label = { Text(c.name, style = MaterialTheme.typography.labelSmall) })
            } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        val lines = withContext(Dispatchers.IO) {
                            runCatching {
                                val (afs, ar, bfs, br) = openSides(app, aRoot, bRoot, connectionIdB)
                                val plan = FolderSync.plan(
                                    afs.listRecursive(ar).filter { !it.isDirectory }.associateBy { it.relativePath },
                                    bfs.listRecursive(br).filter { !it.isDirectory }.associateBy { it.relativePath },
                                    direction, conflict,
                                )
                                listOf("Dry-run: ${plan.copyCount} copies, ${plan.deleteCount} deletes, ${plan.conflicts.size} conflicts") +
                                    plan.actions.map { "${it.kind} ${it.relativePath} ${it.detail}" }
                            }.getOrElse { listOf("Preview failed: ${it.message}") }
                        }
                        preview = lines
                    }
                }) { Text("Dry-run preview") }
                Button(onClick = {
                    scope.launch {
                        val msg = withContext(Dispatchers.IO) {
                            runCatching {
                                val (afs, ar, bfs, br) = openSides(app, aRoot, bRoot, connectionIdB)
                                val aMap = afs.listRecursive(ar).filter { !it.isDirectory }.associateBy { it.relativePath }
                                val bMap = bfs.listRecursive(br).filter { !it.isDirectory }.associateBy { it.relativePath }
                                val plan = FolderSync.plan(aMap, bMap, direction, conflict)
                                FolderSync.apply(
                                    plan, afs, ar, bfs, br,
                                    trashOnDelete = { side, rel ->
                                        val localSide = side == 'A' || connectionIdB.isBlank()
                                        if (localSide) {
                                            val path = if (side == 'A') File(ar, rel).absolutePath else File(br, rel).absolutePath
                                            runCatching { app.trash.moveToTrash(path) }.onFailure {
                                                if (side == 'A') afs.delete(ar, rel) else bfs.delete(br, rel)
                                            }
                                        } else {
                                            if (side == 'A') afs.delete(ar, rel) else bfs.delete(br, rel)
                                        }
                                    },
                                )
                                "Synced: ${plan.copyCount} copies, ${plan.deleteCount} deletes"
                            }.getOrElse { it.message ?: "sync failed" }
                        }
                        onEvent(UiEvent.ShowMessage(msg)); onEvent(UiEvent.Refresh)
                    }
                }) { Text("Run sync") }
            }
            OutlinedTextField(scheduleHours, { scheduleHours = it }, label = { Text("Schedule every N hours (local↔local)") }, singleLine = true)
            TextButton(onClick = {
                if (connectionIdB.isNotBlank()) {
                    onEvent(UiEvent.ShowMessage("Scheduling currently supports local paths only"))
                    return@TextButton
                }
                val id = UUID.randomUUID().toString()
                val root = JSONObject(runCatching { jobsFile().readText() }.getOrDefault("{}"))
                root.put(id, JSONObject().put("aRoot", aRoot).put("bRoot", bRoot)
                    .put("direction", direction.name).put("conflict", conflict.name)
                    .put("previousBoth", JSONArray()))
                jobsFile().writeText(root.toString())
                FolderSyncWorker.schedule(context, id, scheduleHours.toLongOrNull() ?: 24)
                onEvent(UiEvent.ShowMessage("Scheduled job $id"))
            }) { Text("Save + schedule WorkManager job") }
            LazyColumn(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                items(preview) { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

private fun openSides(
    app: FileManagerApplication,
    aRoot: String,
    bRoot: String,
    connectionIdB: String,
): Quadruple<SyncFs, String, SyncFs, String> {
    val a: SyncFs = LocalSyncFs()
    if (connectionIdB.isBlank()) {
        require(bRoot.isNotBlank()) { "Side B path required" }
        return Quadruple(a, aRoot, LocalSyncFs(), bRoot)
    }
    val conn = app.connections.list().firstOrNull { it.id == connectionIdB }
        ?: error("Unknown connection $connectionIdB")
    val secret = app.connections.secretFor(connectionIdB)
    val remote = DefaultRemoteBackendFactory().open(conn, secret)
    val remoteRoot = conn.initialPath.ifBlank { "/" }
    return Quadruple(a, aRoot, RemoteSyncFs(remote), remoteRoot)
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
