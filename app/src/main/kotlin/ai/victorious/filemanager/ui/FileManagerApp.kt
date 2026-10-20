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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.outlined.CreateNewFolder
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
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
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
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
    var showCreate by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { onRefreshPermissions() }

    if (state.showTerminal) {
        val appCtx = LocalContext.current.applicationContext as ai.victorious.filemanager.FileManagerApplication
        Box(Modifier.fillMaxSize()) {
            TerminalScreen(
                appCtx.terminals,
                onClose = { onEvent(UiEvent.CloseTerminal) },
                cwd = pane(state).active.path,
            )
            SnackbarHost(snackbarHost, Modifier.align(Alignment.BottomCenter))
        }
        return
    }

    if (state.showNetwork) {
        Box(Modifier.fillMaxSize()) {
            NetworkConnectionsScreen(
                onOpenRemote = { path ->
                    onEvent(UiEvent.CloseNetwork)
                    onEvent(UiEvent.Open(state.focusedPane, path))
                    onEvent(UiEvent.Navigate(Dest.FILES))
                },
                onMessage = { onEvent(UiEvent.ShowMessage(it)) },
                onClose = { onEvent(UiEvent.CloseNetwork) },
            )
            SnackbarHost(snackbarHost, Modifier.align(Alignment.BottomCenter))
        }
        return
    }

    if (state.showWifiTransfer) {
        val pane = if (state.focusedPane == 0) state.left else state.right
        Box(Modifier.fillMaxSize()) {
            WifiTransferScreen(
                rootPath = pane.active.path,
                onMessage = { onEvent(UiEvent.ShowMessage(it)) },
                onClose = { onEvent(UiEvent.CloseWifiTransfer) },
            )
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

    // Window-size (not screen-size) driven, so split-screen, freeform and foldables adapt too.
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val wideWindow = isWideWindow(maxWidth.value)
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
                                Dest.NOTES -> "Notes"
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
                if (!wideWindow) {
                    NavigationBar {
                        for (item in NavItems) {
                            NavigationBarItem(
                                selected = state.destination == item.dest,
                                onClick = { onEvent(UiEvent.Navigate(item.dest)) },
                                icon = { Icon(item.icon, contentDescription = null) },
                                label = { Text(item.label) },
                            )
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { padding ->
      Row(Modifier.fillMaxSize().padding(padding)) {
        if (wideWindow) {
            NavigationRail {
                for (item in NavItems) {
                    NavigationRailItem(
                        selected = state.destination == item.dest,
                        onClick = { onEvent(UiEvent.Navigate(item.dest)) },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(item.label) },
                    )
                }
            }
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight(),
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
                Dest.FILES -> DualPane(state, onEvent, onCreate = { showCreate = true })
                Dest.SEARCH -> SearchPane(state, onEvent)
                Dest.BOOKMARKS -> BookmarksPane(state, onEvent)
                Dest.APPS -> AppManagerProPane(onEvent = onEvent)
                Dest.NOTES -> NotesVaultPane(onEvent = onEvent)
                Dest.TOOLS -> ToolsPane(state, onEvent)
                Dest.TRASH -> TrashPane(state, onEvent)
                Dest.SETTINGS -> SettingsPane(onEvent)
            }
        }
      }
    }
    }

    if (showCreate) {
        CreateDialog(
            onDismiss = { showCreate = false },
            onConfirm = { name, folder -> showCreate = false; onEvent(UiEvent.Create(name, folder)) },
        )
    }
    if (state.renameTarget != null) {
        RenameDialog(
            currentName = File(state.renameTarget).name,
            onDismiss = { onEvent(UiEvent.DismissRename) },
            onConfirm = { onEvent(UiEvent.ConfirmRename(it)) },
        )
    }
    state.batchRename?.let { br ->
        LaunchedEffect(br.targets) { onEvent(UiEvent.UpdateBatchRename(br.spec)) }
        BatchRenameDialog(br, onEvent)
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
private fun DualPane(state: UiState, onEvent: (UiEvent) -> Unit, onCreate: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (panesSideBySide(maxWidth.value, maxHeight.value)) {
            Row(Modifier.fillMaxSize()) {
                FilePane(0, state.left, state.focusedPane == 0, onEvent, onCreate, Modifier.weight(1f))
                VerticalDivider()
                FilePane(1, state.right, state.focusedPane == 1, onEvent, onCreate, Modifier.weight(1f))
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                FilePane(0, state.left, state.focusedPane == 0, onEvent, onCreate, Modifier.weight(1f))
                HorizontalDivider()
                FilePane(1, state.right, state.focusedPane == 1, onEvent, onCreate, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun FilePane(
    index: Int,
    pane: PaneState,
    focused: Boolean,
    onEvent: (UiEvent) -> Unit,
    onCreate: () -> Unit,
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
            // Tabs scroll on their own so long folder names never push the close buttons or the
            // New tab / New folder actions off a phone-width screen. The active tab (e.g. one just
            // opened at the end of the strip) is scrolled into view.
            val tabRequesters = remember(pane.tabs.size) { List(pane.tabs.size) { BringIntoViewRequester() } }
            LaunchedEffect(pane.activeIndex, pane.tabs.size) {
                androidx.compose.runtime.withFrameNanos { } // after layout, so the chip has coordinates
                tabRequesters.getOrNull(pane.activeIndex)?.bringIntoView()
            }
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                pane.tabs.forEachIndexed { tabIndex, tab ->
                    val selected = tabIndex == pane.activeIndex
                    Surface(
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier
                            .bringIntoViewRequester(tabRequesters[tabIndex])
                            .padding(end = 4.dp)
                            .clickable { onEvent(UiEvent.SwitchTab(index, tabIndex)) },
                    ) {
                        Row(
                            Modifier.padding(start = 10.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                tab.title,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 120.dp),
                            )
                            IconButton(
                                onClick = { onEvent(UiEvent.CloseTab(index, tabIndex)) },
                                modifier = Modifier.size(24.dp),
                            ) {
                                Icon(Icons.Outlined.Close, contentDescription = "Close tab", modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }
            IconButton(onClick = { onEvent(UiEvent.NewTab(index)) }) {
                Icon(Icons.Outlined.Add, contentDescription = "New tab")
            }
            IconButton(onClick = { onEvent(UiEvent.FocusPane(index)); onCreate() }) {
                Icon(Icons.Outlined.CreateNewFolder, contentDescription = "New folder or file")
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
        "Play video" to ViewerMode.VIDEO,
        "Play audio" to ViewerMode.AUDIO,
        "View PDF" to ViewerMode.PDF,
        "Preview font" to ViewerMode.FONT,
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
            supportingText = { Text("Terms are AND-ed · *.pdf filters by type · /folder matches the path") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        )
        Spacer(Modifier.height(12.dp))
        Text(
            state.searchStatus ?: "${state.searchHits.size} results",
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
private fun CreateDialog(onDismiss: () -> Unit, onConfirm: (name: String, folder: Boolean) -> Unit) {
    var value by remember { mutableStateOf("") }
    val ok = value.trim().isNotEmpty() && ai.victorious.filemanager.core.path.PathHelpers.isValidFileName(value.trim())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create in this folder") },
        text = {
            OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true, label = { Text("Name") })
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onConfirm(value.trim(), false) }, enabled = ok) { Text("New file") }
                Button(onClick = { onConfirm(value.trim(), true) }, enabled = ok) { Text("New folder") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun BatchRenameDialog(state: BatchRenameState, onEvent: (UiEvent) -> Unit) {
    val spec = state.spec
    fun update(next: ai.victorious.filemanager.core.rename.RenameSpec) = onEvent(UiEvent.UpdateBatchRename(next))
    fun intField(text: String, fallback: Int) = text.toIntOrNull() ?: fallback
    AlertDialog(
        onDismissRequest = { onEvent(UiEvent.DismissBatchRename) },
        title = { Text("Rename ${state.targets.size} items") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(spec.find, { update(spec.copy(find = it)) }, label = { Text("Find") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(spec.replace, { update(spec.copy(replace = it)) }, label = { Text("Replace with") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(spec.regex, { update(spec.copy(regex = it)) }); Text("Regex")
                    Spacer(Modifier.width(12.dp))
                    Checkbox(spec.caseSensitive, { update(spec.copy(caseSensitive = it)) }); Text("Match case")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ai.victorious.filemanager.core.rename.CaseMode.entries.forEach { mode ->
                        FilterChip(spec.caseMode == mode, { update(spec.copy(caseMode = mode)) }, label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) })
                    }
                }
                OutlinedTextField(spec.template, { update(spec.copy(template = it)) }, label = { Text("Template: {name} {n} {ext}") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(spec.counterStart.toString(), { update(spec.copy(counterStart = intField(it, 0))) }, label = { Text("Start") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(spec.counterStep.toString(), { update(spec.copy(counterStep = intField(it, 1))) }, label = { Text("Step") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(spec.counterPad.toString(), { update(spec.copy(counterPad = intField(it, 1))) }, label = { Text("Pad") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(
                    spec.newExtension.orEmpty(),
                    { update(spec.copy(newExtension = it.ifEmpty { null })) },
                    label = { Text("New extension (blank keeps)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                HorizontalDivider()
                state.plan.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                state.plan.items.forEach { item ->
                    val bad = item.status == ai.victorious.filemanager.core.rename.PlanStatus.INVALID ||
                        item.status == ai.victorious.filemanager.core.rename.PlanStatus.CONFLICT
                    Text(
                        if (item.status == ai.victorious.filemanager.core.rename.PlanStatus.UNCHANGED) item.oldName
                        else "${item.oldName} → ${item.newName}" + (item.reason?.let { "  ($it)" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onEvent(UiEvent.ConfirmBatchRename) }, enabled = state.plan.isApplicable) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = { onEvent(UiEvent.DismissBatchRename) }) { Text("Cancel") } },
    )
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
        title = { Text("VictorFiles 0.1.0") },
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
                Text(
                    "RAR extraction uses junrar (UnRAR license, extraction only; the code may not be used to " +
                        "develop a RAR (WinRAR) compatible archiver). Licence text: assets/licenses/junrar-UnRAR-LICENSE.txt.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Terminal PTY uses Termux terminal-emulator/terminal-view (Apache-2.0, jackpal). " +
                        "Licence: assets/licenses/termux-terminal-Apache-2.0.txt. Falls back to a pipe shell if libtermux fails.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private fun pane(state: UiState): PaneState = if (state.focusedPane == 0) state.left else state.right

private val RowDateFormat: DateFormat by lazy { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

private fun meta(entry: FileEntry): String {
    // One formatter for all rows (was one allocation per visible row per recomposition). Main thread only.
    val whenStr = RowDateFormat.format(Date(entry.modifiedEpochMs))
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

private class NavItem(val dest: Dest, val icon: androidx.compose.ui.graphics.vector.ImageVector, val label: String)

private val NavItems = listOf(
    NavItem(Dest.FILES, Icons.Outlined.Storage, "Files"),
    NavItem(Dest.SEARCH, Icons.Outlined.Search, "Search"),
    NavItem(Dest.BOOKMARKS, Icons.Outlined.BookmarkBorder, "Bookmarks"),
    NavItem(Dest.APPS, Icons.Outlined.Apps, "Apps"),
    NavItem(Dest.NOTES, Icons.Outlined.Description, "Notes"),
    NavItem(Dest.TOOLS, Icons.Outlined.Build, "Tools"),
    NavItem(Dest.TRASH, Icons.Outlined.RestoreFromTrash, "Trash"),
    NavItem(Dest.SETTINGS, Icons.Outlined.SettingsIcon, "Settings"),
)
