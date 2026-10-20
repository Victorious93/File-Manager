package ai.victorious.filemanager.core.media

/**
 * Pure (JVM-testable) parts of the gallery thumbnail cache: how a cache key is derived from a
 * file's identity, and a size-bounded LRU budget that decides what to evict. The actual bitmap
 * decoding and the Android memory/disk caches live in :app; only this logic is unit-tested.
 */

/** Identity of a thumbnail request. Two files with the same path, size and mtime share a key. */
data class ThumbnailKey(
    val path: String,
    val size: Long,
    val lastModified: Long,
    val targetPx: Int,
) {
    /**
     * Stable, filesystem-safe cache token. Includes size + mtime so that replacing a file in place
     * (same path) produces a new token and never serves a stale thumbnail. [targetPx] is included
     * so different grid densities do not collide.
     */
    fun token(): String {
        // FNV-1a 64-bit over the identity string: short, stable, no collisions in practice here,
        // and no dependency on Android's Base64 so it is testable on the JVM.
        val s = "$path|$size|$lastModified|$targetPx"
        var hash = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
        for (ch in s) {
            hash = hash xor (ch.code.toLong() and 0xff)
            hash *= 0x100000001b3L
        }
        val hex = java.lang.Long.toHexString(hash)
        return "t${targetPx}_$hex"
    }
}

/**
 * Tracks approximate memory use of cached thumbnails and reports what to evict, oldest-use first,
 * to stay within [maxBytes]. This mirrors an LruCache's policy but is decoupled from Android so the
 * eviction order and budget math can be verified without an emulator.
 */
class LruBudget(val maxBytes: Long) {
    init { require(maxBytes > 0) { "maxBytes must be > 0" } }

    // Access-ordered: last entry is most-recently used.
    private val entries = LinkedHashMap<String, Long>(16, 0.75f, true)
    private var total = 0L

    val sizeBytes: Long get() = total
    val count: Int get() = entries.size

    fun contains(key: String): Boolean = entries.containsKey(key)

    /** Records an access (most-recently used) without changing size. No-op if absent. */
    fun touch(key: String) { entries[key] }

    /**
     * Inserts/updates [key] with [bytes] and returns the keys evicted (in eviction order) to fit the
     * budget. A single item larger than the budget is kept (callers should simply not cache it), so
     * this never evicts everything for one oversized put beyond what the loop requires.
     */
    fun put(key: String, bytes: Long): List<String> {
        require(bytes >= 0) { "bytes must be >= 0" }
        val prev = entries.put(key, bytes)
        total += bytes - (prev ?: 0L)
        val evicted = ArrayList<String>()
        val it = entries.entries.iterator()
        while (total > maxBytes && entries.size > 1 && it.hasNext()) {
            val e = it.next()
            if (e.key == key) continue // never evict the just-inserted entry first
            it.remove()
            total -= e.value
            evicted.add(e.key)
        }
        return evicted
    }

    fun remove(key: String): Boolean {
        val v = entries.remove(key) ?: return false
        total -= v
        return true
    }

    fun clear() { entries.clear(); total = 0 }

    /** Current least-recently-used order (eviction order), for tests/inspection. */
    fun lruOrder(): List<String> = entries.keys.toList()
}
