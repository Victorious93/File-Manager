package ai.victorious.filemanager.core.trash

import ai.victorious.filemanager.core.fs.FileIo
import ai.victorious.filemanager.core.fs.JsonStateFile
import ai.victorious.filemanager.core.path.PathHelpers
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.util.UUID

@Serializable
data class TrashEntry(
    val id: String,
    val originalPath: String,
    val trashPayloadPath: String,
    val originalName: String,
    val isDirectory: Boolean,
    val deletedAtEpochMs: Long,
)

/**
 * Soft-delete store. Every public method is synchronized: they read-modify-write one shared index file,
 * and the queue, the trash screen and refreshes all reach it from different IO threads.
 */
class TrashStore(
    private val io: FileIo,
    private val trashRoot: String,
    private val ids: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = { System.currentTimeMillis() },
    /**
     * Same-volume payload folder for [path] (e.g. `/storage/emulated/0/.victorious-trash/items`),
     * so trashing a big file is a rename instead of a copy into app storage. Null = [trashRoot].
     * The index always lives under [trashRoot].
     */
    private val payloadDirFor: (path: String) -> String? = { null },
) {
    private val index = JsonStateFile(io, PathHelpers.join(trashRoot, "index.json"), ListSerializer(TrashEntry.serializer()), emptyList(), clock)
    private val itemsDir get() = PathHelpers.join(trashRoot, "items")

    init {
        ensureLayout()
    }

    @Synchronized
    fun list(): List<TrashEntry> = loadIndex().sortedByDescending { it.deletedAtEpochMs }

    @Synchronized
    fun moveToTrash(path: String): String {
        val normalized = PathHelpers.normalize(path)
        require(io.exists(normalized)) { "Missing: $normalized" }
        ensureLayout()
        val id = ids()
        val dir = payloadDirFor(normalized)
            // Never put the trash inside the item being trashed (or trash the trash itself there).
            ?.takeIf { !PathHelpers.isSelfOrDescendant(it, normalized) && !PathHelpers.isSelfOrDescendant(normalized, it) }
            ?: itemsDir
        if (dir != itemsDir) ensureVolumeDir(dir)
        val payload = PathHelpers.join(dir, id)
        val entry = TrashEntry(
            id = id,
            originalPath = normalized,
            trashPayloadPath = payload,
            originalName = PathHelpers.fileName(normalized),
            isDirectory = io.isDirectory(normalized),
            deletedAtEpochMs = clock(),
        )
        // Index first, then move: once the original is gone it is already listed in the trash,
        // and a crash between the two steps leaves the file in place rather than orphaned.
        val before = loadIndex()
        saveIndex(before + entry)
        try {
            io.move(normalized, payload)
        } catch (t: Throwable) {
            saveIndex(before)
            throw t
        }
        return id
    }

    @Synchronized
    fun restore(id: String): String {
        val entries = loadIndex()
        val entry = entries.find { it.id == id } ?: throw IllegalArgumentException("Unknown trash id: $id")
        val parent = PathHelpers.parentOf(entry.originalPath)
            ?: throw IllegalStateException("Cannot restore to filesystem root")
        if (!io.exists(parent)) io.mkdirs(parent)
        val destName = PathHelpers.uniqueChildName(io.childNames(parent), entry.originalName)
        val dest = PathHelpers.join(parent, destName)
        io.move(entry.trashPayloadPath, dest)
        saveIndex(entries.filterNot { it.id == id })
        return dest
    }

    @Synchronized
    fun restoreAll(): List<String> = list().map { restore(it.id) }

    @Synchronized
    fun empty() {
        loadIndex().forEach { io.deleteRecursively(it.trashPayloadPath) }
        saveIndex(emptyList())
        if (io.exists(itemsDir)) {
            io.childNames(itemsDir).forEach { leftover ->
                io.deleteRecursively(PathHelpers.join(itemsDir, leftover))
            }
        }
    }

    @Synchronized
    fun permanentlyDelete(id: String) {
        val entries = loadIndex()
        val entry = entries.find { it.id == id } ?: return
        io.deleteRecursively(entry.trashPayloadPath)
        saveIndex(entries.filterNot { it.id == id })
    }

    private fun ensureVolumeDir(dir: String) {
        if (!io.exists(dir)) io.mkdirs(dir)
        // Keep trashed photos/videos out of gallery apps.
        val parent = PathHelpers.parentOf(dir) ?: dir
        val nomedia = PathHelpers.join(parent, ".nomedia")
        if (!io.exists(nomedia)) runCatching { io.writeText(nomedia, "") }
    }

    private fun ensureLayout() {
        if (!io.exists(trashRoot)) io.mkdirs(trashRoot)
        if (!io.exists(itemsDir)) io.mkdirs(itemsDir)
        if (!index.exists()) index.save(emptyList())
    }

    private fun loadIndex(): List<TrashEntry> {
        ensureLayout()
        return index.load()
    }

    private fun saveIndex(entries: List<TrashEntry>) {
        ensureLayout()
        index.save(entries)
    }
}
