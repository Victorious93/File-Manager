package ai.victorious.filemanager.ui

import ai.victorious.filemanager.FileManagerApplication
import ai.victorious.filemanager.apk.ApkInfo
import ai.victorious.filemanager.apk.ApkRepository
import ai.victorious.filemanager.core.apk.ApkBuilder
import ai.victorious.filemanager.core.apk.ApkSigning
import ai.victorious.filemanager.core.apk.SignSchemes
import ai.victorious.filemanager.core.archive.Archives
import ai.victorious.filemanager.core.bookmarks.Bookmark
import ai.victorious.filemanager.core.fs.FileEntry
import ai.victorious.filemanager.core.fs.FileIo
import ai.victorious.filemanager.core.ops.OpKind
import ai.victorious.filemanager.core.rename.BatchRename
import ai.victorious.filemanager.core.rename.RenamePlan
import ai.victorious.filemanager.core.rename.RenameSpec
import ai.victorious.filemanager.core.search.SearchHit
import ai.victorious.filemanager.core.trash.TrashEntry
import ai.victorious.filemanager.core.archive.EncryptedZip
import ai.victorious.filemanager.core.apk.ApkBundles
import ai.victorious.filemanager.core.apk.Arsc
import ai.victorious.filemanager.core.apk.SmaliAssembler
import ai.victorious.filemanager.core.apk.SmaliAssemblyException
import ai.victorious.filemanager.core.viewer.FileKind
import ai.victorious.filemanager.core.viewer.FileKinds
import ai.victorious.filemanager.core.listing.ListingOptions
import ai.victorious.filemanager.core.props.FileProperties
import ai.victorious.filemanager.core.props.FolderStats
import ai.victorious.filemanager.core.props.HashAlgorithm
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

fun defaultMode(file: File): ViewerMode = when (FileKinds.detect(file)) {
    FileKind.TEXT -> ViewerMode.TEXT
    FileKind.IMAGE -> ViewerMode.IMAGE
    FileKind.VIDEO -> ViewerMode.VIDEO
    FileKind.AUDIO -> ViewerMode.AUDIO
    FileKind.ARCHIVE -> ViewerMode.ARCHIVE
    FileKind.DOCUMENT -> ViewerMode.PDF
    FileKind.FONT -> ViewerMode.FONT
    FileKind.BINARY -> ViewerMode.HEX
}

enum class Dest { FILES, SEARCH, BOOKMARKS, APPS, TOOLS, TRASH, SETTINGS }

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

/** Properties dialog; [stats] (folders) and [hashes] (files) fill in once computed. */
data class PropertiesState(
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val modifiedEpochMs: Long,
    val stats: FolderStats? = null,
    val hashes: Map<HashAlgorithm, String>? = null,
    val error: String? = null,
)

/** Batch-rename dialog: selected paths, the current rules, and the live preview. */
data class BatchRenameState(
    val targets: List<String>,
    val spec: RenameSpec = RenameSpec(),
    val plan: RenamePlan = RenamePlan(emptyList()),
)

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
    val searchStatus: String? = null,
    val bookmarks: List<Bookmark> = emptyList(),
    val trash: List<TrashEntry> = emptyList(),
    val renameTarget: String? = null,
    /** File chosen for Split…; the size dialog is shown while non-null. */
    val splitTarget: String? = null,
    val batchRename: BatchRenameState? = null,
    val listing: ListingOptions = ListingOptions(),
    val properties: PropertiesState? = null,
    val showAbout: Boolean = false,
    val nodeId: String = "",
    val dcaInstalled: Boolean = false,
    val busy: Boolean = false,
    val viewer: ViewerRequest? = null,
    val tool: ToolRequest? = null,
    val apkInfo: ApkInfo? = null,
    val signTarget: String? = null,
    /** APK surgery screen target (.apk, .dex or extracted folder). */
    val surgeryTarget: String? = null,
    /** Unsigned APK just produced by Rebuild; drives the "sign now?" reminder. */
    val rebuiltApk: String? = null,
    val verifyResult: Pair<String, ai.victorious.filemanager.core.apk.VerifyResult>? = null,
    val bundle: ai.victorious.filemanager.core.apk.ApkBundle? = null,
    val showTerminal: Boolean = false,
    val showNetwork: Boolean = false,
    val showWifiTransfer: Boolean = false,
)

/** Signing request from the Sign APK dialog. Passwords live only in memory for this call. */
data class SignOptions(
    val useDebugKey: Boolean,
    val keystorePath: String = "",
    val storePassword: String = "",
    val alias: String = "",
    val keyPassword: String = "",
    val v1: Boolean = true,
    val v2: Boolean = true,
    val v3: Boolean = true,
    val v4: Boolean = false,
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
    data class RequestSplit(val path: String) : UiEvent
    data object DismissSplit : UiEvent
    data class ConfirmSplit(val partSizeBytes: Long) : UiEvent
    /** Join the numbered parts (name.001, name.002, …) that [path] belongs to. */
    data class JoinParts(val path: String) : UiEvent
    data class UpdateBatchRename(val spec: RenameSpec) : UiEvent
    data object ConfirmBatchRename : UiEvent
    data object DismissBatchRename : UiEvent
    data class SetListing(val options: ListingOptions) : UiEvent
    data class SelectAll(val pane: Int) : UiEvent
    data class ShowProperties(val path: String) : UiEvent
    data object CloseProperties : UiEvent
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
    /** Open a file in a viewer; null mode picks the default from the file type. */
    data class OpenFile(val path: String, val mode: ViewerMode? = null) : UiEvent
    data object CloseViewer : UiEvent
    data class OpenTool(val request: ToolRequest) : UiEvent
    data object CloseTool : UiEvent
    class ZipSelectedEncrypted(val password: CharArray) : UiEvent
    data class ShowMessage(val message: String) : UiEvent
    data object ZipSelected : UiEvent
    data class ShowApkInfo(val path: String) : UiEvent
    data object DismissApkInfo : UiEvent
    data class ExtractApk(val path: String) : UiEvent
    data class RebuildApk(val dir: String) : UiEvent
    data class RequestSign(val path: String) : UiEvent
    data object DismissSign : UiEvent
    data class ConfirmSign(val path: String, val options: SignOptions) : UiEvent
    data class OpenSurgery(val path: String) : UiEvent
    data object CloseSurgery : UiEvent
    data object DismissRebuilt : UiEvent
    data class AssembleSmali(val smaliPath: String, val extractedDir: String) : UiEvent
    data class VerifyApk(val path: String) : UiEvent
    data object DismissVerify : UiEvent
    data class OpenBundle(val path: String) : UiEvent
    data object CloseBundle : UiEvent
    data class MergeBundle(val outName: String = "universal.apk") : UiEvent
    data class SaveArscEdits(val target: String, val edits: Map<Int, String>) : UiEvent
    data object OpenTerminal : UiEvent
    data object CloseTerminal : UiEvent
    data object OpenNetwork : UiEvent
    data object CloseNetwork : UiEvent
    data object OpenWifiTransfer : UiEvent
    data object CloseWifiTransfer : UiEvent
    /** Create an empty folder or file named [name] in the focused pane's folder. */
    data class Create(val name: String, val folder: Boolean) : UiEvent
}

class FileManagerViewModel(
    private val app: FileManagerApplication,
    /** Injectable for tests; defaults to the app's composite (local + remote) FileIo. */
    private val ioOverride: FileIo? = null,
) : ViewModel() {
    private val io: FileIo get() = ioOverride ?: app.io

    private val _ui = MutableStateFlow(initialState())
    val ui: StateFlow<UiState> = _ui

    private fun defaultRoot(): String {
        val ext = Environment.getExternalStorageDirectory()
        return if (ext.exists()) ext.absolutePath else app.filesDir.absolutePath
    }

    private fun initialState(): UiState {
        val root = defaultRoot()
        // Entries are filled by refreshAll() on Dispatchers.IO; nothing is listed on the main thread.
        val left = PaneState(listOf(emptyTab(root)), 0)
        val right = PaneState(listOf(emptyTab(root)), 0)
        return UiState(left = left, right = right, nodeId = app.nodeId)
    }

    fun refreshPermissions() {
        val granted = hasStorageAccess()
        val dca = isPackageInstalled("ai.droidcommand.app") || isPackageInstalled("ai.droidcommand.app.debug")
        _ui.update { it.copy(hasStorageAccess = granted, dcaInstalled = dca) }
    }

    /**
     * Re-lists every open tab plus bookmarks/trash. All IO (local, root/Shizuku shell, or a
     * remote backend) runs on Dispatchers.IO; the result is merged by tab id so tabs the user
     * opened/closed meanwhile are not clobbered.
     */
    /** Bumped per refresh; a refresh whose listing finishes after a newer one started is dropped. */
    private var refreshGeneration = 0L

    fun refreshAll() {
        // Every file operation, undo, restore and app resume ends in a refresh: mark the search
        // index stale so the next search re-walks instead of returning moved/deleted paths.
        app.index.invalidate()
        val generation = ++refreshGeneration
        viewModelScope.launch {
            val snapshot = _ui.value
            val listed = withContext(Dispatchers.IO) {
                val tabs = (snapshot.left.tabs + snapshot.right.tabs).associate { it.id to listing(it.path) }
                val selected = (snapshot.left.selected + snapshot.right.selected)
                    .filter { runCatching { io.exists(it) }.getOrDefault(false) }.toSet()
                Refreshed(
                    tabs = tabs,
                    existingSelection = selected,
                    bookmarks = runCatching { app.bookmarks.list() }.getOrDefault(emptyList()),
                    trash = runCatching { app.trash.list() }.getOrDefault(emptyList()),
                    canUndo = app.queue.snapshot().canUndo,
                )
            }
            // Refreshes overlap (each op, undo and resume starts one). An older listing that lands
            // after a newer one would roll the panes back, e.g. hide a file that was just created.
            if (generation != refreshGeneration) return@launch
            _ui.update { state ->
                fun merge(p: PaneState) = p.copy(
                    tabs = p.tabs.map { t ->
                        val l = listed.tabs[t.id]
                        if (l == null || l.path != t.path) t
                        else t.copy(title = titleFor(t.path), entries = l.entries, error = l.error)
                    },
                    selected = p.selected.filter { it in listed.existingSelection || it !in snapshot.left.selected + snapshot.right.selected }.toSet(),
                )
                state.copy(
                    left = merge(state.left),
                    right = merge(state.right),
                    bookmarks = listed.bookmarks,
                    trash = listed.trash,
                    canUndo = listed.canUndo,
                )
            }
        }
    }

    private class Listing(val path: String, val entries: List<FileEntry>, val error: String?)
    private class Refreshed(
        val tabs: Map<String, Listing>,
        val existingSelection: Set<String>,
        val bookmarks: List<Bookmark>,
        val trash: List<TrashEntry>,
        val canUndo: Boolean,
    )

    /** Must be called off the main thread. */
    private fun listing(path: String): Listing {
        val r = runCatching { io.list(path) }
        return Listing(path, r.getOrDefault(emptyList()), r.exceptionOrNull()?.message)
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
            is UiEvent.RequestSplit -> _ui.update { it.copy(splitTarget = event.path) }
            UiEvent.DismissSplit -> _ui.update { it.copy(splitTarget = null) }
            is UiEvent.ConfirmSplit -> {
                val target = _ui.value.splitTarget
                _ui.update { it.copy(splitTarget = null) }
                if (target != null) background("Split…", {
                    ai.victorious.filemanager.core.ops.FileSplit.split(File(target), event.partSizeBytes)
                }) { parts ->
                    refreshAll()
                    snack("Split into ${parts.size} parts (${parts.first().name} …)")
                }
            }
            is UiEvent.JoinParts -> background("Join…", {
                ai.victorious.filemanager.core.ops.FileSplit.join(File(event.path))
            }) { out ->
                refreshAll()
                snack("Joined into ${out.name}")
            }
            is UiEvent.UpdateBatchRename -> updateBatchRename(event.spec)
            UiEvent.ConfirmBatchRename -> confirmBatchRename()
            UiEvent.DismissBatchRename -> _ui.update { it.copy(batchRename = null) }
            is UiEvent.SetListing -> _ui.update { it.copy(listing = event.options) }
            is UiEvent.SelectAll -> updatePane(event.pane) { p ->
                p.copy(selected = _ui.value.listing.apply(p.active.entries).map { it.path }.toSet())
            }
            is UiEvent.ShowProperties -> showProperties(event.path)
            UiEvent.CloseProperties -> { propertiesJob?.cancel(); _ui.update { it.copy(properties = null) } }
            UiEvent.Undo -> undo()
            is UiEvent.Navigate -> _ui.update { it.copy(destination = event.dest) }
            is UiEvent.Search -> search(event.query)
            is UiEvent.OpenSearchHit -> {
                if (event.isDirectory) {
                    openPath(0, event.path)
                } else {
                    openPath(0, parentOrSelf(event.path), select = setOf(event.path))
                }
                _ui.update { it.copy(destination = Dest.FILES, focusedPane = 0) }
            }
            UiEvent.ToggleBookmark -> toggleBookmark()
            is UiEvent.OpenBookmark -> {
                openPath(_ui.value.focusedPane, event.path)
                _ui.update { it.copy(destination = Dest.FILES) }
            }
            is UiEvent.RemoveBookmark -> background("Remove bookmark", {
                app.bookmarks.remove(event.id); app.bookmarks.list()
            }) { list -> _ui.update { it.copy(bookmarks = list) } }
            is UiEvent.RestoreTrash -> background("Restore", { app.trash.restore(event.id) }) {
                refreshAll(); snack("Restored from trash")
            }
            is UiEvent.DeleteTrash -> background("Delete", { app.trash.permanentlyDelete(event.id) }) { refreshAll() }
            UiEvent.EmptyTrash -> background("Empty trash", { app.trash.empty() }) {
                refreshAll(); snack("Trash emptied")
            }
            UiEvent.DismissSnackbar -> _ui.update { it.copy(snackbar = null) }
            UiEvent.ToggleAbout -> _ui.update { it.copy(showAbout = !it.showAbout) }
            UiEvent.Refresh -> refreshAll()
            is UiEvent.OpenFile -> _ui.update { it.copy(viewer = ViewerRequest(event.path, event.mode ?: defaultMode(File(event.path)))) }
            UiEvent.CloseViewer -> { _ui.update { it.copy(viewer = null) }; refreshAll() }
            is UiEvent.ShowMessage -> snack(event.message)
            is UiEvent.OpenTool -> _ui.update { it.copy(tool = event.request) }
            UiEvent.CloseTool -> { _ui.update { it.copy(tool = null) }; refreshAll() }
            is UiEvent.ZipSelectedEncrypted -> zipSelected(event.password)
            UiEvent.ZipSelected -> zipSelected()
            is UiEvent.ShowApkInfo -> background("Reading APK…", { apkRepo.info(File(event.path)) }) { info ->
                _ui.update { it.copy(apkInfo = info) }
            }
            UiEvent.DismissApkInfo -> _ui.update { it.copy(apkInfo = null) }
            is UiEvent.ExtractApk -> background("Extracting…", {
                val apk = File(event.path)
                val dest = ApkBuilder.extractDirFor(apk)
                Archives.extract(apk, dest) to dest
            }) { (n, dest) -> refreshAll(); snack("Extracted $n file(s) to ${dest.name}") }
            is UiEvent.RebuildApk -> background("Rebuilding…", {
                val dir = File(event.dir)
                val out = ApkBuilder.rebuiltNameFor(dir)
                ApkBuilder.rebuild(dir, out) to out
            }) { (n, out) ->
                refreshAll()
                snack("Rebuilt ${out.name} ($n entries, unsigned — sign before installing)")
                _ui.update { it.copy(rebuiltApk = out.absolutePath) }
            }
            is UiEvent.RequestSign -> _ui.update { it.copy(signTarget = event.path, rebuiltApk = null) }
            is UiEvent.OpenSurgery -> _ui.update { it.copy(surgeryTarget = event.path) }
            UiEvent.CloseSurgery -> { _ui.update { it.copy(surgeryTarget = null) }; refreshAll() }
            is UiEvent.AssembleSmali -> background("Assembling smali…", {
                val smali = File(event.smaliPath).readText()
                val dir = File(event.extractedDir)
                require(dir.isDirectory) { "Assemble needs an extracted APK folder" }
                val type = Regex("""\.class\s+[^\n]*?(L[^;]+;)""").find(smali)?.groupValues?.get(1)
                    ?: error("No .class descriptor in smali")
                val dex = SmaliAssembler.dexContaining(dir, type) ?: File(dir, "classes.dex")
                require(dex.isFile) { "No DEX for $type — Extract APK first" }
                try {
                    SmaliAssembler.replaceInDexFile(dex, smali)
                } catch (e: SmaliAssemblyException) {
                    error(e.errors.joinToString("\n").ifBlank { e.message ?: "Assembly failed" })
                }
            }) { type -> snack("Assembled $type into DEX — Rebuild APK, then Sign") }
            is UiEvent.VerifyApk -> background("Verifying…", {
                ApkSigning.verify(File(event.path))
            }) { r -> _ui.update { it.copy(verifyResult = event.path to r) } }
            UiEvent.DismissVerify -> _ui.update { it.copy(verifyResult = null) }
            is UiEvent.OpenBundle -> background("Opening bundle…", {
                val f = File(event.path)
                val work = File(app.cacheDir, "bundles/" + f.nameWithoutExtension)
                work.deleteRecursively()
                ApkBundles.open(f, work)
            }) { b -> _ui.update { it.copy(bundle = b) } }
            UiEvent.CloseBundle -> {
                _ui.value.bundle?.extractDir?.deleteRecursively()
                _ui.update { it.copy(bundle = null) }
                refreshAll()
            }
            is UiEvent.MergeBundle -> {
                val b = _ui.value.bundle ?: return
                val destDir = File(pane(_ui.value, _ui.value.focusedPane).active.path)
                background("Merging splits…", {
                    val out = File(destDir, event.outName)
                    ApkBundles.merge(b.splits.map { it.file }, out) to out
                }) { (n, out) -> snack("Merged $n entries → ${out.name} (unsigned — Sign before install)") }
            }
            UiEvent.OpenTerminal -> _ui.update { it.copy(showTerminal = true) }
            UiEvent.OpenNetwork -> _ui.update { it.copy(showNetwork = true) }
            UiEvent.CloseNetwork -> _ui.update { it.copy(showNetwork = false) }
            UiEvent.OpenWifiTransfer -> _ui.update { it.copy(showWifiTransfer = true) }
            UiEvent.CloseWifiTransfer -> _ui.update { it.copy(showWifiTransfer = false) }
            is UiEvent.Create -> create(event.name.trim(), event.folder)
            UiEvent.CloseTerminal -> {
                app.terminals.closeAll()
                _ui.update { it.copy(showTerminal = false) }
            }
            is UiEvent.SaveArscEdits -> background("Writing resources.arsc…", {
                val target = File(event.target)
                val arsc = Arsc.loadFromTarget(target)
                val bytes = arsc.withEdits(event.edits)
                if (target.isDirectory) {
                    File(target, "resources.arsc").writeBytes(bytes)
                    "folder:${event.edits.size}"
                } else {
                    // Sealed APK: rewrite the entry in place (unsigned); Sign afterwards.
                    val out = File(target.parentFile, target.nameWithoutExtension + "_arsc.apk")
                    ai.victorious.filemanager.core.apk.ApkBuilder.rewrite(target, out, mapOf("resources.arsc" to bytes))
                    "apk:${event.edits.size}:${out.name}"
                }
            }) { tag ->
                val n = tag.substringAfter(':').substringBefore(':')
                snack(
                    if (tag.startsWith("apk:")) "Updated $n string(s) → ${tag.substringAfterLast(':')} (unsigned — Sign next)"
                    else "Updated $n string(s) in resources.arsc — Rebuild APK + Sign",
                )
                refreshAll()
            }
            UiEvent.DismissRebuilt -> _ui.update { it.copy(rebuiltApk = null) }
            UiEvent.DismissSign -> _ui.update { it.copy(signTarget = null) }
            is UiEvent.ConfirmSign -> {
                _ui.update { it.copy(signTarget = null) }
                background("Signing…", { sign(File(event.path), event.options) }) { out ->
                    refreshAll(); snack("Signed → ${out.name}")
                }
            }
        }
    }

    private fun emptyTab(path: String) = TabState(
        id = UUID.randomUUID().toString(),
        title = titleFor(path),
        path = path,
    )

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

    /**
     * Navigates the active tab of [pane] to [path]. The directory check and listing run on
     * Dispatchers.IO: for /remote/... paths they are network calls (NetworkOnMainThreadException
     * on the main thread), and big local folders would jank the UI.
     */
    private fun openPath(pane: Int, path: String, select: Set<String> = emptySet()) {
        val tabId = pane(_ui.value, pane).active.id
        viewModelScope.launch {
            val listed = withContext(Dispatchers.IO) {
                if (!runCatching { io.isDirectory(path) }.getOrDefault(false)) null else listing(path)
            } ?: return@launch
            updatePane(pane) { current ->
                val idx = current.tabs.indexOfFirst { it.id == tabId }.takeIf { it >= 0 } ?: current.activeIndex
                val tabs = current.tabs.toMutableList()
                tabs[idx] = tabs[idx].copy(path = path, title = titleFor(path), entries = listed.entries, error = listed.error)
                current.copy(tabs = tabs, selected = select)
            }
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
        val tab = emptyTab(target)
        updatePane(pane) { current ->
            val tabs = current.tabs + tab
            current.copy(tabs = tabs, activeIndex = tabs.lastIndex, selected = emptySet())
        }
        viewModelScope.launch {
            val listed = withContext(Dispatchers.IO) { listing(target) }
            updatePane(pane) { current ->
                current.copy(tabs = current.tabs.map { if (it.id == tab.id) it.copy(entries = listed.entries, error = listed.error) else it })
            }
        }
    }

    private fun create(name: String, folder: Boolean) {
        val dir = pane(_ui.value, _ui.value.focusedPane).active.path
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    app.queue.enqueue(if (folder) OpKind.CREATE_FOLDER else OpKind.CREATE_FILE, listOf(dir), newName = name)
                    app.queue.processAll().last()
                }
            }
            _ui.update { it.copy(busy = false) }
            result.fold(
                onSuccess = { refreshAll(); snack(it.message) },
                onFailure = { snack(it.message ?: "Create failed") },
            )
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
                        OpKind.RENAME, OpKind.BATCH_RENAME, OpKind.CREATE_FOLDER, OpKind.CREATE_FILE -> error("not a selection op")
                    }
                    app.queue.processAll().last()
                }
            }
            // Clear the selection in the same state update that ends `busy`: the sources are gone
            // (move/delete) or done with (copy), and a separate update left a window where a quick
            // next tap toggled a stale selection.
            _ui.update { s ->
                val cleared = if (result.isSuccess) {
                    if (s.focusedPane == 0) s.copy(left = s.left.copy(selected = emptySet()))
                    else s.copy(right = s.right.copy(selected = emptySet()))
                } else s
                cleared.copy(busy = false)
            }
            result.fold(
                onSuccess = {
                    refreshAll()
                    snack(it.message)
                },
                onFailure = { snack(it.message ?: "Operation failed") },
            )
        }
    }

    private val apkRepo by lazy { ApkRepository(app) }

    private fun sign(apk: File, o: SignOptions): File {
        val schemes = SignSchemes(o.v1, o.v2, o.v3, o.v4)
        val (key, alias) = if (o.useDebugKey) {
            apkRepo.debugKey() to ApkSigning.DEBUG_ALIAS
        } else {
            val ks = File(o.keystorePath.trim())
            require(ks.isFile) { "Keystore not found: ${o.keystorePath}" }
            val store = o.storePassword.toCharArray()
            val keyPass = o.keyPassword.takeIf { it.isNotEmpty() }?.toCharArray()
            ApkSigning.loadKey(ks, store, o.alias.ifBlank { null }, keyPass) to o.alias.ifBlank { "CERT" }
        }
        val out = ApkSigning.signedNameFor(apk)
        ApkSigning.sign(apk, out, key, alias, schemes)
        return out
    }

    /** Runs [work] on IO with the busy bar; reports failures as a snackbar prefixed by the op label. */
    private fun <T> background(label: String, work: () -> T, onSuccess: (T) -> Unit) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            val result = withContext(Dispatchers.IO) { runCatching(work) }
            _ui.update { it.copy(busy = false) }
            result.fold(onSuccess, { snack("${label.removeSuffix("…")} failed: ${it.message}") })
        }
    }

    private fun zipSelected(password: CharArray? = null) {
        val sources = selectedSources()
        if (sources.isEmpty()) {
            snack("Select files to zip")
            return
        }
        val dir = File(pane(_ui.value, _ui.value.focusedPane).active.path)
        val base = if (sources.size == 1) File(sources.first()).nameWithoutExtension.ifBlank { "archive" } else "archive"
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val out = Archives.uniqueZipName(dir, base)
                    val n = if (password != null) EncryptedZip.create(sources.map(::File), out, password)
                    else Archives.createZip(sources.map(::File), out)
                    password?.fill('\u0000')
                    n to out
                }
            }
            _ui.update { it.copy(busy = false) }
            result.fold(
                onSuccess = { (count, out) ->
                    refreshAll()
                    snack("Zipped $count file(s) into ${out.name}")
                },
                onFailure = { snack("Zip failed: ${it.message}") },
            )
        }
    }

    private fun requestRename() {
        val sources = selectedSources()
        when {
            sources.isEmpty() -> snack("Select an item to rename")
            sources.size == 1 -> _ui.update { it.copy(renameTarget = sources.first()) }
            else -> {
                val sorted = sources.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { File(it).name })
                _ui.update { it.copy(batchRename = BatchRenameState(sorted)) }
            }
        }
    }

    private var propertiesJob: kotlinx.coroutines.Job? = null

    private fun showProperties(path: String) {
        propertiesJob?.cancel()
        val file = File(path)
        _ui.update {
            it.copy(properties = PropertiesState(path, file.isDirectory, if (file.isFile) file.length() else 0L, file.lastModified()))
        }
        propertiesJob = viewModelScope.launch {
            val computed = withContext(Dispatchers.IO) {
                val self = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
                runCatching {
                    if (file.isDirectory) FileProperties.folderStats(file) { self?.isActive == false } to null
                    else null to FileProperties.checksums(file)
                }
            }
            _ui.update { st ->
                val cur = st.properties ?: return@update st
                if (cur.path != path) return@update st
                computed.fold(
                    onSuccess = { (stats, hashes) -> st.copy(properties = cur.copy(stats = stats, hashes = hashes)) },
                    onFailure = { st.copy(properties = cur.copy(error = it.message ?: "Cannot read")) },
                )
            }
        }
    }

    private fun updateBatchRename(spec: RenameSpec) {
        val targets = _ui.value.batchRename?.targets ?: return
        viewModelScope.launch {
            val plan = withContext(Dispatchers.IO) {
                BatchRename.plan(targets, spec) { dir -> runCatching { io.childNames(dir) }.getOrDefault(emptySet()) }
            }
            _ui.update { st -> st.batchRename?.let { st.copy(batchRename = it.copy(spec = spec, plan = plan)) } ?: st }
        }
    }

    private fun confirmBatchRename() {
        val plan = _ui.value.batchRename?.plan ?: return
        if (!plan.isApplicable) return
        viewModelScope.launch {
            _ui.update { it.copy(batchRename = null, busy = true) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    app.queue.enqueueBatchRename(plan.renames)
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
                onFailure = { snack(it.message ?: "Batch rename failed") },
            )
        }
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
            // The renamed path no longer exists; drop it from the selection with `busy` so a
            // following Copy/Move cannot pick up the stale old path.
            _ui.update { s ->
                fun drop(p: PaneState) = p.copy(selected = p.selected - target)
                if (result.isSuccess) s.copy(busy = false, left = drop(s.left), right = drop(s.right))
                else s.copy(busy = false)
            }
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

    private var searchJob: kotlinx.coroutines.Job? = null

    /**
     * Debounced, cancellable search over the in-memory [ai.victorious.filemanager.core.search.FileIndex].
     * The first query (or one after a file operation invalidated the index) rebuilds it on IO;
     * later keystrokes are a scan of in-memory strings. Typing cancels the previous query.
     */
    private fun search(query: String) {
        _ui.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        if (query.isBlank()) {
            _ui.update { it.copy(searchHits = emptyList(), searchStatus = null) }
            return
        }
        searchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(SEARCH_DEBOUNCE_MS)
            val root = defaultRoot()
            val index = app.index
            if (index.needsBuild(root)) {
                _ui.update { it.copy(searchStatus = "Indexing…") }
                val job = coroutineContext[kotlinx.coroutines.Job]
                val built = withContext(Dispatchers.IO) {
                    runCatching { index.build(root, isCancelled = { job?.isActive == false }) }
                }
                built.exceptionOrNull()?.let { if (it is InterruptedException) return@launch }
            }
            val hits = withContext(Dispatchers.Default) { index.search(query) }
            val snap = index.current()
            _ui.update {
                it.copy(
                    searchHits = hits,
                    searchStatus = snap?.let { s -> "${hits.size} result(s) · ${s.size} files indexed in ${s.buildMs} ms" },
                )
            }
        }
    }

    private fun toggleBookmark() {
        val path = pane(_ui.value, _ui.value.focusedPane).active.path
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                val existing = app.bookmarks.list().find { it.path == path }
                if (existing != null) app.bookmarks.remove(existing.id) else app.bookmarks.add(path)
                app.bookmarks.list()
            }
            _ui.update { it.copy(bookmarks = list) }
        }
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
        const val SEARCH_DEBOUNCE_MS = 200L
        fun factory(app: FileManagerApplication) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return FileManagerViewModel(app) as T
            }
        }
    }
}
