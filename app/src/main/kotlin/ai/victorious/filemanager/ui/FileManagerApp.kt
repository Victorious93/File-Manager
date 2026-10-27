@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package ai.victorious.filemanager.ui

import ai.victorious.filemanager.notes.NotesVaultPane

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
import androidx.compose.material.icons.outlined.Splitscreen
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import java.io.File
import ai.victorious.filemanager.ui.theme.effectiveDualPane
import ai.victorious.filemanager.ui.theme.fileCategory
import ai.victorious.filemanager.ui.theme.Spacing
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material.icons.outlined.ClearAll
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign

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
    val themeSettings = (context.applicationContext as ai.victorious.filemanager.FileManagerApplication).themeSettings
    val prefs = themeSettings.state.collectAsState().value
    val windowLayout = rememberWindowLayout()
    // Expanded windows (tablets, unfolded foldables) show both panes unless the user opted out.
    val dualPane = prefs.effectiveDualPane(windowLayout.expanded)
    LaunchedEffect(windowLayout.expanded) { themeSettings.expandedWindow.value = windowLayout.expanded }
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
        val otherDir = transferDir(dualPane, state.focusedPane, state.left.active.path, state.right.active.path)
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
                                Dest.HOME -> "Home"
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
                        IconButton(onClick = {
                            themeSettings.update { it.copy(gridView = !it.gridView) }
                        }) {
                            Icon(
                                if (prefs.gridView) Icons.AutoMirrored.Outlined.ViewList else Icons.Outlined.GridView,
                                contentDescription = if (prefs.gridView) "List view" else "Grid view",
                            )
                        }
                        IconButton(onClick = {
                            val next = !dualPane
                            // Turning panes off on a wide window also stops them coming back automatically.
                            themeSettings.update {
                                it.copy(dualPane = next, autoDualOnWide = if (!next && windowLayout.expanded) false else it.autoDualOnWide)
                            }
                        }) {
                            Icon(
                                Icons.Outlined.Splitscreen,
                                contentDescription = if (dualPane) "Show one pane" else "Show two panes",
                            )
                        }
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
                    val focused = pane(state)
                    AnimatedVisibility(
                        visible = focused.selected.isNotEmpty(),
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        SelectionBar(
                            count = focused.selected.size,
                            onSelectAll = { onEvent(UiEvent.SelectAll(state.focusedPane)) },
                            onClear = { onEvent(UiEvent.ClearSelection(state.focusedPane)) },
                        )
                    }
                    state.clipboard?.let { ClipboardBar(it, onEvent) }
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
                        for (item in NavItems.filter { it.compact }) {
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
            Crossfade(state.destination, label = "destination") { dest ->
            when (dest) {
                Dest.HOME -> HomeDashboard(state, onEvent)
                Dest.FILES ->
                    if (dualPane) DualPane(state, prefs.gridView, windowLayout.hinge, onEvent, onCreate = { showCreate = true })
                    else SinglePane(state, prefs.gridView, onEvent, onCreate = { showCreate = true })
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
    state.splitTarget?.let { path ->
        SplitDialog(
            file = File(path),
            onDismiss = { onEvent(UiEvent.DismissSplit) },
            onConfirm = { onEvent(UiEvent.ConfirmSplit(it)) },
        )
    }
    state.properties?.let { PropertiesDialog(it, onEvent) }
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

/** One file list filling the screen: the focused pane. The other pane keeps its folder and tabs, hidden. */
@Composable
private fun SinglePane(state: UiState, grid: Boolean, onEvent: (UiEvent) -> Unit, onCreate: () -> Unit) {
    val index = state.focusedPane
    FilePane(index, pane(state), true, state.listing, grid, state.busy, onEvent, onCreate, Modifier.fillMaxSize())
}

/** Contextual bar while items are selected: how many, select all, clear. Sits above the action bar. */
@Composable
private fun SelectionBar(count: Int, onSelectAll: () -> Unit, onClear: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = Spacing.l, end = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$count selected",
                modifier = Modifier.weight(1f).testTag("selection-count"),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            IconButton(onClick = onSelectAll) { Icon(Icons.Outlined.SelectAll, contentDescription = "Select all") }
            IconButton(onClick = onClear) { Icon(Icons.Outlined.ClearAll, contentDescription = "Clear selection") }
        }
    }
}

/** Shown while Copy/Move has staged items: paste them into whatever folder is open, or drop them. */
@Composable
private fun ClipboardBar(clip: Clipboard, onEvent: (UiEvent) -> Unit) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${if (clip.move) "Cut" else "Copied"} ${clip.sources.size} item(s)",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { onEvent(UiEvent.PasteHere) }) { Text("Paste here") }
            IconButton(onClick = { onEvent(UiEvent.ClearClipboard) }) {
                Icon(Icons.Outlined.Close, contentDescription = "Clear clipboard")
            }
        }
    }
}

@Composable
private fun DualPane(
    state: UiState,
    grid: Boolean,
    hinge: HingeSplit?,
    onEvent: (UiEvent) -> Unit,
    onCreate: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (hinge != null || panesSideBySide(maxWidth.value, maxHeight.value)) {
            // On a foldable, split at the hinge so no file row sits under the fold.
            val leftWeight = hinge?.leftFraction ?: 0.5f
            Row(Modifier.fillMaxSize()) {
                FilePane(0, state.left, state.focusedPane == 0, state.listing, grid, state.busy, onEvent, onCreate, Modifier.weight(leftWeight))
                if (hinge != null && hinge.gap.value > 0f) Spacer(Modifier.width(hinge.gap)) else VerticalDivider()
                FilePane(1, state.right, state.focusedPane == 1, state.listing, grid, state.busy, onEvent, onCreate, Modifier.weight(1f - leftWeight))
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                FilePane(0, state.left, state.focusedPane == 0, state.listing, grid, state.busy, onEvent, onCreate, Modifier.weight(1f))
                HorizontalDivider()
                FilePane(1, state.right, state.focusedPane == 1, state.listing, grid, state.busy, onEvent, onCreate, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun FilePane(
    index: Int,
    pane: PaneState,
    focused: Boolean,
    listing: ai.victorious.filemanager.core.listing.ListingOptions,
    grid: Boolean,
    busy: Boolean,
    onEvent: (UiEvent) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val border = if (focused) MaterialTheme.colorScheme.surfaceContainerLow
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
        BreadcrumbBar(pane.active.path, onOpen = { onEvent(UiEvent.FocusPane(index)); onEvent(UiEvent.Open(index, it)) })
        ListingBar(index, listing, onEvent)
        if (pane.active.error != null) {
            Text(
                pane.active.error!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp),
            )
        }
        val visibleEntries = remember(pane.active.entries, listing) { listing.apply(pane.active.entries) }
        val open: (FileEntry) -> Unit = { entry ->
            onEvent(UiEvent.FocusPane(index))
            if (entry.isDirectory && pane.selected.isEmpty()) {
                onEvent(UiEvent.Open(index, entry.path))
            } else if (pane.selected.isEmpty()) {
                onEvent(UiEvent.OpenFile(entry.path))
            } else {
                onEvent(UiEvent.Select(index, entry.path, additive = pane.selected.isNotEmpty()))
            }
        }
        val toggle: (FileEntry) -> Unit = { entry ->
            onEvent(UiEvent.FocusPane(index))
            onEvent(UiEvent.Select(index, entry.path, additive = true))
        }
        val menuFor: (FileEntry) -> (@Composable (dismiss: () -> Unit) -> Unit) = { entry ->
            if (entry.isDirectory) { dismiss ->
                FolderMenuItems(entry.path, onEvent, dismiss)
            } else { dismiss ->
                OpenWithItems(entry.path, onEvent, dismiss)
                SplitJoinMenuItem(entry.path, onEvent, dismiss)
                ShareMenuItem(entry.path, onEvent, dismiss)
                if (isApk(entry.path)) ApkMenuItems(entry.path, onEvent, dismiss)
                if (entry.path.endsWith(".dex", ignoreCase = true)) DexMenuItems(entry.path, onEvent, dismiss)
                if (ai.victorious.filemanager.core.apk.ApkBundles.isBundleName(entry.name)) {
                    BundleMenuItems(entry.path, onEvent, dismiss)
                }
            }
        }
        when {
            visibleEntries.isEmpty() && pane.active.error == null && !busy -> EmptyState(
                title = if (listing.filter.isNotEmpty()) "No matches" else "This folder is empty",
                body = if (listing.filter.isNotEmpty()) "Nothing here matches \"${listing.filter}\"." else "Use New folder or file to add something, or paste here.",
                icon = Icons.Outlined.FolderOff,
            )
            grid -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 104.dp),
                modifier = Modifier.fillMaxSize().testTag("file-grid"),
                contentPadding = PaddingValues(Spacing.s),
            ) {
                gridItems(visibleEntries, key = { it.path }, contentType = { if (it.isDirectory) 0 else 1 }) { entry ->
                    FileTile(
                        entry = entry,
                        selected = entry.path in pane.selected,
                        onClick = { open(entry) },
                        onToggleSelect = { toggle(entry) },
                        menu = menuFor(entry),
                        modifier = Modifier.animateItem(),
                    )
                }
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 12.dp),
            ) {
                items(visibleEntries, key = { it.path }, contentType = { if (it.isDirectory) 0 else 1 }) { entry ->
                    FileRow(
                        entry = entry,
                        selected = entry.path in pane.selected,
                        onClick = { open(entry) },
                        onLongClick = { toggle(entry) },
                        menu = menuFor(entry),
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

/**
 * One file in list view: tonal type badge (tap it to select), name and details, and a "More options"
 * menu. Long-press opens the same menu (its first item is Select); a horizontal swipe toggles selection.
 */
@Composable
private fun FileRow(
    entry: FileEntry,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    menu: (@Composable (dismiss: () -> Unit) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var drag by remember { mutableStateOf(0f) }
    val offset by animateFloatAsState(drag, label = "swipe")
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer
    else MaterialTheme.colorScheme.surface.copy(alpha = 0f)
    val swipeThreshold = with(androidx.compose.ui.platform.LocalDensity.current) { 72.dp.toPx() }
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.s, vertical = 2.dp)
            .graphicsLayer { translationX = offset }
            .background(bg, MaterialTheme.shapes.medium)
            .pointerInput(entry.path) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (kotlin.math.abs(drag) > swipeThreshold) onLongClick()
                        drag = 0f
                    },
                    onDragCancel = { drag = 0f },
                ) { change, amount ->
                    change.consume()
                    drag = (drag + amount).coerceIn(-swipeThreshold * 1.4f, swipeThreshold * 1.4f)
                }
            }
            .combinedClickable(onClick = onClick, onLongClick = { if (menu != null) menuOpen = true else onLongClick() })
            .padding(horizontal = Spacing.s, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryBadge(
            fileCategory(entry.name, entry.isDirectory),
            selected = selected,
            modifier = Modifier.clickable(onClick = onLongClick),
        )
        Spacer(Modifier.width(Spacing.m))
        Column(Modifier.weight(1f)) {
            Text(
                entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
            )
            Text(
                meta(entry),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (menu != null) {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "More options", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text(if (selected) "Deselect" else "Select") }, onClick = { menuOpen = false; onLongClick() })
                    menu { menuOpen = false }
                }
            }
        }
    }
}

/** One file in grid view: a large type badge with the name under it. Same gestures as [FileRow]. */
@Composable
private fun FileTile(
    entry: FileEntry,
    selected: Boolean,
    onClick: () -> Unit,
    onToggleSelect: () -> Unit,
    menu: @Composable (dismiss: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
    Box(modifier.padding(Spacing.xs)) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(bg, MaterialTheme.shapes.medium)
                .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
                .padding(Spacing.s),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CategoryBadge(
                fileCategory(entry.name, entry.isDirectory),
                selected = selected,
                size = 56.dp,
                modifier = Modifier.clickable(onClick = onToggleSelect),
            )
            Spacer(Modifier.height(Spacing.s))
            Text(
                entry.name,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                if (entry.isDirectory) "Folder" else formatSize(entry.sizeBytes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(text = { Text(if (selected) "Deselect" else "Select") }, onClick = { menuOpen = false; onToggleSelect() })
            menu { menuOpen = false }
        }
    }
}

@Composable
private fun OpenWithItems(path: String, onEvent: (UiEvent) -> Unit, dismiss: () -> Unit) {
    DropdownMenuItem(text = { Text("Properties / checksums") }, onClick = { dismiss(); onEvent(UiEvent.ShowProperties(path)) })
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
    var inContents by remember { mutableStateOf(false) }
    var regex by remember { mutableStateOf(false) }
    var globs by remember { mutableStateOf("") }
    var contentHits by remember { mutableStateOf<List<ai.victorious.filemanager.core.search.ContentHit>>(emptyList()) }
    var contentError by remember { mutableStateOf<String?>(null) }
    val scope = pane(state).active.path
    LaunchedEffect(inContents, regex, globs, state.searchQuery, scope) {
        if (!inContents) { contentHits = emptyList(); contentError = null; return@LaunchedEffect }
        kotlinx.coroutines.delay(350)
        val q = ai.victorious.filemanager.core.search.ContentQuery(
            text = state.searchQuery,
            regex = regex,
            nameGlobs = globs.split(',', ' ').filter { it.isNotBlank() },
        )
        val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
            runCatching { ai.victorious.filemanager.core.search.ContentSearch.search(File(scope), q) { job?.isActive == false } }
        }
        r.onSuccess { contentHits = it; contentError = null }.onFailure { contentError = it.message; contentHits = emptyList() }
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = { onEvent(UiEvent.Search(it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(if (inContents) "Search inside files" else "Search this device") },
            supportingText = { Text("Terms are AND-ed · *.pdf · /folder · type:image · size>10mb · newer:7d") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        )
        if (!inContents) {
            // Library-style quick filters: each chip swaps its own token into the query.
            fun withToken(prefix: String, token: String): String {
                val kept = state.searchQuery.split(' ').filter { it.isNotBlank() && !it.startsWith(prefix) }
                return (kept + token).joinToString(" ")
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ai.victorious.filemanager.core.search.FileCategory.entries.forEach { c ->
                    FilterChip(
                        selected = "type:${c.token}" in state.searchQuery.split(' '),
                        onClick = { onEvent(UiEvent.Search(withToken("type:", "type:${c.token}"))) },
                        label = { Text(c.label) },
                    )
                }
                FilterChip(
                    selected = "newer:7d" in state.searchQuery.split(' '),
                    onClick = { onEvent(UiEvent.Search(withToken("newer:", "newer:7d"))) },
                    label = { Text("Recent") },
                )
                FilterChip(
                    selected = "size>100mb" in state.searchQuery.split(' '),
                    onClick = { onEvent(UiEvent.Search(withToken("size>", "size>100mb"))) },
                    label = { Text("Large") },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(inContents, { inContents = it }); Text("File contents")
            Spacer(Modifier.width(8.dp))
            Checkbox(regex, { regex = it }, enabled = inContents); Text("Regex")
        }
        if (inContents) {
            OutlinedTextField(globs, { globs = it }, label = { Text("Only files (e.g. *.smali, *.xml)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Searching in $scope", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            contentError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        Spacer(Modifier.height(12.dp))
        if (inContents) {
            Text("${contentHits.size} matches", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn {
                items(contentHits) { hit ->
                    Column(Modifier.fillMaxWidth().clickable { onEvent(UiEvent.OpenFile(hit.path)) }.padding(vertical = 6.dp)) {
                        Text("${File(hit.path).name}:${hit.line}", fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(hit.text.trim(), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(hit.path, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            return@Column
        }
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
private fun EmptyState(
    title: String,
    body: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Outlined.Info,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Box(
                Modifier.size(72.dp).background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.extraLarge),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.height(Spacing.l))
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                body, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ListingBar(
    pane: Int,
    options: ai.victorious.filemanager.core.listing.ListingOptions,
    onEvent: (UiEvent) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            options.filter,
            { onEvent(UiEvent.SetListing(options.copy(filter = it))) },
            placeholder = { Text("Filter") },
            singleLine = true,
            modifier = Modifier.weight(1f).testTag("listing-filter"),
        )
        TextButton(onClick = { onEvent(UiEvent.SelectAll(pane)) }) { Text("All") }
        Box {
            TextButton(onClick = { menu = true }) { Text("Sort") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                ai.victorious.filemanager.core.listing.SortKey.entries.forEach { key ->
                    DropdownMenuItem(
                        text = { Text((if (options.sortKey == key) "● " else "") + key.name.lowercase().replaceFirstChar { it.uppercase() }) },
                        onClick = { onEvent(UiEvent.SetListing(options.copy(sortKey = key))); menu = false },
                    )
                }
                DropdownMenuItem(
                    text = { Text(if (options.ascending) "Ascending ✓" else "Descending ✓") },
                    onClick = { onEvent(UiEvent.SetListing(options.copy(ascending = !options.ascending))); menu = false },
                )
                DropdownMenuItem(
                    text = { Text((if (options.foldersFirst) "✓ " else "") + "Folders first") },
                    onClick = { onEvent(UiEvent.SetListing(options.copy(foldersFirst = !options.foldersFirst))); menu = false },
                )
                DropdownMenuItem(
                    text = { Text((if (options.showHidden) "✓ " else "") + "Show hidden") },
                    onClick = { onEvent(UiEvent.SetListing(options.copy(showHidden = !options.showHidden))); menu = false },
                )
            }
        }
    }
}

@Composable
private fun PropertiesDialog(p: PropertiesState, onEvent: (UiEvent) -> Unit) {
    var expected by remember(p.path) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { onEvent(UiEvent.CloseProperties) },
        title = { Text(File(p.path).name.ifEmpty { p.path }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(p.path, style = MaterialTheme.typography.bodySmall)
                Text("Modified: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(p.modifiedEpochMs))}")
                if (p.isDirectory) {
                    p.stats?.let {
                        Text("${it.files} file(s), ${it.folders} folder(s)")
                        Text("Total ${ai.victorious.filemanager.core.storage.StorageAnalyzer.human(it.bytes)} (${it.bytes} bytes)")
                        if (it.unreadable > 0) Text("${it.unreadable} folder(s) unreadable", color = MaterialTheme.colorScheme.error)
                    } ?: Text("Calculating…")
                } else {
                    Text("Size: ${ai.victorious.filemanager.core.storage.StorageAnalyzer.human(p.sizeBytes)} (${p.sizeBytes} bytes)")
                    p.hashes?.let { hashes ->
                        hashes.forEach { (alg, value) ->
                            Text("${alg.jca}: $value", style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedTextField(expected, { expected = it }, label = { Text("Paste a checksum to compare") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        if (expected.isNotBlank()) {
                            val ok = hashes.values.any { ai.victorious.filemanager.core.props.FileProperties.matches(expected, it) }
                            Text(
                                if (ok) "Match" else "No match",
                                color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            )
                        }
                    } ?: Text("Hashing…")
                }
                p.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                val current = remember(p.path) { ai.victorious.filemanager.core.props.FileMode.read(File(p.path)) }
                if (current != null) {
                    HorizontalDivider()
                    var modeText by remember(p.path) { mutableStateOf(current.octal) }
                    var recursive by remember(p.path) { mutableStateOf(false) }
                    var modeMsg by remember(p.path) { mutableStateOf<String?>(null) }
                    val parsed = runCatching { ai.victorious.filemanager.core.props.FileMode.parse(modeText) }.getOrNull()
                    Text("Permissions: ${current.symbolic} (${current.octal})")
                    OutlinedTextField(modeText, { modeText = it }, label = { Text("Mode (e.g. 755 or rwxr-xr-x)") }, singleLine = true, isError = parsed == null, modifier = Modifier.fillMaxWidth())
                    if (p.isDirectory) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(recursive, { recursive = it }); Text("Apply to contents") }
                    TextButton(enabled = parsed != null, onClick = {
                        modeMsg = runCatching { "Changed ${ai.victorious.filemanager.core.props.FileMode.apply(File(p.path), parsed!!, recursive)} item(s)" }
                            .getOrElse { "Failed: ${it.message}" }
                    }) { Text("Apply mode") }
                    modeMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onEvent(UiEvent.CloseProperties) }) { Text("Close") } },
    )
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

private val PART_NAME = Regex("""\.\d{3}$""")

@Composable
private fun SplitJoinMenuItem(path: String, onEvent: (UiEvent) -> Unit, dismiss: () -> Unit) {
    if (PART_NAME.containsMatchIn(path)) {
        DropdownMenuItem(text = { Text("Join parts") }, onClick = { dismiss(); onEvent(UiEvent.JoinParts(path)) })
    } else {
        DropdownMenuItem(text = { Text("Split file…") }, onClick = { dismiss(); onEvent(UiEvent.RequestSplit(path)) })
    }
}

@Composable
private fun SplitDialog(file: File, onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    var text by remember { mutableStateOf("100") }
    val size = ai.victorious.filemanager.core.ops.FileSplit.parseSize(text)
    val length = remember(file) { file.length() }
    val parts = size?.let { (length + it - 1) / it }
    val max = ai.victorious.filemanager.core.ops.FileSplit.MAX_PARTS
    val error = when {
        size == null -> "Enter a size like 100, 50MB, 1.5GB, 700k, 512b (bare numbers are MB)"
        parts!! < 2 -> "Part size is not smaller than the file (${length} bytes)"
        parts > max -> "$parts parts is over the $max limit; use a larger size"
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Split ${file.name}") },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true, label = { Text("Part size") },
                isError = error != null,
                supportingText = { Text(error ?: "$parts parts: ${file.name}.001 … in this folder") },
            )
        },
        confirmButton = { Button(onClick = { onConfirm(size!!) }, enabled = error == null) { Text("Split") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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

/** [compact]: shown in the phone bottom bar too; the rail on wider windows shows every destination. */
private class NavItem(
    val dest: Dest,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val label: String,
    val compact: Boolean = true,
)

private val NavItems = listOf(
    NavItem(Dest.HOME, Icons.Outlined.Home, "Home"),
    NavItem(Dest.FILES, Icons.Outlined.Storage, "Files"),
    NavItem(Dest.SEARCH, Icons.Outlined.Search, "Search"),
    NavItem(Dest.BOOKMARKS, Icons.Outlined.BookmarkBorder, "Bookmarks", compact = false),
    NavItem(Dest.APPS, Icons.Outlined.Apps, "Apps"),
    NavItem(Dest.NOTES, Icons.Outlined.Description, "Notes", compact = false),
    NavItem(Dest.TOOLS, Icons.Outlined.Build, "Tools"),
    NavItem(Dest.TRASH, Icons.Outlined.RestoreFromTrash, "Trash"),
    NavItem(Dest.SETTINGS, Icons.Outlined.SettingsIcon, "Settings"),
)
