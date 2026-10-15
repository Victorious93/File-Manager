package ai.victorious.filemanager.ui

import ai.victorious.filemanager.FileManagerApplication
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun SettingsPane() {
    val app = LocalContext.current.applicationContext as FileManagerApplication
    val settings = app.fleetSettings
    val enabled by settings.externalControl.collectAsState()
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Allow external control", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Lets DroidCommand (ai.droidcommand.app) query this app over the fleet bridge. " +
                        "Off by default. Other apps are always rejected.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = enabled, onCheckedChange = settings::setExternalControlEnabled)
        }
        Text("Fleet node: ${app.nodeId} (external_node, stub)", style = MaterialTheme.typography.bodySmall)
    }
}
