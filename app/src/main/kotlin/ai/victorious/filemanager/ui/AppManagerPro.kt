package ai.victorious.filemanager.ui

import ai.victorious.filemanager.FileManagerApplication
import ai.victorious.filemanager.appmgr.AppManagerService
import ai.victorious.filemanager.core.appmgr.AppOpsManagerOps
import ai.victorious.filemanager.core.appmgr.AppProfile
import ai.victorious.filemanager.core.appmgr.ProfileSchedule
import ai.victorious.filemanager.core.appmgr.ProfileActions
import ai.victorious.filemanager.appmgr.ProfileScheduleWorker
import ai.victorious.filemanager.core.appmgr.AppUsage
import ai.victorious.filemanager.core.appmgr.BatchPlan
import ai.victorious.filemanager.core.appmgr.ComponentType
import ai.victorious.filemanager.core.appmgr.LogFilter
import ai.victorious.filemanager.core.appmgr.LogLine
import ai.victorious.filemanager.core.appmgr.ProfileAction
import ai.victorious.filemanager.core.appmgr.RunningProcess
import ai.victorious.filemanager.core.appmgr.TrackerHit
import ai.victorious.filemanager.core.appmgr.UsageAggregator
import ai.victorious.filemanager.core.appmgr.UsageSort
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import ai.victorious.filemanager.core.appmgr.AppOpCatalog
import ai.victorious.filemanager.core.appmgr.ExodusTrackers
import ai.victorious.filemanager.core.appmgr.InstallOptions
import ai.victorious.filemanager.core.appmgr.LibraryHit
import ai.victorious.filemanager.core.appmgr.NetUsage
import ai.victorious.filemanager.core.appmgr.NetUsageAggregator
import ai.victorious.filemanager.core.appmgr.PrivilegeStatus
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * App Manager Pro UI. Every destructive action goes through [ConfirmPlan] before execution.
 * Guide: MuntashirAkon/AppManager's public README feature list only — no source copied.
 */
@Composable
fun AppManagerProPane(onEvent: (UiEvent) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as FileManagerApplication
    val service = remember { AppManagerService(app, app.privilege) }
    var tab by remember { mutableIntStateOf(0) }
    val titles = listOf("Apps", "Trackers", "Logcat", "Usage", "Running", "Profiles", "Backup")
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Text("App Manager Pro", style = MaterialTheme.typography.titleLarge)
        PrivilegeBanner(service, app, onEvent)
        TabRow(tab) { titles.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) } }
        when (tab) {
            0 -> AppsTab(service, onEvent)
            1 -> TrackersTab(service, onEvent)
            2 -> LogcatTab(service, onEvent)
            3 -> UsageTab(service, onEvent)
            4 -> RunningTab(service, onEvent)
            5 -> ProfilesTab(service, onEvent)
            6 -> {
                val dir = (LocalContext.current.applicationContext as FileManagerApplication).filesDir.absolutePath + "/app-backups"
                java.io.File(dir).mkdirs()
                AppsPane(backupDir = dir, onEvent = onEvent)
            }
        }
    }
}

/** Clear Root / Shizuku / Limited state, with the one action that fixes it. */
@Composable
private fun PrivilegeBanner(service: AppManagerService, app: FileManagerApplication, onEvent: (UiEvent) -> Unit) {
    val shizuku by app.shizuku.state.collectAsState()
    var banner by remember { mutableStateOf<PrivilegeStatus.Banner?>(null) }
    LaunchedEffect(shizuku) { banner = withContext(Dispatchers.IO) { service.privilegeBanner(shizuku) } }
    val b = banner ?: return
    val color = when (b.level) {
        PrivilegeStatus.Level.ROOT -> MaterialTheme.colorScheme.primary
        PrivilegeStatus.Level.SHIZUKU -> MaterialTheme.colorScheme.tertiary
        PrivilegeStatus.Level.LIMITED -> MaterialTheme.colorScheme.error
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(b.title, color = color, style = MaterialTheme.typography.labelLarge)
            Text(b.detail, style = MaterialTheme.typography.bodySmall)
        }
        b.action?.let { label ->
            TextButton(onClick = {
                if (label == "Grant Shizuku") app.privilege.requestShizukuPermission()
                else onEvent(UiEvent.Navigate(Dest.SETTINGS))
            }) { Text(label) }
        }
    }
}

/** A button that stays visible but disabled with the reason when the needed privilege is missing. */
@Composable
private fun GatedButton(label: String, need: PrivilegeStatus.Need, service: AppManagerService, onEvent: (UiEvent) -> Unit, onClick: () -> Unit) {
    val reason = remember(need) { service.blockReason(need) }
    TextButton(onClick = { if (reason != null) onEvent(UiEvent.ShowMessage("$label: $reason")) else onClick() }) {
        Text(if (reason != null) "$label 🔒" else label)
    }
}

@Composable
private fun InstallDialog(service: AppManagerService, onEvent: (UiEvent) -> Unit, onPlan: (BatchPlan) -> Unit, onDismiss: () -> Unit) {
    var path by remember { mutableStateOf("/sdcard/Download/") }
    var installer by remember { mutableStateOf("") }
    var downgrade by remember { mutableStateOf(false) }
    var testOnly by remember { mutableStateOf(false) }
    var grant by remember { mutableStateOf(false) }
    var bypass by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Install APK with options") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(path, { path = it }, label = { Text("APK path (.apk)") }, singleLine = true)
                OutlinedTextField(installer, { installer = it }, label = { Text("Installer package (optional)") }, singleLine = true)
                FilterChip(downgrade, { downgrade = !downgrade }, label = { Text("Allow downgrade") })
                FilterChip(testOnly, { testOnly = !testOnly }, label = { Text("Allow test-only") })
                FilterChip(grant, { grant = !grant }, label = { Text("Grant all runtime permissions") })
                FilterChip(bypass, { bypass = !bypass }, label = { Text("Bypass low target SDK block (Android 14+)") })
                Text("Needs Root or Shizuku. For split bundles (.apks) use the normal installer.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                runCatching {
                    service.planInstall(path.trim(), InstallOptions(downgrade = downgrade, testOnly = testOnly, grantAllPermissions = grant,
                        installerPackage = installer.trim().ifBlank { null }, bypassLowTargetSdk = bypass))
                }.onSuccess { onDismiss(); onPlan(it) }.onFailure { onEvent(UiEvent.ShowMessage(it.message ?: "")) }
            }) { Text("Review") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ConfirmPlan(plan: BatchPlan?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    if (plan == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Confirm: ${plan.title}") },
        text = { Text(plan.summary) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Confirm") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AppsTab(service: AppManagerService, onEvent: (UiEvent) -> Unit) {
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<AppManagerService.InstalledApp>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var detail by remember { mutableStateOf<AppManagerService.InstalledApp?>(null) }
    var plan by remember { mutableStateOf<BatchPlan?>(null) }
    var userOnly by remember { mutableStateOf(true) }
    var installing by remember { mutableStateOf(false) }
    val context = LocalContext.current
    fun reload() = scope.launch { apps = withContext(Dispatchers.IO) { service.listApps(includeSystem = !userOnly) } }
    LaunchedEffect(userOnly) { reload() }
    fun run(p: BatchPlan) { plan = p }
    fun go() {
        val p = plan ?: return; plan = null
        scope.launch {
            val err = withContext(Dispatchers.IO) { service.execute(p) }
            onEvent(UiEvent.ShowMessage(if (err == null) "${p.title}: done" else "${p.title}: $err"))
            reload()
        }
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, { query = it }, label = { Text("Search") }, modifier = Modifier.weight(1f), singleLine = true)
            FilterChip(userOnly, { userOnly = !userOnly }, label = { Text(if (userOnly) "User" else "All") })
            GatedButton("Install…", PrivilegeStatus.Need.ELEVATED, service, onEvent) { installing = true }
        }
        if (selected.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { runCatching { run(service.planDisable(selected.toList())) }.onFailure { onEvent(UiEvent.ShowMessage(it.message ?: "")) } }) { Text("Disable") }
                TextButton(onClick = { runCatching { run(service.planEnable(selected.toList())) }.onFailure { onEvent(UiEvent.ShowMessage(it.message ?: "")) } }) { Text("Enable") }
                TextButton(onClick = { runCatching { run(service.planForceStop(selected.toList())) }.onFailure { onEvent(UiEvent.ShowMessage(it.message ?: "")) } }) { Text("Force-stop") }
                TextButton(onClick = { runCatching { run(service.planSuspend(selected.toList())) }.onFailure { onEvent(UiEvent.ShowMessage(it.message ?: "")) } }) { Text("Freeze") }
                TextButton(onClick = { selected = emptySet() }) { Text("Clear") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val sel = selected.toList()
                fun tryPlan(f: () -> BatchPlan) = runCatching { run(f()) }.onFailure { onEvent(UiEvent.ShowMessage(it.message ?: "")) }
                GatedButton("Unfreeze", PrivilegeStatus.Need.ELEVATED, service, onEvent) { tryPlan { service.planUnfreeze(sel) } }
                GatedButton("Clear cache", PrivilegeStatus.Need.ELEVATED, service, onEvent) { tryPlan { service.planClearCache(sel) } }
                GatedButton("Clear data", PrivilegeStatus.Need.ELEVATED, service, onEvent) { tryPlan { service.planClearDataMany(sel) } }
                GatedButton("Uninstall", PrivilegeStatus.Need.ELEVATED, service, onEvent) { tryPlan { service.planUninstall(sel, keepData = false) } }
                GatedButton("Restore", PrivilegeStatus.Need.ELEVATED, service, onEvent) { tryPlan { service.planReinstallExisting(sel) } }
                GatedButton("Reset ops", PrivilegeStatus.Need.ELEVATED, service, onEvent) { tryPlan { service.planResetAppOps(sel) } }
                TextButton(onClick = {
                    scope.launch {
                        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "app-backups")
                        val (ok, errs) = withContext(Dispatchers.IO) { service.backupApks(sel, dir) }
                        onEvent(UiEvent.ShowMessage("Backed up $ok APK(s) to ${dir.path}" + if (errs.isEmpty()) "" else " · ${errs.size} failed: ${errs.first()}"))
                    }
                }) { Text("Backup APKs") }
            }
        }
        val rows = apps.filter { query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true) }
        LazyColumn(Modifier.weight(1f)) {
            items(rows, key = { it.packageName }) { a ->
                Row(Modifier.fillMaxWidth().clickable { detail = a }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(a.packageName in selected, {
                        selected = if (a.packageName in selected) selected - a.packageName else selected + a.packageName
                    }, label = { Text(if (a.packageName in selected) "✓" else " ") })
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(a.label, style = MaterialTheme.typography.titleSmall)
                        Text("${a.packageName} · v${a.versionName ?: "?"} · ${if (a.enabled) "enabled" else "disabled"}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    if (installing) InstallDialog(service, onEvent, onPlan = { run(it) }, onDismiss = { installing = false })
    detail?.let { a -> AppDetailDialog(a, service, onEvent, onPlan = { run(it) }, onClose = { detail = null }) }
    ConfirmPlan(plan, onConfirm = ::go, onDismiss = { plan = null })
}

@Composable
private fun AppDetailDialog(
    a: AppManagerService.InstalledApp,
    service: AppManagerService,
    onEvent: (UiEvent) -> Unit,
    onPlan: (BatchPlan) -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var comps by remember { mutableStateOf<List<ai.victorious.filemanager.core.appmgr.AppComponent>>(emptyList()) }
    var perms by remember { mutableStateOf<List<Pair<String, Boolean>>>(emptyList()) }
    var trackers by remember { mutableStateOf<List<TrackerHit>>(emptyList()) }
    var ops by remember { mutableStateOf<Map<String, AppOpsManagerOps.Mode>>(emptyMap()) }
    var libs by remember { mutableStateOf<List<LibraryHit>?>(null) }
    var newOp by remember { mutableStateOf("") }
    LaunchedEffect(a.packageName) {
        withContext(Dispatchers.IO) {
            comps = service.components(a.packageName)
            perms = service.permissions(a.packageName)
            ops = service.appOps(a.packageName)
            trackers = service.scanTrackers(a.packageName)
            libs = service.scanLibraries(a.packageName)
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(a.label) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(a.packageName, style = MaterialTheme.typography.bodySmall)
                Text(
                    when (val l = libs) {
                        null -> "Scanning code for trackers and libraries…"
                        else -> if (l.isEmpty()) "Libraries: none recognised" else "Libraries: " + l.joinToString { "${it.library.name} (${it.library.kind})" }
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("Components: ${comps.size} · Trackers: ${trackers.size} · Permissions: ${perms.size}")
                if (trackers.isNotEmpty()) Text("Trackers: " + trackers.joinToString { it.tracker.name }, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { runCatching { onPlan(service.planDisable(listOf(a.packageName))) }.onFailure { onEvent(UiEvent.ShowMessage(it.message ?: "")) } }) { Text("Disable") }
                    TextButton(onClick = { runCatching { onPlan(service.planEnable(listOf(a.packageName))) }.onFailure { onEvent(UiEvent.ShowMessage(it.message ?: "")) } }) { Text("Enable") }
                    TextButton(onClick = { runCatching { onPlan(service.planForceStop(listOf(a.packageName))) }.onFailure { onEvent(UiEvent.ShowMessage(it.message ?: "")) } }) { Text("Stop") }
                    TextButton(onClick = { runCatching { onPlan(service.planClearData(a.packageName)) }.onFailure { onEvent(UiEvent.ShowMessage(it.message ?: "")) } }) { Text("Clear data") }
                }
                TextButton(onClick = { runCatching { onPlan(service.planBlockTrackers(a.packageName)) }.onFailure { onEvent(UiEvent.ShowMessage(it.message ?: "")) } }) {
                    Text("Block tracker components (${trackers.size})")
                }
                Text("Components", style = MaterialTheme.typography.labelLarge)
                comps.take(40).forEach { c ->
                    Row(Modifier.fillMaxWidth()) {
                        Text("${c.type.manifestTag}: ${c.className.substringAfterLast('.')}${if (c.blocked) " [off]" else ""}",
                            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = {
                            runCatching {
                                onPlan(if (c.blocked) service.planUnblock(listOf(c)) else service.planBlock(listOf(c)))
                            }.onFailure { onEvent(UiEvent.ShowMessage(it.message ?: "")) }
                        }) { Text(if (c.blocked) "Enable" else "Block") }
                    }
                }
                if (comps.size > 40) Text("… ${comps.size - 40} more")
                Text("Permissions", style = MaterialTheme.typography.labelLarge)
                perms.take(30).forEach { (name, granted) ->
                    Row {
                        Text("${name.substringAfterLast('.')} ${if (granted) "✓" else "✗"}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = {
                            runCatching {
                                onPlan(if (granted) service.planRevoke(a.packageName, name) else service.planGrant(a.packageName, name))
                            }.onFailure { onEvent(UiEvent.ShowMessage(it.message ?: "")) }
                        }) { Text(if (granted) "Revoke" else "Grant") }
                    }
                }
                if (ops.isNotEmpty()) {
                    Text("AppOps", style = MaterialTheme.typography.labelLarge)
                    ops.entries.take(20).forEach { (op, mode) ->
                        Row {
                            Text("$op=${mode.cli}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            AppOpsManagerOps.Mode.entries.filter { it != mode }.forEach { m ->
                                TextButton(onClick = {
                                    runCatching { onPlan(service.planAppOp(a.packageName, op, m)) }
                                        .onFailure { onEvent(UiEvent.ShowMessage(it.message ?: "")) }
                                }) { Text(m.cli) }
                            }
                        }
                    }
                }
                Text("Add / reset app ops", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    AppOpCatalog.COMMON.filter { it !in ops }.take(12).forEach { op ->
                        FilterChip(newOp == op, { newOp = op }, label = { Text(op) })
                    }
                }
                Row {
                    OutlinedTextField(newOp, { newOp = it.uppercase() }, label = { Text("Op name") }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(enabled = newOp.isNotBlank(), onClick = {
                        runCatching { onPlan(service.planAddAppOp(a.packageName, newOp.trim(), AppOpsManagerOps.Mode.IGNORE)) }
                            .onFailure { onEvent(UiEvent.ShowMessage(it.message ?: "")) }
                    }) { Text("Set ignore") }
                }
                TextButton(onClick = {
                    runCatching { onPlan(service.planResetAppOps(listOf(a.packageName))) }.onFailure { onEvent(UiEvent.ShowMessage(it.message ?: "")) }
                }) { Text("Reset all app ops to default") }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

@Composable
private fun TrackersTab(service: AppManagerService, onEvent: (UiEvent) -> Unit) {
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<Pair<AppManagerService.InstalledApp, List<TrackerHit>>>>(emptyList()) }
    var plan by remember { mutableStateOf<BatchPlan?>(null) }
    var exodus by remember { mutableIntStateOf(-1) }
    var scanning by remember { mutableStateOf(true) }
    var gen by remember { mutableIntStateOf(0) }
    LaunchedEffect(gen) {
        scanning = true
        exodus = withContext(Dispatchers.IO) { service.exodusCount() }
        rows = withContext(Dispatchers.IO) {
            service.listApps(includeSystem = false).map { it to service.scanTrackers(it.packageName) }.filter { it.second.isNotEmpty() }
        }
        scanning = false
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(if (scanning) "Scanning app code…" else "${rows.size} apps with trackers", style = MaterialTheme.typography.labelLarge)
            Text(if (exodus > 0) "Built-in list + $exodus Exodus signatures. ${ExodusTrackers.ATTRIBUTION}" else "Built-in list only. Download the Exodus Privacy list for wider coverage.",
                style = MaterialTheme.typography.bodySmall)
        }
        TextButton(enabled = !scanning, onClick = {
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { service.updateExodus() } }
                    .onSuccess { onEvent(UiEvent.ShowMessage("Exodus list updated: $it signatures")); gen++ }
                    .onFailure { onEvent(UiEvent.ShowMessage("Exodus update failed: ${it.message}")) }
            }
        }) { Text("Update tracker list") }
    }
    LazyColumn {
        items(rows) { (a, hits) ->
            Column(Modifier.fillMaxWidth().padding(8.dp).clickable {
                runCatching { plan = service.planBlockTrackers(a.packageName) }.onFailure { onEvent(UiEvent.ShowMessage(it.message ?: "")) }
            }) {
                Text("${a.label} (${hits.size})", style = MaterialTheme.typography.titleSmall)
                Text(hits.joinToString { it.tracker.name }, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    ConfirmPlan(plan, onConfirm = {
        val p = plan!!; plan = null
        scope.launch {
            val err = withContext(Dispatchers.IO) { service.execute(p) }
            onEvent(UiEvent.ShowMessage(if (err == null) "Trackers blocked" else err))
        }
    }, onDismiss = { plan = null })
}

@Composable
private fun LogcatTab(service: AppManagerService, onEvent: (UiEvent) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var lines by remember { mutableStateOf<List<LogLine>>(emptyList()) }
    var filterTag by remember { mutableStateOf("") }
    var filterMsg by remember { mutableStateOf("") }
    var level by remember { mutableStateOf(setOf('V', 'D', 'I', 'W', 'E', 'F')) }
    fun load() = scope.launch { lines = withContext(Dispatchers.IO) { service.dumpLogcat() } }
    LaunchedEffect(Unit) { load() }
    val shown = remember(lines, filterTag, filterMsg, level) {
        service.filterLog(lines, LogFilter(levels = level, tagContains = filterTag, messageContains = filterMsg))
    }
    Column {
        Row {
            OutlinedTextField(filterTag, { filterTag = it }, label = { Text("Tag") }, modifier = Modifier.weight(1f), singleLine = true)
            OutlinedTextField(filterMsg, { filterMsg = it }, label = { Text("Text") }, modifier = Modifier.weight(1f), singleLine = true)
            TextButton(onClick = { load() }) { Text("Refresh") }
            TextButton(onClick = {
                scope.launch {
                    val text = service.exportLog(shown)
                    val f = File(context.cacheDir, "logcat-${System.currentTimeMillis()}.txt")
                    withContext(Dispatchers.IO) { f.writeText(text) }
                    onEvent(UiEvent.ShowMessage("Exported ${shown.size} lines → ${f.name}"))
                }
            }) { Text("Export") }
        }
        Row { listOf('V', 'D', 'I', 'W', 'E', 'F').forEach { ch ->
            FilterChip(ch in level, { level = if (ch in level) level - ch else level + ch }, label = { Text("$ch") })
        } }
        LazyColumn(Modifier.weight(1f)) {
            items(shown.takeLast(500)) { l ->
                Text("${l.level}/${l.tag}: ${l.message}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun UsageTab(service: AppManagerService, onEvent: (UiEvent) -> Unit) {
    val context = LocalContext.current
    var rows by remember { mutableStateOf<List<AppUsage>>(emptyList()) }
    var sort by remember { mutableStateOf(UsageSort.TIME) }
    var showData by remember { mutableStateOf(false) }
    var net by remember { mutableStateOf<List<Pair<String, NetUsage>>>(emptyList()) }
    LaunchedEffect(Unit) {
        if (!service.hasUsageAccess()) return@LaunchedEffect
        val end = System.currentTimeMillis()
        rows = withContext(Dispatchers.IO) { service.usage(end - 7L * 24 * 3600_000, end) }
        net = withContext(Dispatchers.IO) {
            service.dataUsage(end - 30L * 24 * 3600_000, end).map { service.labelForUid(it.uid) to it }
        }
    }
    Column {
        if (!service.hasUsageAccess()) {
            Text("Usage access is off.")
            TextButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                onEvent(UiEvent.ShowMessage("Grant usage access, then reopen this tab"))
            }) { Text("Open usage access settings") }
        } else {
            Row {
                FilterChip(!showData, { showData = false }, label = { Text("Screen time (7d)") })
                FilterChip(showData, { showData = true }, label = { Text("Data (30d)") })
            }
            if (showData) {
                if (net.isEmpty()) Text("No data usage reported (some devices hide per-app mobile data).", style = MaterialTheme.typography.bodySmall)
                LazyColumn {
                    items(net) { (label, n) ->
                        Text("$label: mobile ${NetUsageAggregator.formatBytes(n.mobile)} · Wi-Fi ${NetUsageAggregator.formatBytes(n.wifi)}", Modifier.padding(6.dp))
                    }
                }
                return@Column
            }
            Row { UsageSort.entries.forEach { s ->
                FilterChip(sort == s, { sort = s }, label = { Text(s.name) })
            } }
            LazyColumn {
                items(UsageAggregator.sort(rows, sort)) { u ->
                    Text("${u.packageName}: ${UsageAggregator.formatDuration(u.totalForegroundMs)}", Modifier.padding(6.dp))
                }
            }
        }
    }
}

@Composable
private fun RunningTab(service: AppManagerService, onEvent: (UiEvent) -> Unit) {
    val scope = rememberCoroutineScope()
    var procs by remember { mutableStateOf<List<RunningProcess>>(emptyList()) }
    var plan by remember { mutableStateOf<BatchPlan?>(null) }
    fun load() = scope.launch { procs = withContext(Dispatchers.IO) { service.runningProcesses() } }
    LaunchedEffect(Unit) { load() }
    LazyColumn {
        items(procs) { p ->
            Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${p.name} (pid ${p.pid})", style = MaterialTheme.typography.bodyMedium)
                    Text(p.user, style = MaterialTheme.typography.bodySmall)
                }
                p.packageName?.let { pkg ->
                    TextButton(onClick = {
                        runCatching { plan = service.planForceStop(listOf(pkg)) }.onFailure { onEvent(UiEvent.ShowMessage(it.message ?: "")) }
                    }) { Text("Stop") }
                }
            }
        }
    }
    ConfirmPlan(plan, onConfirm = {
        val p = plan!!; plan = null
        scope.launch {
            val err = withContext(Dispatchers.IO) { service.execute(p) }
            onEvent(UiEvent.ShowMessage(if (err == null) "Stopped" else err)); load()
        }
    }, onDismiss = { plan = null })
}

@Composable
private fun ProfilesTab(service: AppManagerService, onEvent: (UiEvent) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var profiles by remember { mutableStateOf(service.loadProfiles()) }
    var plan by remember { mutableStateOf<BatchPlan?>(null) }
    var name by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<AppProfile?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row {
            OutlinedTextField(name, { name = it }, label = { Text("New profile name") }, modifier = Modifier.weight(1f), singleLine = true)
            TextButton(enabled = name.isNotBlank(), onClick = {
                val p = AppProfile(UUID.randomUUID().toString(), name.trim(), actions = emptyList(), createdAtMs = System.currentTimeMillis())
                profiles = profiles + p; service.saveProfiles(profiles); name = ""; editing = p
            }) { Text("Create") }
        }
        Text("Edit steps, reorder, and schedule. Apply still asks for confirmation. Schedules run via WorkManager (off by default).", style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.weight(1f)) {
            items(profiles, key = { it.id }) { p ->
                Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { editing = p }) {
                        Text(p.name, style = MaterialTheme.typography.titleSmall)
                        Text("${p.actions.size} steps · ${ProfileScheduleWorker.describe(p.schedule)}", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(enabled = p.actions.isNotEmpty(), onClick = {
                        runCatching { plan = service.planProfile(p) }.onFailure { onEvent(UiEvent.ShowMessage(it.message ?: "")) }
                    }) { Text("Apply") }
                    TextButton(onClick = { editing = p }) { Text("Edit") }
                    TextButton(onClick = {
                        ProfileScheduleWorker.cancel(context, p.id)
                        profiles = profiles.filterNot { it.id == p.id }; service.saveProfiles(profiles)
                        if (editing?.id == p.id) editing = null
                    }) { Text("Delete") }
                }
            }
        }
    }
    editing?.let { ed ->
        ProfileEditorDialog(
            profile = ed,
            onDismiss = { editing = null },
            onSave = { updated ->
                profiles = profiles.map { if (it.id == updated.id) updated else it }
                service.saveProfiles(profiles)
                ProfileScheduleWorker.apply(context, updated)
                editing = null
                onEvent(UiEvent.ShowMessage("Saved ${updated.name} (${ProfileScheduleWorker.describe(updated.schedule)})"))
            },
        )
    }
    ConfirmPlan(plan, onConfirm = {
        val p = plan!!; plan = null
        scope.launch {
            val err = withContext(Dispatchers.IO) { service.execute(p) }
            onEvent(UiEvent.ShowMessage(if (err == null) "Profile applied" else err))
        }
    }, onDismiss = { plan = null })
}

@Composable
private fun ProfileEditorDialog(profile: AppProfile, onDismiss: () -> Unit, onSave: (AppProfile) -> Unit) {
    var name by remember(profile.id) { mutableStateOf(profile.name) }
    var desc by remember(profile.id) { mutableStateOf(profile.description) }
    var actions by remember(profile.id) { mutableStateOf(profile.actions) }
    var mode by remember(profile.id) { mutableStateOf(profile.schedule.mode) }
    var hour by remember(profile.id) { mutableStateOf(profile.schedule.hourOfDay.toString()) }
    var minute by remember(profile.id) { mutableStateOf(profile.schedule.minuteOfHour.toString()) }
    var delayH by remember(profile.id) { mutableStateOf(profile.schedule.delayHours.toString()) }
    var pkg by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit profile") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(desc, { desc = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth())
                Text("Steps", style = MaterialTheme.typography.labelLarge)
                actions.forEachIndexed { i, a ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}. ${ProfileActions.summarize(a)}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(enabled = i > 0, onClick = { actions = ProfileActions.move(actions, i, i - 1) }) { Text("↑") }
                        TextButton(enabled = i < actions.lastIndex, onClick = { actions = ProfileActions.move(actions, i, i + 1) }) { Text("↓") }
                        TextButton(onClick = { actions = ProfileActions.removeAt(actions, i) }) { Text("✕") }
                    }
                }
                OutlinedTextField(pkg, { pkg = it }, label = { Text("Package for new step") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row {
                    TextButton(enabled = pkg.isNotBlank(), onClick = {
                        actions = actions + ProfileAction.ForceStop(pkg.trim()); pkg = ""
                    }) { Text("+ Force-stop") }
                    TextButton(enabled = pkg.isNotBlank(), onClick = {
                        actions = actions + ProfileAction.DisablePackage(pkg.trim()); pkg = ""
                    }) { Text("+ Disable") }
                    TextButton(enabled = pkg.isNotBlank(), onClick = {
                        actions = actions + ProfileAction.EnablePackage(pkg.trim()); pkg = ""
                    }) { Text("+ Enable") }
                }
                Text("Schedule", style = MaterialTheme.typography.labelLarge)
                Row {
                    listOf("off", "once", "daily", "weekly").forEach { m ->
                        FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m) })
                    }
                }
                if (mode == "once") OutlinedTextField(delayH, { delayH = it }, label = { Text("Delay hours") }, singleLine = true)
                if (mode == "daily" || mode == "weekly") {
                    Row {
                        OutlinedTextField(hour, { hour = it }, label = { Text("Hour") }, modifier = Modifier.weight(1f), singleLine = true)
                        OutlinedTextField(minute, { minute = it }, label = { Text("Minute") }, modifier = Modifier.weight(1f), singleLine = true)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    profile.copy(
                        name = name.trim().ifBlank { profile.name },
                        description = desc,
                        actions = actions,
                        schedule = ProfileSchedule(
                            mode = mode,
                            hourOfDay = hour.toIntOrNull() ?: 9,
                            minuteOfHour = minute.toIntOrNull() ?: 0,
                            delayHours = delayH.toIntOrNull() ?: 1,
                        ),
                    ),
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
