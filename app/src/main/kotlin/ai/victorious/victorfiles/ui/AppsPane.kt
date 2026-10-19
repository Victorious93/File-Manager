package ai.victorious.victorfiles.ui

import ai.victorious.victorfiles.FileManagerApplication
import ai.victorious.victorfiles.apk.ApkInstaller
import ai.victorious.victorfiles.apps.AppBackupService
import ai.victorious.victorfiles.apps.InstalledAppInfo
import ai.victorious.victorfiles.core.apps.AppBackup
import ai.victorious.victorfiles.core.storage.StorageAnalyzer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AppsPane(backupDir: String, onEvent: (UiEvent) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as FileManagerApplication
    val scope = rememberCoroutineScope()
    val service = remember { AppBackupService(app, app.privilege) }
    var apps by remember { mutableStateOf<List<InstalledAppInfo>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var userOnly by remember { mutableStateOf(true) }
    var systemOnly by remember { mutableStateOf(false) }
    var sort by remember { mutableStateOf(AppBackup.Sort.NAME) }
    var busy by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<InstalledAppInfo?>(null) }
    var includeData by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch {
            error = null
            val list = withContext(Dispatchers.IO) { runCatching { service.listInstalled() } }
            list.onSuccess { apps = it }.onFailure { error = it.message }
        }
    }

    LaunchedEffect(Unit) { reload() }

    val rows = remember(apps, query, userOnly, systemOnly, sort) {
        val src = apps.orEmpty().map {
            AppBackup.AppRow(it.packageName, it.label, it.isSystem, it.sizeBytes, it.installedEpochMs)
        }
        val filtered = AppBackup.filterAndSort(src, query, userOnly, systemOnly, sort)
        val byPkg = apps.orEmpty().associateBy { it.packageName }
        filtered.mapNotNull { byPkg[it.packageName] }
    }

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        OutlinedTextField(
            query, { query = it }, label = { Text("Search apps") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(userOnly, {
                userOnly = true; systemOnly = false
            }, label = { Text("User") })
            FilterChip(systemOnly, {
                systemOnly = true; userOnly = false
            }, label = { Text("System") })
            FilterChip(!userOnly && !systemOnly, {
                userOnly = false; systemOnly = false
            }, label = { Text("All") })
            FilterChip(sort == AppBackup.Sort.NAME, { sort = AppBackup.Sort.NAME }, label = { Text("Name") })
            FilterChip(sort == AppBackup.Sort.SIZE, { sort = AppBackup.Sort.SIZE }, label = { Text("Size") })
            FilterChip(sort == AppBackup.Sort.INSTALLED, { sort = AppBackup.Sort.INSTALLED }, label = { Text("Date") })
        }
        Text(
            "Backups go to: $backupDir",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        when {
            error != null -> Text(error!!, Modifier.padding(16.dp))
            apps == null -> CircularProgressIndicator(Modifier.padding(24.dp).align(Alignment.CenterHorizontally))
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(rows, key = { it.packageName }) { a ->
                    Column(
                        Modifier.fillMaxWidth().clickable { selected = a }.padding(vertical = 8.dp, horizontal = 4.dp),
                    ) {
                        Text(a.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${a.packageName} · ${a.versionName ?: "?"} (${a.versionCode}) · " +
                                StorageAnalyzer.human(a.sizeBytes) +
                                if (a.isSystem) " · system" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    }
                }
            }
        }
    }

    selected?.let { a ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(a.label) },
            text = {
                Column {
                    Text(a.packageName)
                    Text("APKs: ${a.apkPaths.size}")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(includeData, { includeData = it })
                        Text("Include /data (root)")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    busy = true
                    selected = null
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            runCatching { service.backup(a, File(backupDir), includeData = includeData) }
                        }
                        busy = false
                        r.onSuccess { onEvent(UiEvent.ShowMessage("Backed up → ${it.name}")) }
                            .onFailure { onEvent(UiEvent.ShowMessage(it.message ?: "Backup failed")) }
                        onEvent(UiEvent.Refresh)
                    }
                }) { Text("Backup") }
            },
            dismissButton = {
                Column {
                    TextButton(onClick = {
                        runCatching { service.openApp(a.packageName) }
                            .onFailure { onEvent(UiEvent.ShowMessage(it.message ?: "Cannot open")) }
                        selected = null
                    }) { Text("Open") }
                    TextButton(onClick = {
                        service.appInfoSettings(a.packageName)
                        selected = null
                    }) { Text("App info") }
                    TextButton(onClick = {
                        ApkInstaller.uninstall(context, a.packageName)
                        selected = null
                    }) { Text("Uninstall") }
                    TextButton(onClick = { selected = null }) { Text("Close") }
                }
            },
        )
    }
}
