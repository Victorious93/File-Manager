@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package ai.victorious.filemanager.ui

import ai.victorious.filemanager.core.fs.FileEntry
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Settings as SettingsIcon
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerApp(
    state: UiState,
    onEvent: (UiEvent) -> Unit,
    onRefreshPermissions: () -> Unit,
) {
    val snackbarHost = remember { SnackbarHostState() }
    LaunchedEffect(state.snackbar) {
        val message = state.snackbar ?: return@LaunchedEffect
        snackbarHost.showSnackbar(message)
        onEvent(UiEvent.DismissSnackbar)
    }
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { onRefreshPermissions() }

    if (state.showTerminal) {
        val appCtx = LocalContext.current.applicationContext as ai.victorious.filemanager.FileManagerApplication
        Box(Modifier.fillMaxSize()) {
            TerminalScreen(appCtx.terminals, onClose = { onEvent(UiEvent.CloseTerminal) })
            SnackbarHost(snackbarHost, Modifier.align(Alignment.BottomCenter))
        }
        return
    }
    state.tool?.let { tool ->
        Box(Modifier.fillMaxSize()) {
            ToolHost(tool, onClose = { onEvent(UiEvent.CloseTool) }, onEvent = onEvent)
            SnackbarHost(snackbarHost, Modifier.align(Alignment.BottomCenter))
        }
        return
    }
    val viewer = state.viewer
    if (viewer != null) {
        val otherDir = if (state.focusedPane == 0) state.right.active.path else state.left.active.path
        Box(Modifier.fillMaxSize()) {
            ViewerHost(
                request = viewer,
                extractDir = otherDir,
                onClose = { onEvent(UiEvent.CloseViewer) },
                onMessage = { onEvent(UiEvent.ShowMessage(it)) },
            )
            SnackbarHost(snackbarHost, Modifier.align(Alignment.BottomCenter))
        }
        return
    }
    state.surgeryTarget?.let { target ->
        Box(Modifier.fillMaxSize()) {
            ApkSurgeryScreen(target, onEvent)
            SnackbarHost(snackbarHost, Modifier.align(Alignment.BottomCenter))
        }
        state.rebuiltApk?.let { RebuiltSignReminder(it, onEvent) }
        state.signTarget?.let { SignDialog(it, onEvent) }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Victorious", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = when (state.destination) {
                                Dest.FILES -> pane(state).active.path
                                Dest.SEARCH -> "Search"
                                Dest.BOOKMARKS -> "Bookmarks"
                                Dest.APPS -> "Apps"
                                Dest.TOOLS -> "Tools"
                                Dest.TRASH -> "Trash"
                                Dest.SETTINGS -> "Settings"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    if (state.destination == Dest.FILES) {
                        val path = pane(state).active.path
                        val bookmarked = state.bookmarks.any { it.path == path }
                        IconButton(onClick = { onEvent(UiEvent.ToggleBookmark) }) {
                            Icon(
                                if (bookmarked) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                                contentDescription = "Bookmark",
                            )
                        }
                    }
                    IconButton(onClick = { onEvent(UiEvent.ToggleAbout) }) {
                        Icon(Icons.Outlined.Info, contentDescription = "About")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            Column {
                if (state.destination == Dest.FILES) {
                    BottomAppBar(
                        actions = {
                            IconButton(onClick = { onEvent(UiEvent.CopyToOther) }) {
                                Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy")
                            }
                            IconButton(onClick = { onEvent(UiEvent.MoveToOther) }) {
                                Icon(Icons.AutoMirrored.Outlined.DriveFileMove, contentDescription = "Move")
                            }
                            IconButton(onClick = { onEvent(UiEvent.DeleteSelected) }) {
                                Icon(Icons.Outlined.Delete, contentDescription = "Delete")
                            }
                            IconButton(onClick = { onEvent(UiEvent.RequestRename) }) {
                                Icon(Icons.Outlined.DriveFileRenameOutline, contentDescription = "Rename")
                            }
                            IconButton(onClick = { onEvent(UiEvent.ZipSelected) }) {
                                Icon(Icons.Outlined.Archive, contentDescription = "Zip selected")
                            }
                            IconButton(onClick = {
                                val apks = selectedApks(state)
                                if (apks.isEmpty()) onEvent(UiEvent.ShowMessage("Select one or more .apk files"))
                                else apkAction(context, onEvent) { ai.victorious.filemanager.apk.ApkInstaller.install(context, apks) }
                            }) {
                                Icon(Icons.Outlined.Android, contentDescription = "Install selected APKs")
                            }
                            IconButton(onClick = {
                                val files = selectedFiles(state)
                                if (files.isEmpty()) onEvent(UiEvent.ShowMessage("Select files to share"))
                                else apkAction(context, onEvent) { ai.victorious.filemanager.apk.ApkInstaller.share(context, files); null }
                            }) {
                                Icon(Icons.Outlined.Share, contentDescription = "Share / export")
                            }
                            IconButton(onClick = { onEvent(UiEvent.Undo) }, enabled = state.canUndo) {
                                Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = "Undo")
                            }
                        },
                    )
                }
                NavigationBar {
                    NavigationBarItem(
                        selected = state.destination == Dest.FILES,
                        onClick = { onEvent(UiEvent.Navigate(Dest.FILES)) },
                        icon = { Icon(Icons.Outlined.Storage, contentDescription = null) },
                        label = { Text("Files") },
                    )
                    NavigationBarItem(
                        selected = state.destination == Dest.SEARCH,
                        onClick = { onEvent(UiEvent.Navigate(Dest.SEARCH)) },
                        icon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        label = { Text("Search") },
                    )
                    NavigationBarItem(
                        selected = state.destination == Dest.BOOKMARKS,
                        onClick = { onEvent(UiEvent.Navigate(Dest.BOOKMARKS)) },
                        icon = { Icon(Icons.Outlined.BookmarkBorder, contentDescription = null) },
                        label = { Text("Bookmarks") },
                    )
                    NavigationBarItem(
                        selected = state.destination == Dest.APPS,
                        onClick = { onEvent(UiEvent.Navigate(Dest.APPS)) },
                        icon = { Icon(Icons.Outlined.Apps, contentDescription = null) },
                        label = { Text("Apps") },
                    )
                    NavigationBarItem(
                        selected = state.destination == Dest.TOOLS,
                        onClick = { onEvent(UiEvent.Navigate(Dest.TOOLS)) },
                        icon = { Icon(Icons.Outlined.Build, contentDescription = null) },
                        label = { Text("Tools") },
                    )
                    NavigationBarItem(
                        selected = state.destination == Dest.TRASH,
                        onClick = { onEvent(UiEvent.Navigate(Dest.TRASH)) },
                        icon = { Icon(Icons.Outlined.RestoreFromTrash, contentDescription = null) },
                        label = { Text("Trash") },
                    )
                    NavigationBarItem(
                        selected = state.destination == Dest.SETTINGS,
                        onClick = { onEvent(UiEvent.Navigate(Dest.SETTINGS)) },
                        icon = { Icon(Icons.Outlined.SettingsIcon, contentDescription = null) },
                        label = { Text("Settings") },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!state.hasStorageAccess) {
                PermissionBanner(
                    onGrant = {
                        if (Build.VERSION.SDK_INT >= 30) {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        } else {
                            permissionLauncher.launch(
                                arrayOf(
                                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                ),
                            )
                        }
                    },
                )
            }
            when (state.destination) {
                Dest.FILES -> DualPane(state, onEvent)
                Dest.SEARCH -> SearchPane(state, onEvent)
                Dest.BOOKMARKS -> BookmarksPane(state, onEvent)
                Dest.APPS -> {
                    val dir = if (state.focusedPane == 0) state.left.active.path else state.right.active.path
                    AppsPane(backupDir = dir, onEvent = onEvent)
                }
                Dest.TOOLS -> ToolsPane(state, onEvent)
                Dest.TRASH -> TrashPane(state, onEvent)
                Dest.SETTINGS -> SettingsPane(onEvent)
            }
        }
    }

    if (state.renameTarget != null) {
        RenameDialog(
            currentName = File(state.renameTarget).name,
            onDismiss = { onEvent(UiEvent.DismissRename) },
            onConfirm = { onEvent(UiEvent.ConfirmRename(it)) },
        )
    }
    state.apkInfo?.let { ApkInfoDialog(it, onEvent) }
    state.signTarget?.let { SignDialog(it, onEvent) }
    state.rebuiltApk?.let { RebuiltSignReminder(it, onEvent) }
    state.verifyResult?.let { (path, r) -> VerifyDialog(path, r, onEvent) }
    state.bundle?.let { BundleDialog(it, onEvent) }
    if (state.showAbout) {
        AboutDialog(state = state, onDismiss = { onEvent(UiEvent.ToggleAbout) })
    }
}

@Composable
private fun PermissionBanner(onGrant: () -> Unit) {
    Surface(
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Storage access needed", fontWeight = FontWeight.Medium)
                Text(
                    "All-files access keeps local browsing snappy on Android 10+. The app still works in its own folders without it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = onGrant) { Text("Grant") }
        }
    }
}

@Composable
private fun DualPane(state: UiState, onEvent: (UiEvent) -> Unit) {
    val wide = LocalConfiguration.current.screenWidthDp >= 700
    if (wide) {
        Row(Modifier.fillMaxSize()) {
            FilePane(0, state.left, state.focusedPane == 0, onEvent, Modifier.weight(1f))
            VerticalDivider()
            FilePane(1, state.right, state.focusedPane == 1, onEvent, Modifier.weight(1f))
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            FilePane(0, state.left, state.focusedPane == 0, onEvent, Modifier.weight(1f))
            HorizontalDivider()
            FilePane(1, state.right, state.focusedPane == 1, onEvent, Modifier.weight(1f))
        }
    }
}

@Composable
private fun FilePane(
    index: Int,
    pane: PaneState,
    focused: Boolean,
    onEvent: (UiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val border = if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    else MaterialTheme.colorScheme.surface
    Column(
        modifier
            .fillMaxHeight()
            .background(border)
            .clickable { onEvent(UiEvent.FocusPane(index)) },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { onEvent(UiEvent.GoUp(index)) }) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Up")
            }
            pane.tabs.forEachIndexed { tabIndex, tab ->
                val selected = tabIndex == pane.activeIndex
                Surface(
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .clickable { onEvent(UiEvent.SwitchTab(index, tabIndex)) },
                ) {
                    Row(
                        Modifier.padding(start = 10.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(tab.title, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        IconButton(
                            onClick = { onEvent(UiEvent.CloseTab(index, tabIndex)) },
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(Icons.Outlined.Close, contentDescription = "Close tab", modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
            IconButton(onClick = { onEvent(UiEvent.NewTab(index)) }) {
                Icon(Icons.Outlined.Add, contentDescription = "New tab")
            }
        }
        Text(
            pane.active.path,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        )
        if (pane.active.error != null) {
            Text(
                pane.active.error!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp),
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 12.dp),
        ) {
            items(pane.active.entries, key = { it.path }) { entry ->
                FileRow(
                    entry = entry,
                    selected = entry.path in pane.selected,
                    onClick = {
                        onEvent(UiEvent.FocusPane(index))
                        if (entry.isDirectory && pane.selected.isEmpty()) {
                            onEvent(UiEvent.Open(index, entry.path))
                        } else if (pane.selected.isEmpty()) {
                            onEvent(UiEvent.OpenFile(entry.path))
                        } else {
                            onEvent(UiEvent.Select(index, entry.path, additive = pane.selected.isNotEmpty()))
                        }
                    },
                    onLongClick = {
                        onEvent(UiEvent.FocusPane(index))
                        onEvent(UiEvent.Select(index, entry.path, additive = true))
                    },
                    menu = if (entry.isDirectory) { dismiss ->
                        FolderMenuItems(entry.path, onEvent, dismiss)
                    } else { dismiss ->
                        OpenWithItems(entry.path, onEvent, dismiss)
                        ShareMenuItem(entry.path, onEvent, dismiss)
                        if (isApk(entry.path)) ApkMenuItems(entry.path, onEvent, dismiss)
                        if (entry.path.endsWith(".dex", ignoreCase = true)) DexMenuItems(entry.path, onEvent, dismiss)
                        if (ai.victorious.filemanager.core.apk.ApkBundles.isBundleName(entry.name)) {
                            BundleMenuItems(entry.path, onEvent, dismiss)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun FileRow(
    entry: FileEntry,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    menu: (@Composable (dismiss: () -> Unit) -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val bg = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    else MaterialTheme.colorScheme.surface
    Row(
        Modifier
            .fillMaxWidth()
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = { if (menu != null) menuOpen = true else onLongClick() })
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (entry.isDirectory) Icons.Outlined.Folder else Icons.Outlined.Description,
            contentDescription = null,
            tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(
                meta(entry),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (menu != null) {
            Box {
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Select") }, onClick = { menuOpen = false; onLongClick() })
                    menu { menuOpen = false }
                }
            }
        }
    }
}

@Composable
private fun OpenWithItems(path: String, onEvent: (UiEvent) -> Unit, dismiss: () -> Unit) {
    listOf(
        "Open in text editor" to ViewerMode.TEXT,
        "Open in hex editor" to ViewerMode.HEX,
        "Preview image" to ViewerMode.IMAGE,
        "Media info" to ViewerMode.MEDIA_INFO,
        "Open as archive" to ViewerMode.ARCHIVE,
    ).forEach { (label, mode) ->
        DropdownMenuItem(text = { Text(label) }, onClick = { dismiss(); onEvent(UiEvent.OpenFile(path, mode)) })
    }
}

@Composable
private fun SearchPane(state: UiState, onEvent: (UiEvent) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = { onEvent(UiEvent.Search(it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Search this device") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "${state.searchHits.size} results",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn {
            items(state.searchHits, key = { it.entry.path }) { hit ->
                FileRow(
                    entry = hit.entry,
                    selected = false,
                    onClick = {
                        if (hit.entry.isDirectory) onEvent(UiEvent.OpenSearchHit(hit.entry.path, true))
                        else onEvent(UiEvent.OpenFile(hit.entry.path))
                    },
                    onLongClick = {},
                )
            }
        }
    }
}

@Composable
private fun BookmarksPane(state: UiState, onEvent: (UiEvent) -> Unit) {
    if (state.bookmarks.isEmpty()) {
        EmptyState("No bookmarks yet", "Star the current folder from Files.")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(state.bookmarks, key = { it.id }) { bookmark ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onEvent(UiEvent.OpenBookmark(bookmark.path)) }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Bookmark, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(bookmark.label, fontWeight = FontWeight.Medium)
                    Text(bookmark.path, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { onEvent(UiEvent.RemoveBookmark(bookmark.id)) }) {
                    Icon(Icons.Outlined.Close, contentDescription = "Remove")
                }
            }
        }
    }
}

@Composable
private fun TrashPane(state: UiState, onEvent: (UiEvent) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${state.trash.size} item(s)", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { onEvent(UiEvent.EmptyTrash) }, enabled = state.trash.isNotEmpty()) {
                Text("Empty trash")
            }
        }
        if (state.trash.isEmpty()) {
            EmptyState("Trash is empty", "Deleted files land here so you can restore them.")
            return
        }
        LazyColumn {
            items(state.trash, key = { it.id }) { item ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(item.originalName, fontWeight = FontWeight.Medium)
                        Text(item.originalPath, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { onEvent(UiEvent.RestoreTrash(item.id)) }) { Text("Restore") }
                    IconButton(onClick = { onEvent(UiEvent.DeleteTrash(item.id)) }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Delete forever")
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, body: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RenameDialog(currentName: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = {
            OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true)
        },
        confirmButton = {
            Button(onClick = { onConfirm(value.trim()) }, enabled = value.trim().isNotEmpty()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AboutDialog(state: UiState, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Victorious File Manager 0.1.0") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Standalone file manager. applicationId ai.victorious.filemanager")
                Text("Works fully without DroidCommand AI.")
                Text("Optional Messenger stub: ai.victorious.filemanager.action.BRIDGE")
                Text("Node id: ${state.nodeId}", style = MaterialTheme.typography.bodySmall)
                Text(
                    if (state.dcaInstalled) "DroidCommand AI is installed (optional)."
                    else "DroidCommand AI is not installed. That is fine — this app does not require it.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Fleet class is pending owner choice (EXTERNAL_NODE vs BOUND_COMPANION). This stub is not LIVE control.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private fun pane(state: UiState): PaneState = if (state.focusedPane == 0) state.left else state.right

private fun meta(entry: FileEntry): String {
    val whenStr = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.modifiedEpochMs))
    return if (entry.isDirectory) "Folder · $whenStr" else "${formatSize(entry.sizeBytes)} · $whenStr"
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(java.util.Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(java.util.Locale.US, "%.1f MB", mb)
    return String.format(java.util.Locale.US, "%.1f GB", mb / 1024.0)
}
