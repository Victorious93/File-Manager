package ai.victorious.filemanager.ui

import ai.victorious.filemanager.FileManagerApplication
import ai.victorious.filemanager.core.bookmarks.Bookmark
import ai.victorious.filemanager.core.fs.FileEntry
import ai.victorious.filemanager.core.fs.FileIo
import ai.victorious.filemanager.core.ops.OpKind
import ai.victorious.filemanager.core.search.SearchHit
import ai.victorious.filemanager.core.trash.TrashEntry
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Dest { FILES, SEARCH, BOOKMARKS, TRASH }

data class TabState(
    val id: String,
    val title: String,
    val path: String,
    val entries: List<FileEntry> = emptyList(),
    val error: String? = null,
)

data class PaneState(
    val tabs: List<TabState>,
    val activeIndex: Int,
    val selected: Set<String> = emptySet(),
) {
    val active: TabState get() = tabs[activeIndex.coerceIn(tabs.indices)]
}

data class UiState(
    val destination: Dest = Dest.FILES,
    val focusedPane: Int = 0,
    val left: PaneState,
    val right: PaneState,
    val hasStorageAccess: Boolean = false,
    val snackbar: String? = null,
    val canUndo: Boolean = false,
    val searchQuery: String = "",
    val searchHits: List<SearchHit> = emptyList(),
    val bookmarks: List<Bookmark> = emptyList(),
    val trash: List<TrashEntry> = emptyList(),
    val renameTarget: String? = null,
    val showAbout: Boolean = false,
    val nodeId: String = "",
    val dcaInstalled: Boolean = false,
    val busy: Boolean = false,
)

sealed interface UiEvent {
    data class Open(val pane: Int, val path: String) : UiEvent
    data class Select(val pane: Int, val path: String, val additive: Boolean) : UiEvent
    data class ClearSelection(val pane: Int) : UiEvent
    data class FocusPane(val pane: Int) : UiEvent
    data class SwitchTab(val pane: Int, val index: Int) : UiEvent
    data class CloseTab(val pane: Int, val index: Int) : UiEvent
    data class NewTab(val pane: Int, val path: String? = null) : UiEvent
    data class GoUp(val pane: Int) : UiEvent
    data object CopyToOther : UiEvent
    data object MoveToOther : UiEvent
    data object DeleteSelected : UiEvent
    data object RequestRename : UiEvent
    data class ConfirmRename(val newName: String) : UiEvent
    data object DismissRename : UiEvent
    data object Undo : UiEvent
    data class Navigate(val dest: Dest) : UiEvent
    data class Search(val query: String) : UiEvent
    data class OpenSearchHit(val path: String, val isDirectory: Boolean) : UiEvent
    data object ToggleBookmark : UiEvent
    data class OpenBookmark(val path: String) : UiEvent
    data class RemoveBookmark(val id: String) : UiEvent
    data class RestoreTrash(val id: String) : UiEvent
    data class DeleteTrash(val id: String) : UiEvent
    data object EmptyTrash : UiEvent
    data object DismissSnackbar : UiEvent
    data object ToggleAbout : UiEvent
    data object Refresh : UiEvent
}

class FileManagerViewModel(
    private val app: FileManagerApplication,
) : ViewModel() {
    private val io: FileIo get() = app.io

    private val _ui = MutableStateFlow(initialState())
    val ui: StateFlow<UiState> = _ui

    private fun defaultRoot(): String {
        val ext = Environment.getExternalStorageDirectory()
        return if (ext.exists()) ext.absolutePath else app.filesDir.absolutePath
    }

    private fun initialState(): UiState {
        val root = defaultRoot()
        val left = PaneState(listOf(tabFor(root)), 0)
        val right = PaneState(listOf(tabFor(root)), 0)
        return UiState(left = left, right = right, nodeId = app.nodeId)
    }

    fun refreshPermissions() {
        val granted = hasStorageAccess()
        val dca = isPackageInstalled("ai.droidcommand.app") || isPackageInstalled("ai.droidcommand.app.debug")
        _ui.update { it.copy(hasStorageAccess = granted, dcaInstalled = dca) }
    }

    fun refreshAll() {
        _ui.update { state ->
            state.copy(
                left = reloadPane(state.left),
                right = reloadPane(state.right),
                bookmarks = app.bookmarks.list(),
                trash = app.trash.list(),
                canUndo = app.queue.snapshot().canUndo,
            )
        }
    }

    fun onEvent(event: UiEvent) {
        when (event) {
            is UiEvent.Open -> openPath(event.pane, event.path)
            is UiEvent.Select -> select(event.pane, event.path, event.additive)
            is UiEvent.ClearSelection -> updatePane(event.pane) { it.copy(selected = emptySet()) }
            is UiEvent.FocusPane -> _ui.update { it.copy(focusedPane = event.pane) }
            is UiEvent.SwitchTab -> switchTab(event.pane, event.index)
            is UiEvent.CloseTab -> closeTab(event.pane, event.index)
            is UiEvent.NewTab -> newTab(event.pane, event.path)
            is UiEvent.GoUp -> goUp(event.pane)
            UiEvent.CopyToOther -> runOp(OpKind.COPY)
            UiEvent.MoveToOther -> runOp(OpKind.MOVE)
            UiEvent.DeleteSelected -> runOp(OpKind.DELETE)
            UiEvent.RequestRename -> requestRename()
            is UiEvent.ConfirmRename -> confirmRename(event.newName)
            UiEvent.DismissRename -> _ui.update { it.copy(renameTarget = null) }
            UiEvent.Undo -> undo()
            is UiEvent.Navigate -> _ui.update { it.copy(destination = event.dest) }
            is UiEvent.Search -> search(event.query)
            is UiEvent.OpenSearchHit -> {
                if (event.isDirectory) {
                    openPath(0, event.path)
                    _ui.update { it.copy(destination = Dest.FILES, focusedPane = 0) }
                } else {
                    openPath(0, parentOrSelf(event.path))
                    updatePane(0) { it.copy(selected = setOf(event.path)) }
                    _ui.update { it.copy(destination = Dest.FILES, focusedPane = 0) }
                }
            }
            UiEvent.ToggleBookmark -> toggleBookmark()
            is UiEvent.OpenBookmark -> {
                openPath(_ui.value.focusedPane, event.path)
                _ui.update { it.copy(destination = Dest.FILES) }
            }
            is UiEvent.RemoveBookmark -> {
                app.bookmarks.remove(event.id)
                _ui.update { it.copy(bookmarks = app.bookmarks.list()) }
            }
            is UiEvent.RestoreTrash -> {
                runCatching { app.trash.restore(event.id) }
                refreshAll()
                snack("Restored from trash")
            }
            is UiEvent.DeleteTrash -> {
                app.trash.permanentlyDelete(event.id)
                refreshAll()
            }
            UiEvent.EmptyTrash -> {
                app.trash.empty()
                refreshAll()
                snack("Trash emptied")
            }
            UiEvent.DismissSnackbar -> _ui.update { it.copy(snackbar = null) }
            UiEvent.ToggleAbout -> _ui.update { it.copy(showAbout = !it.showAbout) }
            UiEvent.Refresh -> refreshAll()
        }
    }

    private fun tabFor(path: String): TabState {
        val listed = runCatching { io.list(path) }
        return TabState(
            id = UUID.randomUUID().toString(),
            title = titleFor(path),
            path = path,
            entries = listed.getOrDefault(emptyList()),
            error = listed.exceptionOrNull()?.message,
        )
    }

    private fun reloadPane(pane: PaneState): PaneState {
        val tabs = pane.tabs.map { tab ->
            val listed = runCatching { io.list(tab.path) }
            tab.copy(
                title = titleFor(tab.path),
                entries = listed.getOrDefault(emptyList()),
                error = listed.exceptionOrNull()?.message,
            )
        }
        return pane.copy(tabs = tabs, selected = pane.selected.filter { io.exists(it) }.toSet())
    }

    private fun titleFor(path: String): String {
        val name = File(path).name
        return name.ifBlank { path }
    }

    private fun pane(state: UiState, index: Int) = if (index == 0) state.left else state.right

    private fun updatePane(index: Int, transform: (PaneState) -> PaneState) {
        _ui.update { state ->
            if (index == 0) state.copy(left = transform(state.left), focusedPane = index)
            else state.copy(right = transform(state.right), focusedPane = index)
        }
    }

    private fun openPath(pane: Int, path: String) {
        if (!io.isDirectory(path)) return
        updatePane(pane) { current ->
            val tabs = current.tabs.toMutableList()
            val active = current.active.copy(
                path = path,
                title = titleFor(path),
                entries = runCatching { io.list(path) }.getOrDefault(emptyList()),
                error = null,
            ).let { reloaded ->
                val listed = runCatching { io.list(path) }
                reloaded.copy(entries = listed.getOrDefault(emptyList()), error = listed.exceptionOrNull()?.message)
            }
            tabs[current.activeIndex] = active
            current.copy(tabs = tabs, selected = emptySet())
        }
    }

    private fun select(pane: Int, path: String, additive: Boolean) {
        updatePane(pane) { current ->
            val next = if (!additive) {
                if (current.selected == setOf(path)) emptySet() else setOf(path)
            } else {
                if (path in current.selected) current.selected - path else current.selected + path
            }
            current.copy(selected = next)
        }
    }

    private fun switchTab(pane: Int, index: Int) {
        updatePane(pane) { it.copy(activeIndex = index.coerceIn(it.tabs.indices), selected = emptySet()) }
    }

    private fun closeTab(pane: Int, index: Int) {
        updatePane(pane) { current ->
            if (current.tabs.size == 1) current
            else {
                val tabs = current.tabs.toMutableList().also { it.removeAt(index) }
                val active = current.activeIndex.coerceAtMost(tabs.lastIndex)
                current.copy(tabs = tabs, activeIndex = active, selected = emptySet())
            }
        }
    }

    private fun newTab(pane: Int, path: String?) {
        val target = path ?: pane(_ui.value, pane).active.path
        updatePane(pane) { current ->
            val tabs = current.tabs + tabFor(target)
            current.copy(tabs = tabs, activeIndex = tabs.lastIndex, selected = emptySet())
        }
    }

    private fun goUp(pane: Int) {
        val current = pane(_ui.value, pane).active.path
        val parent = File(current).parent ?: return
        openPath(pane, parent)
    }

    private fun selectedSources(): List<String> {
        val state = _ui.value
        val pane = pane(state, state.focusedPane)
        return pane.selected.toList().ifEmpty { emptyList() }
    }

    private fun otherPaneDir(): String {
        val state = _ui.value
        val other = if (state.focusedPane == 0) 1 else 0
        return pane(state, other).active.path
    }

    private fun runOp(kind: OpKind) {
        val sources = selectedSources()
        if (sources.isEmpty()) {
            snack("Select files first")
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    when (kind) {
                        OpKind.COPY, OpKind.MOVE -> app.queue.enqueue(kind, sources, otherPaneDir())
                        OpKind.DELETE -> app.queue.enqueue(kind, sources)
                        OpKind.RENAME -> error("use confirmRename")
                    }
                    app.queue.processAll().last()
                }
            }
            _ui.update { it.copy(busy = false) }
            result.fold(
                onSuccess = {
                    refreshAll()
                    updatePane(_ui.value.focusedPane) { pane -> pane.copy(selected = emptySet()) }
                    snack(it.message)
                },
                onFailure = { snack(it.message ?: "Operation failed") },
            )
        }
    }

    private fun requestRename() {
        val sources = selectedSources()
        if (sources.size != 1) {
            snack("Select a single item to rename")
            return
        }
        _ui.update { it.copy(renameTarget = sources.first()) }
    }

    private fun confirmRename(newName: String) {
        val target = _ui.value.renameTarget ?: return
        viewModelScope.launch {
            _ui.update { it.copy(renameTarget = null, busy = true) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    app.queue.enqueue(OpKind.RENAME, listOf(target), newName = newName)
                    app.queue.processAll().last()
                }
            }
            _ui.update { it.copy(busy = false) }
            result.fold(
                onSuccess = {
                    refreshAll()
                    snack(it.message)
                },
                onFailure = { snack(it.message ?: "Rename failed") },
            )
        }
    }

    private fun undo() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { app.queue.undoLast() }
            refreshAll()
            snack(result.message)
        }
    }

    private fun search(query: String) {
        _ui.update { it.copy(searchQuery = query) }
        viewModelScope.launch {
            val hits = withContext(Dispatchers.IO) {
                app.search.search(defaultRoot(), query)
            }
            _ui.update { it.copy(searchHits = hits) }
        }
    }

    private fun toggleBookmark() {
        val path = pane(_ui.value, _ui.value.focusedPane).active.path
        val existing = app.bookmarks.list().find { it.path == path }
        if (existing != null) app.bookmarks.remove(existing.id) else app.bookmarks.add(path)
        _ui.update { it.copy(bookmarks = app.bookmarks.list()) }
    }

    private fun snack(message: String) {
        _ui.update { it.copy(snackbar = message, canUndo = app.queue.snapshot().canUndo) }
    }

    private fun parentOrSelf(path: String): String = File(path).parent ?: path

    private fun hasStorageAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(app, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private fun isPackageInstalled(pkg: String): Boolean {
        return runCatching {
            app.packageManager.getPackageInfo(pkg, 0)
            true
        }.getOrDefault(false)
    }

    companion object {
        fun factory(app: FileManagerApplication) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return FileManagerViewModel(app) as T
            }
        }
    }
}
