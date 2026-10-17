package ai.victorious.filemanager.ui

import ai.victorious.filemanager.FileManagerApplication
import ai.victorious.filemanager.core.net.AuthKind
import ai.victorious.filemanager.core.net.ConnectionDraft
import ai.victorious.filemanager.core.net.CredentialRedactor
import ai.victorious.filemanager.core.net.RemotePaths
import ai.victorious.filemanager.core.net.RemoteProtocol
import ai.victorious.filemanager.core.net.SavedConnection
import ai.victorious.filemanager.net.WifiTransferController
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun NetworkConnectionsScreen(onOpenRemote: (String) -> Unit, onMessage: (String) -> Unit, onClose: () -> Unit) {
    val app = LocalContext.current.applicationContext as FileManagerApplication
    var items by remember { mutableStateOf(app.connections.list()) }
    var editing by remember { mutableStateOf(false) }
    fun reload() { items = app.connections.list() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Network connections", style = MaterialTheme.typography.titleLarge)
            Row {
                IconButton(onClick = { editing = true }) { Icon(Icons.Outlined.Add, "Add") }
                TextButton(onClick = onClose) { Text("Close") }
            }
        }
        Text(
            "Credentials stay in EncryptedSharedPreferences (Android Keystore). Cloud OAuth (Drive/Dropbox) is deferred — no client IDs.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        LazyColumn(Modifier.weight(1f)) {
            items(items, key = { it.id }) { c ->
                ListItem(
                    headlineContent = { Text(c.name) },
                    supportingContent = {
                        Text("${c.protocol}://${c.username}@${c.host}:${c.port}${c.initialPath}")
                    },
                    leadingContent = { Icon(Icons.Outlined.Cloud, null) },
                    trailingContent = {
                        Row {
                            IconButton(onClick = {
                                onOpenRemote(RemotePaths.toVirtual(c.id, c.initialPath))
                            }) { Icon(Icons.Outlined.FolderOpen, "Browse") }
                            IconButton(onClick = {
                                app.connections.remove(c.id)
                                reload()
                            }) { Icon(Icons.Outlined.Delete, "Delete") }
                        }
                    },
                )
            }
        }
    }
    if (editing) {
        EditConnectionDialog(
            onDismiss = { editing = false },
            onSave = { draft ->
                try {
                    val saved = app.connections.save(draft)
                    reload()
                    editing = false
                    onMessage("Saved ${saved.name}")
                } catch (t: Throwable) {
                    onMessage(CredentialRedactor.safeMessage(t))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditConnectionDialog(onDismiss: () -> Unit, onSave: (ConnectionDraft) -> Unit) {
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("/") }
    var share by remember { mutableStateOf("") }
    var protocol by remember { mutableStateOf(RemoteProtocol.SFTP) }
    var expanded by remember { mutableStateOf(false) }
    var useKey by remember { mutableStateOf(false) }
    var keyPem by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add connection") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = protocol.name,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Protocol") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        RemoteProtocol.entries.forEach { p ->
                            DropdownMenuItem(text = { Text(p.name) }, onClick = {
                                protocol = p
                                expanded = false
                            })
                        }
                    }
                }
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(host, { host = it }, label = { Text("Host") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    port, { port = it }, label = { Text("Port (blank = default)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(user, { user = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth())
                if (protocol == RemoteProtocol.SMB) {
                    OutlinedTextField(share, { share = it }, label = { Text("SMB share") }, modifier = Modifier.fillMaxWidth())
                }
                OutlinedTextField(path, { path = it }, label = { Text("Initial path") }, modifier = Modifier.fillMaxWidth())
                if (protocol == RemoteProtocol.SFTP) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Use private key", Modifier.weight(1f))
                        Switch(checked = useKey, onCheckedChange = { useKey = it })
                    }
                }
                if (useKey && protocol == RemoteProtocol.SFTP) {
                    OutlinedTextField(
                        keyPem, { keyPem = it }, label = { Text("Private key (PEM)") },
                        modifier = Modifier.fillMaxWidth(), minLines = 3,
                    )
                } else {
                    OutlinedTextField(
                        password, { password = it }, label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (host.isBlank()) return@TextButton
                onSave(
                    ConnectionDraft(
                        name = name,
                        protocol = protocol,
                        host = host,
                        port = port.toIntOrNull(),
                        username = user,
                        password = password.takeIf { it.isNotEmpty() },
                        privateKeyPem = keyPem.takeIf { it.isNotEmpty() },
                        initialPath = path.ifBlank { "/" },
                        authKind = if (useKey) AuthKind.PRIVATE_KEY else AuthKind.PASSWORD,
                        share = share,
                        webDavHttps = protocol == RemoteProtocol.WEBDAV,
                    ),
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun WifiTransferScreen(rootPath: String, onMessage: (String) -> Unit, onClose: () -> Unit) {
    val app = LocalContext.current.applicationContext as FileManagerApplication
    val ctrl = app.wifiTransfer
    var running by remember { mutableStateOf(ctrl.isRunning) }
    var url by remember { mutableStateOf(ctrl.url) }
    var pin by remember { mutableStateOf(ctrl.pin) }
    var requirePin by remember { mutableStateOf(true) }
    var qr by remember { mutableStateOf<Bitmap?>(null) }

    DisposableEffect(Unit) {
        onDispose { /* keep running until user stops */ }
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Wi-Fi file transfer", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onClose) { Text("Close") }
        }
        Text(
            "Serves $rootPath on the LAN only. Stop when finished.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            Text("Require PIN", Modifier.weight(1f))
            Switch(checked = requirePin, onCheckedChange = { requirePin = it }, enabled = !running)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                try {
                    val s = ctrl.start(rootPath, requirePin = requirePin)
                    running = true
                    url = s.url
                    pin = ctrl.pin
                    qr = ctrl.qrBitmap(s.url + (ctrl.pin?.let { "?pin=$it" } ?: ""))
                    onMessage("Wi-Fi transfer started")
                } catch (t: Throwable) {
                    onMessage(CredentialRedactor.safeMessage(t))
                }
            }, enabled = !running) { Text("Start") }
            Button(onClick = {
                ctrl.stop()
                running = false
                url = null
                pin = null
                qr = null
                onMessage("Wi-Fi transfer stopped")
            }, enabled = running) { Text("Stop") }
        }
        if (running) {
            Text("URL: $url", modifier = Modifier.padding(top = 16.dp))
            if (pin != null) Text("PIN: $pin", style = MaterialTheme.typography.titleMedium)
            qr?.let {
                Image(it.asImageBitmap(), contentDescription = "QR", modifier = Modifier.size(240.dp).padding(16.dp))
            }
            Icon(Icons.Outlined.Wifi, null, modifier = Modifier.padding(8.dp))
        }
    }
}
