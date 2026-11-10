package ai.victorious.filemanager.ui

import ai.victorious.filemanager.core.media.library.ContinueItem
import ai.victorious.filemanager.core.media.library.LibraryGroup
import ai.victorious.filemanager.core.media.library.LibraryHome
import ai.victorious.filemanager.core.media.library.LibraryItem
import ai.victorious.filemanager.core.media.library.LibrarySort
import ai.victorious.filemanager.core.media.library.MediaLibraryIndex
import ai.victorious.filemanager.core.media.library.SavedLink
import ai.victorious.filemanager.core.media.library.YouTubeLinks
import ai.victorious.filemanager.core.media.pro.MediaItemRef
import ai.victorious.filemanager.core.media.pro.Playlist
import ai.victorious.filemanager.media.MediaLibraryRepo
import ai.victorious.filemanager.media.MediaProActivity
import ai.victorious.filemanager.media.MediaProEngine
import ai.victorious.filemanager.ui.theme.FleetDark
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class LibTab(val label: String) {
    HOME("Home"), SONGS("Songs"), VIDEOS("Videos"), ARTISTS("Artists"), ALBUMS("Albums"),
    FOLDERS("Folders"), PLAYLISTS("Playlists"), LINKS("YouTube links"),
}

internal fun fmtDuration(ms: Long): String {
    if (ms <= 0) return ""
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/**
 * Media library: a YouTube / YouTube Music-style home for the media already on this phone (continue
 * watching, recents, favourites, artists, albums, folders, playlists, search). It plays through the
 * existing Media Pro player. YouTube and YouTube Music links are only handed to the official apps.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun MediaLibraryScreen(onClose: () -> Unit, onEvent: (UiEvent) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var index by remember { mutableStateOf<MediaLibraryIndex?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(LibTab.HOME) }
    var query by remember { mutableStateOf("") }
    var openGroup by remember { mutableStateOf<LibraryGroup?>(null) }
    var addTo by remember { mutableStateOf<List<LibraryItem>?>(null) }
    val version by MediaLibraryRepo.version.collectAsState()

    fun rescan() = scope.launch {
        scanning = true
        index = withContext(Dispatchers.IO) { MediaLibraryIndex(MediaLibraryRepo.scan(context)) }
        scanning = false
    }
    LaunchedEffect(Unit) { MediaProEngine.init(context); rescan() }

    fun play(list: List<LibraryItem>, start: Int = 0, shuffle: Boolean = false) {
        if (list.isEmpty()) { onEvent(UiEvent.ShowMessage("Nothing to play")); return }
        val q = if (shuffle) list.shuffled() else list
        MediaProActivity.openQueue(context, q.map { it.path }, q.map { it.title }, if (shuffle) 0 else start)
    }

    ToolScaffold("Media library", onBack = { if (openGroup != null) openGroup = null else onClose() }, actions = {
        TextButton(onClick = { rescan() }, enabled = !scanning) { Text("Rescan") }
    }) { modifier ->
        Column(modifier) {
            OutlinedTextField(query, { query = it }, label = { Text("Search songs, videos, artists, albums") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))
            if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
            val idx = index
            val group = openGroup
            when {
                idx == null -> Text("Scanning your music and videos…", Modifier.padding(24.dp))
                query.isNotBlank() -> {
                    val hits = remember(idx, query) { idx.search(query) }
                    ItemList(hits, version, header = "${hits.size} result${if (hits.size == 1) "" else "s"}", onPlay = { i -> play(hits, i) }, onAdd = { addTo = listOf(it) })
                }
                group != null -> ItemList(group.items, version, header = "${group.title} · ${group.subtitle}",
                    onPlay = { i -> play(group.items, i) }, onAdd = { addTo = listOf(it) },
                    actions = {
                        Button(onClick = { play(group.items) }) { Text("Play all") }
                        OutlinedButton(onClick = { play(group.items, shuffle = true) }) { Text("Shuffle") }
                        OutlinedButton(onClick = { addTo = group.items }) { Text("Add to playlist") }
                    })
                else -> {
                    ScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 8.dp) {
                        LibTab.entries.forEach { t -> Tab(selected = tab == t, onClick = { tab = t }, text = { Text(t.label) }) }
                    }
                    when (tab) {
                        LibTab.HOME -> HomeTab(idx, version, ::play, onAdd = { addTo = listOf(it) })
                        LibTab.SONGS -> SortedList(idx, idx.songs, version, ::play) { addTo = listOf(it) }
                        LibTab.VIDEOS -> SortedList(idx, idx.videos, version, ::play) { addTo = listOf(it) }
                        LibTab.ARTISTS -> GroupList(remember(idx) { idx.artists() }) { openGroup = it }
                        LibTab.ALBUMS -> GroupList(remember(idx) { idx.albums() }) { openGroup = it }
                        LibTab.FOLDERS -> GroupList(remember(idx) { idx.folders() }) { openGroup = it }
                        LibTab.PLAYLISTS -> PlaylistsTab(onEvent)
                        LibTab.LINKS -> LinksTab(version, onEvent)
                    }
                }
            }
        }
    }
    addTo?.let { items -> AddToPlaylistDialog(items, onEvent) { addTo = null } }
}

@Composable
private fun HomeTab(idx: MediaLibraryIndex, version: Int, play: (List<LibraryItem>, Int, Boolean) -> Unit, onAdd: (LibraryItem) -> Unit) {
    val context = LocalContext.current
    val byPath = remember(idx) { idx.all.associateBy { it.path } }
    val home = remember(idx, version) {
        val hist = MediaLibraryRepo.history(context).events()
        @androidx.annotation.OptIn(UnstableApi::class)
        val resume = MediaProEngine.resumePoints(context)
        Triple(LibraryHome.continueItems(hist, resume, byPath), LibraryHome.recent(hist, byPath), LibraryHome.favorites(MediaLibraryRepo.favorites(context), byPath))
    }
    val (cont, recent, favs) = home
    LazyColumn(Modifier.fillMaxWidth()) {
        item {
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { play(idx.songs, 0, true) }) { Text("Shuffle all songs (${idx.songs.size})") }
                OutlinedButton(onClick = { play(idx.sorted(idx.videos, LibrarySort.RECENTLY_ADDED), 0, false) }) { Text("Play videos (${idx.videos.size})") }
            }
            if (idx.all.isEmpty()) Text(
                "No music or videos found. Give the app storage access in Settings, then tap Rescan.",
                Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium,
            )
        }
        section("Continue watching & listening", cont.isEmpty()) {
            items(cont, key = { "c" + it.item.path }) { c -> ContinueRow(c) { play(listOf(c.item), 0, false) } }
        }
        section("Recently played", recent.isEmpty()) {
            items(recent.take(15), key = { "r" + it.path }) { i -> ItemRow(i, version, onClick = { play(recent, recent.indexOf(i), false) }, onAdd = onAdd) }
        }
        section("Favourites", favs.isEmpty()) {
            items(favs, key = { "f" + it.path }) { i -> ItemRow(i, version, onClick = { play(favs, favs.indexOf(i), false) }, onAdd = onAdd) }
        }
        val added = LibraryHome.recentlyAdded(idx.all, 15)
        section("Recently added", added.isEmpty()) {
            items(added, key = { "a" + it.path }) { i -> ItemRow(i, version, onClick = { play(added, added.indexOf(i), false) }, onAdd = onAdd) }
        }
    }
}

private fun LazyListScope.section(title: String, empty: Boolean, content: LazyListScope.() -> Unit) {
    if (empty) return
    item(key = "h_$title") {
        Text(title, style = MaterialTheme.typography.titleMedium, color = FleetDark.Cyan, modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 4.dp))
    }
    content()
}

@Composable
private fun ContinueRow(c: ContinueItem, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text((if (c.item.isVideo) "▶ " else "♪ ") + c.item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
        LinearProgressIndicator(progress = { c.progress }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), color = FleetDark.Magenta)
        Text("${fmtDuration(c.positionMs)} of ${fmtDuration(c.item.durationMs).ifEmpty { "?" }} · ${fmtDuration(c.remainingMs)} left",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ItemRow(i: LibraryItem, version: Int, onClick: () -> Unit, onAdd: (LibraryItem) -> Unit) {
    val context = LocalContext.current
    var fav by remember(i.path, version) { mutableStateOf(MediaLibraryRepo.favorites(context).isFavorite(i.path)) }
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text((if (i.isVideo) "▶ " else "♪ ") + i.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            val sub = if (i.isVideo) listOf(i.folder.substringAfterLast('/'), fmtDuration(i.durationMs)) else listOf(i.displayArtist, i.album, fmtDuration(i.durationMs))
            Text(sub.filter { it.isNotBlank() }.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = { fav = MediaLibraryRepo.toggleFavorite(context, i.path) }) {
            Text(if (fav) "★" else "☆", color = if (fav) FleetDark.Magenta else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = { onAdd(i) }) { Text("+") }
    }
}

@Composable
private fun ItemList(items: List<LibraryItem>, version: Int, header: String, onPlay: (Int) -> Unit, onAdd: (LibraryItem) -> Unit, actions: (@Composable () -> Unit)? = null) {
    LazyColumn(Modifier.fillMaxWidth()) {
        item {
            Text(header, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(12.dp))
            actions?.let { a -> Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { a() } }
        }
        items(items.size, key = { items[it].path }) { n -> ItemRow(items[n], version, onClick = { onPlay(n) }, onAdd = onAdd) }
    }
}

@Composable
private fun SortedList(idx: MediaLibraryIndex, list: List<LibraryItem>, version: Int, play: (List<LibraryItem>, Int, Boolean) -> Unit, onAdd: (LibraryItem) -> Unit) {
    var sort by remember { mutableStateOf(LibrarySort.TITLE) }
    val sorted = remember(list, sort) { idx.sorted(list, sort) }
    Column {
        Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LibrarySort.entries.forEach { s ->
                FilterChip(sort == s, { sort = s }, { Text(s.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }) })
            }
        }
        ItemList(sorted, version, "${sorted.size} items", onPlay = { play(sorted, it, false) }, onAdd = onAdd, actions = {
            Button(onClick = { play(sorted, 0, false) }) { Text("Play all") }
            OutlinedButton(onClick = { play(sorted, 0, true) }) { Text("Shuffle") }
        })
    }
}

@Composable
private fun GroupList(groups: List<LibraryGroup>, onOpen: (LibraryGroup) -> Unit) {
    if (groups.isEmpty()) { Text("Nothing here yet.", Modifier.padding(24.dp)); return }
    LazyColumn(Modifier.fillMaxWidth()) {
        items(groups, key = { it.key }) { g ->
            Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onOpen(g) }.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(g.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(g.subtitle + (fmtDuration(g.totalDurationMs).takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun PlaylistsTab(onEvent: (UiEvent) -> Unit) {
    val context = LocalContext.current
    var lists by remember { mutableStateOf(MediaProEngine.playlists()) }
    if (lists.isEmpty()) Text("No playlists yet. Tap + next to any song or video to start one.", Modifier.padding(24.dp))
    LazyColumn(Modifier.fillMaxWidth()) {
        items(lists, key = { it.id }) { pl ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable {
                    MediaProActivity.openQueue(context, pl.items.map { it.uri }, pl.items.map { it.title })
                }) {
                    Text(pl.name, style = MaterialTheme.typography.titleSmall)
                    Text("${pl.items.size} item${if (pl.items.size == 1) "" else "s"} · ${fmtDuration(pl.items.sumOf { it.durationMs })}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = {
                    val q = pl.items.shuffled(); MediaProActivity.openQueue(context, q.map { it.uri }, q.map { it.title })
                }) { Text("Shuffle") }
                TextButton(onClick = { lists = lists - pl; MediaProEngine.savePlaylists(lists); onEvent(UiEvent.ShowMessage("Deleted ${pl.name}")) }) { Text("Delete") }
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun AddToPlaylistDialog(items: List<LibraryItem>, onEvent: (UiEvent) -> Unit, onDismiss: () -> Unit) {
    var lists by remember { mutableStateOf(MediaProEngine.playlists()) }
    var name by remember { mutableStateOf("") }
    fun refs() = items.map { MediaItemRef(UUID.randomUUID().toString(), it.path, it.title, it.durationMs) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add ${items.size} to playlist") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                lists.forEach { pl ->
                    Text(pl.name + " (${pl.items.size})", Modifier.fillMaxWidth().clickable {
                        val existing = pl.items.map { it.uri }.toSet()
                        lists = lists.map { if (it.id == pl.id) it.copy(items = it.items + refs().filter { r -> r.uri !in existing }) else it }
                        MediaProEngine.savePlaylists(lists); onEvent(UiEvent.ShowMessage("Added to ${pl.name}")); onDismiss()
                    }.padding(10.dp))
                }
                OutlinedTextField(name, { name = it }, label = { Text("New playlist name") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                lists = lists + Playlist(UUID.randomUUID().toString(), name.trim(), refs())
                MediaProEngine.savePlaylists(lists); onEvent(UiEvent.ShowMessage("Created ${name.trim()}")); onDismiss()
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** YouTube / YouTube Music links the user saved. They open in the official apps; nothing is downloaded or played here. */
@Composable
private fun LinksTab(version: Int, onEvent: (UiEvent) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    val links = remember(version) { MediaLibraryRepo.links(context) }
    val hasYt = remember { MediaLibraryRepo.isInstalled(context, YouTubeLinks.YOUTUBE_PACKAGE) }
    val hasMusic = remember { MediaLibraryRepo.isInstalled(context, YouTubeLinks.MUSIC_PACKAGE) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { s -> s.readBytes().takeIf { it.size <= 2_000_000 }?.decodeToString() }
                }.getOrNull()?.let { YouTubeLinks.extract(it) }.orEmpty()
            }
            found.forEach { MediaLibraryRepo.addLink(context, it.url, null) }
            onEvent(UiEvent.ShowMessage(if (found.isEmpty()) "No YouTube links in that file" else "Saved ${found.size} link(s)"))
        }
    }
    fun open(raw: String) {
        val link = YouTubeLinks.parse(raw) ?: run { onEvent(UiEvent.ShowMessage("Not a YouTube or YouTube Music link")); return }
        runCatching { MediaLibraryRepo.openOfficial(context, link) }
            .onSuccess { onEvent(UiEvent.ShowMessage("Opened in $it")) }
            .onFailure { onEvent(UiEvent.ShowMessage(it.message ?: "Cannot open")) }
    }
    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        item {
            Text(
                "Links open in the official YouTube or YouTube Music app (or your browser if they are not installed). " +
                    "This app does not download, stream or change YouTube content.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp),
            )
            Text("YouTube app: ${if (hasYt) "installed" else "not installed"} · YouTube Music: ${if (hasMusic) "installed" else "not installed"}",
                style = MaterialTheme.typography.labelMedium, color = FleetDark.Cyan)
            OutlinedTextField(url, { url = it }, label = { Text("Paste a youtube.com, youtu.be or music.youtube.com link") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(label, { label = it }, label = { Text("Label (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            val parsed = remember(url) { YouTubeLinks.parse(url) }
            Text(if (url.isBlank()) "" else parsed?.let { "${it.kind.label} · opens in ${if (it.isMusic) "YouTube Music" else "YouTube"}" } ?: "Not a YouTube link",
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                Button(enabled = parsed != null, onClick = { open(url) }) { Text("Open") }
                OutlinedButton(enabled = parsed != null, onClick = {
                    if (MediaLibraryRepo.addLink(context, url, label)) { url = ""; label = "" }
                }) { Text("Save") }
                OutlinedButton(onClick = { importer.launch(arrayOf("text/*", "application/octet-stream", "*/*")) }) { Text("Import from file") }
            }
            if (links.isNotEmpty()) Text("Saved links", style = MaterialTheme.typography.titleMedium, color = FleetDark.Cyan, modifier = Modifier.padding(top = 12.dp))
        }
        items(links, key = { it.url }) { l: SavedLink ->
            val kind = remember(l.url) { YouTubeLinks.parse(l.url)?.kind?.label.orEmpty() }
            Row(Modifier.fillMaxWidth().clickable { open(l.url) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(l.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("$kind · ${l.url}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { MediaLibraryRepo.removeLink(context, l.url) }) { Text("Remove") }
            }
        }
    }
}
