package ai.victorious.filemanager.core.bookmarks

import ai.victorious.filemanager.core.path.PathHelpers
import kotlinx.serialization.Serializable
import ai.victorious.filemanager.core.fs.FileIo
import ai.victorious.filemanager.core.fs.JsonStateFile
import ai.victorious.filemanager.core.fs.NioFileIo
import kotlinx.serialization.builtins.ListSerializer
import java.util.UUID

@Serializable
data class Bookmark(
    val id: String,
    val path: String,
    val label: String,
    val createdAtEpochMs: Long,
)

class BookmarkStore(
    private val storeFile: String,
    private val io: FileIo = NioFileIo(),
    private val ids: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val state = JsonStateFile(io, storeFile, ListSerializer(Bookmark.serializer()), emptyList(), clock)

    fun list(): List<Bookmark> = load().sortedBy { it.label.lowercase() }

    fun add(path: String, label: String? = null): Bookmark {
        val normalized = PathHelpers.normalize(path)
        val existing = load()
        existing.find { it.path == normalized }?.let { return it }
        val bookmark = Bookmark(
            id = ids(),
            path = normalized,
            label = label?.ifBlank { null } ?: PathHelpers.fileName(normalized),
            createdAtEpochMs = clock(),
        )
        save(existing + bookmark)
        return bookmark
    }

    fun remove(id: String) {
        save(load().filterNot { it.id == id })
    }

    fun isBookmarked(path: String): Boolean {
        val normalized = PathHelpers.normalize(path)
        return load().any { it.path == normalized }
    }

    private fun load(): List<Bookmark> = state.load()

    private fun save(entries: List<Bookmark>) = state.save(entries)
}
