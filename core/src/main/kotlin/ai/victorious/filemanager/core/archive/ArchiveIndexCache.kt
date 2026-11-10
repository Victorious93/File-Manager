package ai.victorious.filemanager.core.archive

import java.io.File

/**
 * Small in-memory cache of archive listings, so reopening an archive, showing it in the second pane
 * or searching it after browsing does not read the whole central directory again.
 *
 * An entry is valid only while the archive's canonical path, length and modification time are
 * unchanged, so an edit made outside the app invalidates it by itself. Edits made through the app
 * call [invalidate] as well, because some file systems round modification times to 2 seconds and an
 * in-place edit can keep its length.
 *
 * Failures (wrong password, corrupt file) are never cached. Memory is bounded by both the number of
 * archives and the total number of cached entries.
 */
object ArchiveIndexCache {
    private const val MAX_ARCHIVES = 8
    private const val MAX_TOTAL_ITEMS = 200_000

    private data class Stamp(val length: Long, val modified: Long)

    private class Slot(val stamp: Stamp, val items: List<ArchiveItem>)

    // Access-ordered, so the eldest entry is the least recently used one.
    private val slots = LinkedHashMap<String, Slot>(16, 0.75f, true)
    private var totalItems = 0

    /** Lists [file], from the cache when it is unchanged since it was cached. */
    fun list(file: File): List<ArchiveItem> = list(file) { Archives.list(it) }

    /** Same as [list] with an injectable [loader]; the tests use it to count real reads. */
    internal fun list(file: File, loader: (File) -> List<ArchiveItem>): List<ArchiveItem> {
        val key = file.canonicalPath
        val stamp = Stamp(file.length(), file.lastModified())
        synchronized(this) {
            val hit = slots[key]
            if (hit != null && hit.stamp == stamp) return hit.items
        }
        // Parse outside the lock: listing a big archive can take seconds and must not block other panes.
        val items = loader(file).toList()
        // Re-read the stamp: if the file changed while we parsed, do not cache a possibly mixed result.
        val after = Stamp(file.length(), file.lastModified())
        if (after == stamp && items.size <= MAX_TOTAL_ITEMS) synchronized(this) { put(key, Slot(stamp, items)) }
        return items
    }

    private fun put(key: String, slot: Slot) {
        slots.remove(key)?.let { totalItems -= it.items.size }
        slots[key] = slot
        totalItems += slot.items.size
        val it = slots.entries.iterator()
        while ((slots.size > MAX_ARCHIVES || totalItems > MAX_TOTAL_ITEMS) && slots.size > 1 && it.hasNext()) {
            val eldest = it.next()
            if (eldest.key == key) continue
            totalItems -= eldest.value.items.size
            it.remove()
        }
    }

    @Synchronized
    fun invalidate(file: File) {
        slots.remove(file.canonicalPath)?.let { totalItems -= it.items.size }
    }

    @Synchronized
    fun clear() {
        slots.clear()
        totalItems = 0
    }

    @Synchronized
    internal fun cachedArchives(): Int = slots.size
}
