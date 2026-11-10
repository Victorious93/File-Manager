package ai.victorious.filemanager.ui

import ai.victorious.filemanager.FileManagerApplication
import ai.victorious.filemanager.core.mypc.HostKeyMismatchException
import ai.victorious.filemanager.core.mypc.HostKeyUnpinnedException
import ai.victorious.filemanager.core.mypc.HostKeys
import ai.victorious.filemanager.core.mypc.PcProfile
import ai.victorious.filemanager.core.mypc.PcProfileRules
import ai.victorious.filemanager.core.net.RemotePaths
import ai.victorious.filemanager.mypc.MyPcErrors
import ai.victorious.filemanager.mypc.MyPcTransferWorker
import ai.victorious.filemanager.mypc.MyPcTransfers
import ai.victorious.filemanager.ui.theme.FleetDark
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tools → My PC: the PCs this app can open over SFTP, each pinned to its host key, plus the
 * background transfer list. Browsing itself happens in the normal (dual-pane) file browser.
 */
@Composable
fun MyPcScreen(focusedPane: Int, onClose: () -> Unit, onEvent: (UiEvent) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as FileManagerApplication
    val repo = app.myPc
    val profiles by repo.profiles.collectAsState()
    val transfers by remember { WorkManager.getInstance(context).getWorkInfosByTagFlow(MyPcTransfers.TAG) }
        .collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var keyFor by remember { mutableStateOf<PcProfile?>(null) }
    var trust by remember { mutableStateOf<Pair<PcProfile, HostKeyUnpinnedException>?>(null) }
    var blocked by remember { mutableStateOf<Pair<PcProfile, HostKeyMismatchException>?>(null) }
    var removing by remember { mutableStateOf<PcProfile?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    val errors by repo.lastError.collectAsState()

    fun open(p: PcProfile) {
        busy = p.profileId
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val b = repo.connect(p.profileId)
                    RemotePaths.toVirtual(p.connectionId, p.startPath.ifBlank { b.home() })
                }
            }
            busy = null
            r.onSuccess { path ->
                onEvent(UiEvent.CloseTool)
                onEvent(UiEvent.Open(focusedPane, path))
                onEvent(UiEvent.Navigate(Dest.FILES))
            }.onFailure { t ->
                when (t) {
                    is HostKeyUnpinnedException -> trust = p to t
                    is HostKeyMismatchException -> blocked = p to t
                    else -> onEvent(UiEvent.ShowMessage(MyPcErrors.explain(t)))
                }
            }
        }
    }

    ToolScaffold("My PC", onClose, actions = {
        IconButton(onClick = { adding = true }) { Icon(Icons.Outlined.Add, contentDescription = "Add PC") }
    }) { mod ->
        LazyColumn(mod.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text(
                    "Open your Ubuntu PC's files over SFTP in either pane. Each PC is locked to its host key: if the key " +
                        "ever changes, the app refuses to connect. Copy or move between a phone folder and a PC folder " +
                        "to run a background transfer that resumes after a drop and is checked before it is kept.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (profiles.isEmpty()) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("No PCs yet", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Tap + to add one by hand (host, user, host key). Pairing by QR through VictorSuite comes later.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { adding = true }) { Text("Add PC") }
                        }
                    }
                }
            }
            items(profiles, key = { it.profileId }) { p ->
                PcCard(
                    p = p,
                    busy = busy == p.profileId,
                    lastError = errors[p.profileId],
                    onOpen = { open(p) },
                    onKey = { keyFor = p },
                    onRemove = { removing = p },
                )
            }
            item { Text("Transfers", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
            val recent = transfers.sortedBy { it.state.isFinished }.take(30)
            if (recent.isEmpty()) {
                item {
                    Text(
                        "None yet. Select files on the phone with a PC folder in the other pane (or the reverse) and tap Copy or Move.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(recent, key = { it.id.toString() }) { w -> TransferRow(w) { WorkManager.getInstance(context).cancelWorkById(w.id) } }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (adding) {
        AddPcDialog(onDismiss = { adding = false }) { name, user, hosts, port, pins, start ->
            scope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { repo.addManual(name, user, hosts, port, pins, start) } }
                r.onSuccess { adding = false; keyFor = it }
                    .onFailure { onEvent(UiEvent.ShowMessage(MyPcErrors.explain(it))) }
            }
        }
    }
    keyFor?.let { p -> PublicKeyDialog(p, context) { keyFor = null } }
    trust?.let { (p, e) ->
        AlertDialog(
            onDismissRequest = { trust = null },
            title = { Text("Check this PC's host key") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("First connection to ${p.name} (${e.host}). On the PC, run:")
                    Mono("ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub")
                    Text("The SHA256 part must match exactly:")
                    Mono(e.presented)
                    Text(
                        "Only tap Pin if it matches. After that a different key is always blocked.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    runCatching { repo.pinFirstKey(p.profileId, e.presented) }
                        .onFailure { onEvent(UiEvent.ShowMessage(MyPcErrors.explain(it))) }
                    trust = null
                    repo.get(p.profileId)?.let(::open)
                }) { Text("It matches, pin it") }
            },
            dismissButton = { TextButton(onClick = { trust = null }) { Text("Cancel") } },
        )
    }
    blocked?.let { (p, e) ->
        AlertDialog(
            onDismissRequest = { blocked = null },
            title = { Text("Connection blocked", color = FleetDark.Magenta) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "${p.name} at ${e.host} answered with a different host key than the one pinned. " +
                            "This can mean the PC was reinstalled, or that something is pretending to be it. " +
                            "Nothing was sent and the app will not connect.",
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Pinned:", fontWeight = FontWeight.SemiBold)
                    e.expected.forEach { Mono(it) }
                    Text("Presented:", fontWeight = FontWeight.SemiBold)
                    Mono(e.presented)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "If you reinstalled the PC yourself, remove it here and add it again (re-pair). There is no " +
                            "\"connect anyway\".",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = { Button(onClick = { blocked = null }) { Text("Close") } },
        )
    }
    removing?.let { p ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${p.name}?") },
            text = {
                Text(
                    "Deletes this PC's pinned host key and this app's key for it from the phone. Also delete the " +
                        "line ending in this app's victorpc comment from ~/.ssh/authorized_keys on the PC.",
                )
            },
            confirmButton = {
                Button(
                    onClick = { repo.remove(p.profileId); removing = null },
                    colors = ButtonDefaults.buttonColors(containerColor = FleetDark.Magenta, contentColor = FleetDark.Background),
                ) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Mono(text: String) {
    SelectionContainer {
        Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = FleetDark.Cyan, modifier = Modifier.padding(vertical = 4.dp))
    }
}

@Composable
private fun PcCard(p: PcProfile, busy: Boolean, lastError: Throwable?, onOpen: () -> Unit, onKey: () -> Unit, onRemove: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, if (lastError is HostKeyMismatchException) FleetDark.Magenta else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(14.dp).fillMaxWidth()) {
            Text(p.name, style = MaterialTheme.typography.titleMedium)
            Text("${p.user}@${p.hosts.joinToString(", ")}  port ${p.port}", style = MaterialTheme.typography.bodySmall)
            if (p.hostKeys.isEmpty()) {
                Text("Host key not pinned yet: you'll be asked to check it on first connect.", style = MaterialTheme.typography.bodySmall, color = FleetDark.Magenta)
            } else {
                p.hostKeys.forEach { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = FleetDark.Cyan) }
            }
            p.lastSeenHost?.let { Text("Last reached at $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (lastError is HostKeyMismatchException) {
                Text("Blocked: host key changed. Re-pair to use this PC again.", color = FleetDark.Magenta, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpen, enabled = !busy) { Text(if (busy) "Connecting…" else "Open in pane") }
                OutlinedButton(onClick = onKey) { Text("Key") }
                TextButton(onClick = onRemove) { Text("Remove") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
        }
    }
}

@Composable
private fun TransferRow(w: WorkInfo, onCancel: () -> Unit) {
    val data = if (w.state.isFinished) w.outputData else w.progress
    val label = data.getString(MyPcTransferWorker.P_LABEL) ?: "Transfer"
    val done = data.getLong(MyPcTransferWorker.P_DONE, 0L)
    val total = data.getLong(MyPcTransferWorker.P_TOTAL, 0L)
    val msg = w.outputData.getString(MyPcTransferWorker.OUT_MESSAGE)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(12.dp).fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
            val state = when (w.state) {
                WorkInfo.State.ENQUEUED -> if (w.runAttemptCount > 0) "Waiting to resume" else "Waiting"
                WorkInfo.State.RUNNING -> if (total > 0) "${MyPcTransferWorker.human(done)} of ${MyPcTransferWorker.human(total)}" else "Connecting…"
                WorkInfo.State.SUCCEEDED -> msg ?: "Done"
                WorkInfo.State.FAILED -> msg ?: "Failed"
                WorkInfo.State.BLOCKED -> "Waiting for network"
                WorkInfo.State.CANCELLED -> "Cancelled"
            }
            Text(
                state,
                style = MaterialTheme.typography.bodySmall,
                color = if (w.state == WorkInfo.State.FAILED) FleetDark.Magenta else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (w.state == WorkInfo.State.RUNNING && total > 0) {
                LinearProgressIndicator(progress = { (done.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
            if (!w.state.isFinished) TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun PublicKeyDialog(p: PcProfile, context: Context, onDismiss: () -> Unit) {
    val app = context.applicationContext as FileManagerApplication
    val line = remember(p.profileId) { runCatching { app.myPc.authorizedKeysLine(p) }.getOrElse { "Key unavailable: ${it.message}" } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("This app's key for ${p.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "This is a public key, safe to share. Its private half never leaves this phone's secure hardware. " +
                        "Add this line to ~/.ssh/authorized_keys for ${p.user} on the PC:",
                )
                Mono(line)
                Text("For example, on the PC:", style = MaterialTheme.typography.bodySmall)
                Mono("nano ~/.ssh/authorized_keys   # paste the line, save")
                Text(
                    "Key name on the phone: ${p.keyAlias}. Automatic enrollment through VictorSuite comes in a later update.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("My PC public key", line))
            }) { Text("Copy line") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun AddPcDialog(
    onDismiss: () -> Unit,
    onSave: (name: String, user: String, hosts: List<String>, port: Int, pins: List<String>, start: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var hosts by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var pins by remember { mutableStateOf("") }
    var start by remember { mutableStateOf("") }
    var problems by remember { mutableStateOf<List<String>>(emptyList()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a PC") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(user, { user = it.trim() }, label = { Text("PC user (e.g. don)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    hosts, { hosts = it },
                    label = { Text("Hosts, LAN first (e.g. 192.168.1.42, desk.tail1234.ts.net)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    port, { port = it.filter(Char::isDigit).take(5) },
                    label = { Text("SSH port") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    pins, { pins = it },
                    label = { Text("Host key (optional): ssh-ed25519:SHA256:…") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Leave the host key empty to check it on first connect. Separate several with ;",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(start, { start = it }, label = { Text("Start folder (optional, default home)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                problems.forEach { Text(it, color = FleetDark.Magenta, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val hostList = PcProfileRules.parseHosts(hosts)
                val portNum = port.toIntOrNull()
                problems = PcProfileRules.problems(name, user, hostList, portNum, pins)
                if (problems.isEmpty()) onSave(name, user, hostList, portNum!!, HostKeys.parseList(pins), start)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
