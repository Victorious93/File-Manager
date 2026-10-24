package ai.victorious.filemanager.ui

import ai.victorious.filemanager.FileManagerApplication
import ai.victorious.filemanager.core.priv.PowerAction
import ai.victorious.filemanager.core.priv.PrivilegeBackend
import ai.victorious.filemanager.core.priv.ShizukuCopy
import ai.victorious.filemanager.core.priv.ShizukuState
import ai.victorious.filemanager.priv.ShizukuController
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Fleet neon palette per Shizuku state (readable on dark and light Material 3 surfaces). */
private fun stateColor(s: ShizukuState) = when (s) {
    ShizukuState.GRANTED -> Color(0xFF39FF88)
    ShizukuState.DENIED -> Color(0xFFFFC53D)
    ShizukuState.NOT_RUNNING -> Color(0xFF3DD9FF)
    ShizukuState.NOT_INSTALLED -> Color(0xFFFF4D8D)
}

/** Material 3 status indicator: four states, consistent fleet copy, one primary action. */
@Composable
fun ShizukuStatusCard(controller: ShizukuController, onEvent: (UiEvent) -> Unit) {
    val context = LocalContext.current
    val state by controller.state.collectAsState()
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(12.dp).background(stateColor(state), CircleShape))
                Text(
                    ShizukuCopy.title(state) + if (controller.isSui) " (Sui)" else "",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { contentDescription = ShizukuCopy.title(state) },
                )
            }
            Text(ShizukuCopy.detail(state), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShizukuCopy.action(state)?.let { label ->
                    Button(onClick = {
                        when (state) {
                            ShizukuState.DENIED -> if (!controller.requestPermission()) {
                                onEvent(UiEvent.ShowMessage("Shizuku could not show the prompt. Open Shizuku and retry."))
                            }
                            ShizukuState.NOT_RUNNING -> openShizuku(context)
                            ShizukuState.NOT_INSTALLED -> runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                            ShizukuState.GRANTED -> Unit
                        }
                    }) { Text(label) }
                }
                TextButton(onClick = { controller.refresh() }) { Text("Re-check") }
            }
        }
    }
}

private fun openShizuku(context: android.content.Context) {
    val i = context.packageManager.getLaunchIntentForPackage(ShizukuController.MANAGER_PACKAGE)
    runCatching { context.startActivity((i ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/guide/setup/"))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/**
 * Power ops (chmod/chown/remount/pm install/pm uninstall) routed through PrivilegeManager.ops()
 * → Shizuku UserService or root. Every op shows a confirm dialog first; output is never shown
 * or logged, only success / short error.
 */
@Composable
fun PowerOpsSection(onEvent: (UiEvent) -> Unit) {
    val app = LocalContext.current.applicationContext as FileManagerApplication
    val scope = rememberCoroutineScope()
    var action by remember { mutableStateOf(PowerAction.CHMOD) }
    var target by remember { mutableStateOf("") }
    var arg by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }
    val choices = listOf(PowerAction.CHMOD, PowerAction.CHOWN, PowerAction.REMOUNT, PowerAction.INSTALL, PowerAction.UNINSTALL)

    Text("Power ops", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    Text("Runs via Root or the Shizuku service. Nothing runs without your confirmation.", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        choices.forEach { a -> FilterChip(selected = action == a, onClick = { action = a }, label = { Text(a.name.lowercase()) }) }
    }
    OutlinedTextField(target, { target = it }, label = {
        Text(when (action) { PowerAction.UNINSTALL -> "Package name"; PowerAction.REMOUNT -> "Mount point (/system…)"; PowerAction.INSTALL -> "APK path"; else -> "Path" })
    }, singleLine = true, modifier = Modifier.fillMaxWidth())
    if (action == PowerAction.CHMOD || action == PowerAction.CHOWN || action == PowerAction.REMOUNT) {
        OutlinedTextField(arg, { arg = it }, label = {
            Text(when (action) { PowerAction.CHMOD -> "Mode (e.g. 644)"; PowerAction.CHOWN -> "owner[:group]"; else -> "rw or ro" })
        }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
    Button(enabled = target.isNotBlank(), onClick = { confirming = true }) { Text("Run…") }

    if (confirming) {
        val summary = "${action.name.lowercase()} ${target.trim()}" + if (arg.isNotBlank()) " ($arg)" else ""
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Run privileged operation?") },
            text = { Text("$summary\n\nThis can break apps or the system. Only continue if you meant it.") },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    val t = target.trim(); val a = action; val x = arg.trim()
                    scope.launch {
                        val msg = withContext(Dispatchers.IO) {
                            runCatching {
                                val ops = app.privilege.ops()
                                if (ops.backend == PrivilegeBackend.NORMAL) error(app.privilege.missingReason() ?: "Start Shizuku or enable Root first")
                                app.privilege.confirmGate.approve(a, t)
                                when (a) {
                                    PowerAction.CHMOD -> ops.chmod(t, x)
                                    PowerAction.CHOWN -> ops.chown(t, x)
                                    PowerAction.REMOUNT -> ops.remount(t, x != "ro")
                                    PowerAction.INSTALL -> ops.installPackage(t)
                                    PowerAction.UNINSTALL -> ops.uninstallPackage(t)
                                    PowerAction.WRITE -> Unit
                                }
                                "Done via ${ops.backend}"
                            }.getOrElse { it.message ?: "Failed" }
                        }
                        onEvent(UiEvent.ShowMessage(msg))
                    }
                }) { Text("Run") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}
