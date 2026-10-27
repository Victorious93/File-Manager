package ai.victorious.filemanager.ui

import ai.victorious.filemanager.FileManagerApplication
import ai.victorious.filemanager.core.priv.PrivilegeBackend
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp

@Composable
fun SettingsPane(onEvent: (UiEvent) -> Unit = {}) {
    val app = LocalContext.current.applicationContext as FileManagerApplication
    val fleet = app.fleetSettings
    val priv = app.privilegeSettings
    val external by fleet.externalControl.collectAsState()
    val rootOn by priv.rootEnabled.collectAsState()
    val shizukuOn by priv.shizukuEnabled.collectAsState()
    var refresh by remember { mutableStateOf(0) }
    val shizukuState by app.shizuku.state.collectAsState()
    val avail = remember(rootOn, shizukuOn, refresh, shizukuState) { app.privilege.availability() }
    val backend = remember(avail) { app.privilege.backend() }

    Column(
        Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(onClick = { onEvent(UiEvent.OpenTool(ToolRequest.Help)) }, modifier = Modifier.fillMaxWidth()) {
            Text("Help & manual")
        }
        AppearanceSection()
        Text("Privilege", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        Text(
            "Fallback order for protected paths: Root → Shizuku → normal. Both toggles default off.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Root (su / Magisk)", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (avail.rootAvailable) "su detected / granted"
                    else "No su / not granted yet",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = rootOn, onCheckedChange = {
                priv.setRootEnabled(it)
                refresh++
                if (it && !avail.rootAvailable) {
                    onEvent(UiEvent.ShowMessage("Root enabled, but su is missing or not granted yet."))
                }
            })
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Use Shizuku / Sui", style = MaterialTheme.typography.titleSmall)
                Text("Privileged ops go through the Shizuku UserService.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = shizukuOn, onCheckedChange = {
                priv.setShizukuEnabled(it)
                refresh++
            })
        }
        ShizukuStatusCard(app.shizuku, onEvent)
        Text(
            "Active backend: $backend" +
                if (backend == PrivilegeBackend.NORMAL) {
                    app.privilege.missingReason()?.let { " — $it" }.orEmpty()
                } else "",
            style = MaterialTheme.typography.bodySmall,
        )

        PowerOpsSection(onEvent)

        SecureFolderSettingsSection()
        ArchivePasswordsSection(onEvent)

        Text("Fleet", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Allow external control", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Lets DroidCommand (ai.droidcommand.app) query this app over the fleet bridge. " +
                        "Off by default. Other apps are always rejected.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = external, onCheckedChange = fleet::setExternalControlEnabled)
        }
        Text("Fleet node: ${app.nodeId} (external_node)", style = MaterialTheme.typography.bodySmall)

        BackupRestoreSection(onEvent)
    }
}

@Composable
private fun ArchivePasswordsSection(onEvent: (UiEvent) -> Unit) {
    val store = (LocalContext.current.applicationContext as FileManagerApplication).archivePasswords
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var count by remember { mutableStateOf<Int?>(null) }
    // Keystore + file I/O stay off the main thread.
    androidx.compose.runtime.LaunchedEffect(Unit) { count = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { store.list().size }.getOrDefault(0) } }
    Text("Saved archive passwords", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    Text(
        "${count ?: "…"} saved, encrypted with a key in the Android Keystore (never backed up). Tried automatically on encrypted ZIPs.",
        style = MaterialTheme.typography.bodySmall,
    )
    OutlinedButton(enabled = (count ?: 0) > 0, onClick = {
        count = 0
        scope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { store.clear() } }
        onEvent(UiEvent.ShowMessage("Saved archive passwords cleared"))
    }) { Text("Clear all") }
}

@Composable
private fun SecureFolderSettingsSection() {
    val app = LocalContext.current.applicationContext as FileManagerApplication
    val secure = app.secureFolder
    var timeout by remember { mutableStateOf(secure.timeoutSec) }
    Text("Secure folder", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    Text(
        "Auto-lock after ${if (timeout == 0) "leaving the app" else "$timeout s idle or in background"}.",
        style = MaterialTheme.typography.bodySmall,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (s in listOf(0, 30, 60, 300)) {
            FilterChip(selected = timeout == s, onClick = { secure.timeoutSec = s; timeout = s },
                label = { Text(if (s == 0) "Immediately" else if (s < 60) "${s}s" else "${s / 60} min") })
        }
    }
}

@Composable
private fun AppearanceSection() {
    val app = LocalContext.current.applicationContext as FileManagerApplication
    val theme = app.themeSettings
    val prefs by theme.state.collectAsState()
    val dynamicSupported = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
    Text("Appearance", style = MaterialTheme.typography.titleMedium)
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (m in ai.victorious.filemanager.ui.theme.ThemeMode.entries) {
            FilterChip(selected = prefs.mode == m, onClick = { theme.update { it.copy(mode = m) } }, label = { Text(m.label) })
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Dynamic color (Material You)", style = MaterialTheme.typography.titleSmall)
            Text(
                if (dynamicSupported) "Use colors from your wallpaper" else "Needs Android 12 or newer",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(
            checked = prefs.dynamicColor && dynamicSupported,
            enabled = dynamicSupported,
            onCheckedChange = { on -> theme.update { it.copy(dynamicColor = on) } },
        )
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Two file panes", style = MaterialTheme.typography.titleSmall)
            Text(
                "Show two folders at once. Off: one list, and Copy/Move use Paste here.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(checked = prefs.dualPane, onCheckedChange = { on -> theme.update { it.copy(dualPane = on) } })
    }
    val dynamicOn = prefs.dynamicColor && dynamicSupported
    if (!dynamicOn) {
        Text("Theme", style = MaterialTheme.typography.titleSmall)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (p in ai.victorious.filemanager.ui.theme.Palette.entries) {
                FilterChip(
                    selected = prefs.palette == p,
                    onClick = { theme.update { it.copy(palette = p) } },
                    label = { Text(p.label) },
                    leadingIcon = {
                        androidx.compose.foundation.layout.Box(
                            Modifier
                                .size(12.dp)
                                .background(p.swatch, androidx.compose.foundation.shape.CircleShape),
                        )
                    },
                )
            }
        }
    }
    // The accent presets only recolor the Classic theme; the others bring their own colors.
    if (!dynamicOn && prefs.palette == ai.victorious.filemanager.ui.theme.Palette.CLASSIC) {
        Text("Accent", style = MaterialTheme.typography.titleSmall)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (a in ai.victorious.filemanager.ui.theme.Accent.entries) {
                FilterChip(selected = prefs.accent == a, onClick = { theme.update { it.copy(accent = a) } }, label = { Text(a.label) })
            }
        }
    }
}
