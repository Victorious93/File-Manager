package ai.victorious.filemanager.ui

import ai.victorious.filemanager.ui.theme.FileCategory
import ai.victorious.filemanager.ui.theme.LocalIconStyle
import ai.victorious.filemanager.ui.theme.Spacing
import ai.victorious.filemanager.ui.theme.fileCategory
import ai.victorious.filemanager.ui.theme.icon
import android.os.Environment
import android.os.StatFs
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Immutable
data class StorageVolumeInfo(val label: String, val path: String, val totalBytes: Long, val freeBytes: Long) {
    val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0)
    val usedFraction: Float get() = if (totalBytes <= 0) 0f else (usedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
}

@Immutable
data class RecentFile(val path: String, val name: String, val modifiedEpochMs: Long, val sizeBytes: Long)

/**
 * Newest files first, ignoring hidden files and anything inside hidden folders (thumbnails, caches, .trash).
 * Pure so it is unit-tested; the I/O walk lives in [scanRecent].
 */
fun pickRecent(files: Sequence<RecentFile>, limit: Int): List<RecentFile> =
    files.filter { f -> f.path.split('/').none { it.startsWith(".") } }
        .sortedByDescending { it.modifiedEpochMs }
        .take(limit)
        .toList()

private fun scanRecent(roots: List<File>, limit: Int = 12, maxDepth: Int = 3, maxFiles: Int = 4000): List<RecentFile> {
    var seen = 0
    val all = roots.asSequence().filter { it.isDirectory }.flatMap { root ->
        root.walkTopDown().maxDepth(maxDepth)
            .onEnter { !it.name.startsWith(".") }
            .filter { it.isFile }
    }.takeWhile { seen++ < maxFiles }
        .map { RecentFile(it.absolutePath, it.name, it.lastModified(), it.length()) }
    return pickRecent(all, limit)
}

private fun statVolume(label: String, dir: File): StorageVolumeInfo? = runCatching {
    val s = StatFs(dir.absolutePath)
    StorageVolumeInfo(label, dir.absolutePath, s.totalBytes, s.availableBytes)
}.getOrNull()?.takeIf { it.totalBytes > 0 }

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var v = bytes / 1024.0
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return String.format(java.util.Locale.US, "%.1f %s", v, units[i])
}

private class QuickTool(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/** A well-known folder worth one tap from Home. */
private class FolderShortcut(val label: String, val path: String, val icon: ImageVector)

private val CommonFolders = listOf(
    "Download" to Icons.Outlined.Download,
    "DCIM" to Icons.Outlined.PhotoCamera,
    "Pictures" to Icons.Outlined.Image,
    "Documents" to Icons.Outlined.Description,
    "Music" to Icons.Outlined.MusicNote,
    "Movies" to Icons.Outlined.Movie,
)

/**
 * Landing dashboard, top to bottom: search, storage, jump-to folders, quick tools, bookmarks, recent files.
 * Everything slow loads off the main thread.
 */
@Composable
fun HomeDashboard(state: UiState, onEvent: (UiEvent) -> Unit) {
    val context = LocalContext.current
    var volumes by remember { mutableStateOf<List<StorageVolumeInfo>?>(null) }
    var recent by remember { mutableStateOf<List<RecentFile>?>(null) }
    var folders by remember { mutableStateOf<List<FolderShortcut>>(emptyList()) }
    LaunchedEffect(state.hasStorageAccess) {
        withContext(Dispatchers.IO) {
            val vols = buildList {
                statVolume("Internal storage", Environment.getExternalStorageDirectory())?.let(::add)
                // Removable cards / USB: the roots of the app's external dirs beyond the primary one.
                context.getExternalFilesDirs(null).drop(1).filterNotNull().forEachIndexed { i, d ->
                    val root = d.absolutePath.substringBefore("/Android/")
                    statVolume(if (i == 0) "SD card" else "External ${i + 1}", File(root))?.let(::add)
                }
                statVolume("System", Environment.getRootDirectory())?.let(::add)
            }
            volumes = vols
            val base = Environment.getExternalStorageDirectory()
            folders = CommonFolders.mapNotNull { (name, icon) ->
                File(base, name).takeIf { it.isDirectory }?.let { FolderShortcut(name, it.absolutePath, icon) }
            }
            recent = runCatching {
                scanRecent(listOf("Download", "DCIM", "Documents", "Pictures", "Movies", "Music").map { File(base, it) })
            }.getOrDefault(emptyList())
        }
    }
    val openFolder: (String) -> Unit = { path ->
        onEvent(UiEvent.Open(state.focusedPane, path))
        onEvent(UiEvent.Navigate(Dest.FILES))
    }
    val tools = listOf(
        QuickTool("Terminal", Icons.Outlined.Terminal) { onEvent(UiEvent.OpenTerminal) },
        QuickTool("Wi-Fi transfer", Icons.Outlined.Wifi) { onEvent(UiEvent.OpenWifiTransfer) },
        QuickTool("Network", Icons.Outlined.Lan) { onEvent(UiEvent.OpenNetwork) },
        QuickTool("Notes vault", Icons.Outlined.Description) { onEvent(UiEvent.Navigate(Dest.NOTES)) },
        QuickTool("Analyzer", Icons.Outlined.PieChart) {
            onEvent(UiEvent.OpenTool(ToolRequest.Storage(Environment.getExternalStorageDirectory().path)))
        },
        QuickTool("Duplicates", Icons.Outlined.ContentCopy) {
            onEvent(UiEvent.OpenTool(ToolRequest.Duplicates(Environment.getExternalStorageDirectory().path)))
        },
        QuickTool("Recycle bin", Icons.Outlined.RestoreFromTrash) { onEvent(UiEvent.Navigate(Dest.TRASH)) },
        QuickTool("Bookmarks", Icons.Outlined.Bookmark) { onEvent(UiEvent.Navigate(Dest.BOOKMARKS)) },
    )
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 72.dp),
        modifier = Modifier.fillMaxSize().testTag("home"),
        contentPadding = PaddingValues(horizontal = Spacing.l, vertical = Spacing.s),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        val full: (androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> GridItemSpan) = { GridItemSpan(maxLineSpan) }

        item(key = "search", span = full) {
            SearchEntry { onEvent(UiEvent.Navigate(Dest.SEARCH)) }
        }

        item(key = "h-storage", span = full) { SectionTitle("Storage") }
        val vols = volumes
        if (vols == null) {
            item(key = "storage-loading", span = full) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        } else {
            item(key = "storage", span = full) {
                StorageSummary(
                    vols,
                    onOpen = { openFolder(it.path) },
                    // The system partition is read-only and mostly unreadable; analyzing it is not useful.
                    onAnalyze = { onEvent(UiEvent.OpenTool(ToolRequest.Storage(it.path))) },
                )
            }
        }

        if (folders.isNotEmpty()) {
            item(key = "h-folders", span = full) { SectionTitle("Jump to") }
            item(key = "folders", span = full) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    items(folders, key = { it.path }) { f ->
                        AssistChip(
                            onClick = { openFolder(f.path) },
                            label = { Text(f.label) },
                            leadingIcon = { Icon(f.icon, null, Modifier.size(AssistChipDefaults.IconSize)) },
                        )
                    }
                }
            }
        }

        item(key = "h-tools", span = full) { SectionTitle("Quick tools") }
        items(tools, key = { "tool-" + it.label }) { t -> ToolTile(t) }

        if (state.bookmarks.isNotEmpty()) {
            item(key = "h-bm", span = full) { SectionTitle("Bookmarks & shortcuts") }
            item(key = "bm", span = full) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    items(state.bookmarks.take(12), key = { "bm-" + it.id }) { b ->
                        AssistChip(
                            onClick = { onEvent(UiEvent.OpenBookmark(b.path)) },
                            label = { Text(b.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp)) },
                            leadingIcon = { Icon(Icons.Outlined.Bookmark, null, Modifier.size(AssistChipDefaults.IconSize)) },
                        )
                    }
                }
            }
        }

        item(key = "h-recent", span = full) { SectionTitle("Recent files") }
        val rec = recent
        when {
            rec == null -> item(key = "recent-loading", span = full) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            rec.isEmpty() -> item(key = "recent-empty", span = full) {
                Text(
                    "Nothing recent in Download, DCIM, Documents, Pictures, Movies or Music.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> items(rec, key = { "recent-" + it.path }, span = { full() }) { f -> RecentRow(f) { onEvent(UiEvent.OpenFile(f.path)) } }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = Spacing.m, bottom = Spacing.xs),
    )
}

/** Looks like a search field, opens the Search screen: the most common first step on a file manager. */
@Composable
private fun SearchEntry(onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = CircleShape,
        modifier = Modifier.fillMaxWidth().height(48.dp).clip(CircleShape).clickable(onClick = onClick).testTag("home-search"),
    ) {
        Row(Modifier.padding(horizontal = Spacing.l), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(Spacing.m))
            Text("Search files", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** All volumes in one card (one row each) instead of one big card per volume, so Home fits on one screen. */
@Composable
private fun StorageSummary(
    volumes: List<StorageVolumeInfo>,
    onOpen: (StorageVolumeInfo) -> Unit,
    onAnalyze: (StorageVolumeInfo) -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = Spacing.s)) {
            volumes.forEach { v ->
                StorageRow(v, onOpen = { onOpen(v) }, onAnalyze = if (v.label == "System") null else { { onAnalyze(v) } })
            }
        }
    }
}

@Composable
private fun StorageRow(v: StorageVolumeInfo, onOpen: () -> Unit, onAnalyze: (() -> Unit)?) {
    val progress by animateFloatAsState(v.usedFraction, tween(600), label = "storage")
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = Spacing.l, end = Spacing.xs, top = Spacing.s, bottom = Spacing.s)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.SdStorage, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(Spacing.s))
            Text(v.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${formatBytes(v.freeBytes)} free",
                style = MaterialTheme.typography.labelLarge,
                color = if (v.usedFraction > 0.9f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (onAnalyze != null) {
                IconButton(onClick = onAnalyze, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.PieChart, contentDescription = "Analyze ${v.label}", modifier = Modifier.size(20.dp))
                }
            } else {
                Spacer(Modifier.width(Spacing.m))
            }
        }
        Spacer(Modifier.height(Spacing.xs))
        Row(Modifier.padding(end = Spacing.m), verticalAlignment = Alignment.CenterVertically) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.weight(1f).height(6.dp),
                color = if (v.usedFraction > 0.9f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                drawStopIndicator = {},
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            "${formatBytes(v.usedBytes)} of ${formatBytes(v.totalBytes)} used",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Icon over a label, no card outline: four fit across a phone and the grid reads as a launcher, not a form. */
@Composable
private fun ToolTile(t: QuickTool) {
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = t.onClick).padding(vertical = Spacing.s, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(48.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(t.icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer) }
        Spacer(Modifier.height(Spacing.xs))
        Text(
            t.label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
            minLines = 2,
        )
    }
}

@Composable
private fun RecentRow(f: RecentFile, onClick: () -> Unit) {
    val cat = fileCategory(f.name, isDirectory = false)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryBadge(cat, selected = false)
        Spacer(Modifier.width(Spacing.m))
        Column(Modifier.weight(1f)) {
            Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${formatBytes(f.sizeBytes)} · ${f.path.substringBeforeLast('/').substringAfterLast('/')}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** Tonal rounded container with the file-type icon; a check mark when selected. */
@Composable
fun CategoryBadge(cat: FileCategory, selected: Boolean, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 40.dp) {
    val style = LocalIconStyle.current
    val scheme = MaterialTheme.colorScheme
    val container = when {
        selected -> scheme.primary
        style.colourful -> cat.colour.copy(alpha = 0.18f)
        cat == FileCategory.FOLDER -> scheme.primaryContainer
        else -> scheme.surfaceVariant
    }
    val tint = when {
        selected -> scheme.onPrimary
        style.colourful -> cat.colour
        cat == FileCategory.FOLDER -> scheme.onPrimaryContainer
        else -> scheme.onSurfaceVariant
    }
    Box(
        modifier.size(size).background(container, MaterialTheme.shapes.medium),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.animation.Crossfade(selected, label = "badge") { sel ->
            Icon(
                if (sel) Icons.Outlined.Check else cat.icon(style),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(size * 0.55f),
            )
        }
    }
}
