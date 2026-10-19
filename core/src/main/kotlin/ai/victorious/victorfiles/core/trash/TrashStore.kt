package ai.victorious.victorfiles.core.trash

import ai.victorious.victorfiles.core.fs.FileIo
import ai.victorious.victorfiles.core.path.PathHelpers
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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

class TrashStore(
    private val io: FileIo,
    private val trashRoot: String,
    private val ids: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val indexPath get() = PathHelpers.join(trashRoot, "index.json")
    private val itemsDir get() = PathHelpers.join(trashRoot, "items")

    init {
        ensureLayout()
    }

    fun list(): List<TrashEntry> = loadIndex().sortedByDescending { it.deletedAtEpochMs }

    fun moveToTrash(path: String): String {
        val normalized = PathHelpers.normalize(path)
        require(io.exists(normalized)) { "Missing: $normalized" }
        ensureLayout()
        val id = ids()
        val payload = PathHelpers.join(itemsDir, id)
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

    fun restoreAll(): List<String> = list().map { restore(it.id) }

    fun empty() {
        loadIndex().forEach { io.deleteRecursively(it.trashPayloadPath) }
        saveIndex(emptyList())
        if (io.exists(itemsDir)) {
            io.childNames(itemsDir).forEach { leftover ->
                io.deleteRecursively(PathHelpers.join(itemsDir, leftover))
            }
        }
    }

    fun permanentlyDelete(id: String) {
        val entries = loadIndex()
        val entry = entries.find { it.id == id } ?: return
        io.deleteRecursively(entry.trashPayloadPath)
        saveIndex(entries.filterNot { it.id == id })
    }

    private fun ensureLayout() {
        if (!io.exists(trashRoot)) io.mkdirs(trashRoot)
        if (!io.exists(itemsDir)) io.mkdirs(itemsDir)
        if (!io.exists(indexPath)) {
            io.writeText(indexPath, json.encodeToString(emptyList<TrashEntry>()))
        }
    }

    private fun loadIndex(): List<TrashEntry> {
        ensureLayout()
        val text = io.readText(indexPath)
        if (text.isBlank()) return emptyList()
        return json.decodeFromString(text)
    }

    private fun saveIndex(entries: List<TrashEntry>) {
        ensureLayout()
        io.writeText(indexPath, json.encodeToString(entries))
    }
}
