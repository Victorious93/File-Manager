package ai.victorious.victorfiles.core.hex

/**
 * Byte-pattern search over a [HexDocument] (including unsaved edits).
 *
 * Hex patterns are whitespace-separated or contiguous byte pairs; `??` matches any byte
 * ("48 65 ?? 6C", "4865??6C"). Text patterns are encoded with the given charset.
 */
class HexPattern private constructor(private val bytes: ByteArray, private val wild: BooleanArray) {
    val length: Int get() = bytes.size

    fun matchesAt(doc: HexDocument, offset: Int): Boolean {
        if (offset < 0 || offset + bytes.size > doc.size) return false
        for (i in bytes.indices) {
            if (!wild[i] && doc[offset + i] != bytes[i]) return false
        }
        return true
    }

    companion object {
        /** Returns null for empty input, odd digit counts, invalid digits, or an all-wildcard pattern. */
        fun fromHex(text: String): HexPattern? {
            val compact = text.filterNot { it.isWhitespace() }
            if (compact.isEmpty() || compact.length % 2 != 0) return null
            val n = compact.length / 2
            val bytes = ByteArray(n)
            val wild = BooleanArray(n)
            for (i in 0 until n) {
                val pair = compact.substring(i * 2, i * 2 + 2)
                if (pair == "??") {
                    wild[i] = true
                } else {
                    if (!pair.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
                    bytes[i] = pair.toInt(16).toByte()
                }
            }
            return if (wild.all { it }) null else HexPattern(bytes, wild)
        }

        fun fromText(text: String, charset: java.nio.charset.Charset = Charsets.UTF_8): HexPattern? {
            if (text.isEmpty()) return null
            val b = text.toByteArray(charset)
            return HexPattern(b, BooleanArray(b.size))
        }
    }
}

object HexSearch {
    /**
     * First match at or after [from] (forward) or at or before [from] (backward). A [from] past the
     * end (forward) or before the start (backward) has no matches on that side, so with [wrap] the
     * search continues from the other end, and without it returns -1. Returns the offset or -1.
     */
    fun find(doc: HexDocument, pattern: HexPattern, from: Int, forward: Boolean = true, wrap: Boolean = true): Int {
        val last = doc.size - pattern.length
        if (last < 0) return -1
        if (forward) {
            val s = from.coerceAtLeast(0)
            for (o in s..last) if (pattern.matchesAt(doc, o)) return o
            if (wrap) for (o in 0 until minOf(s, last + 1)) if (pattern.matchesAt(doc, o)) return o
        } else {
            val s = from.coerceAtMost(last)
            for (o in s downTo 0) if (pattern.matchesAt(doc, o)) return o
            if (wrap) for (o in last downTo maxOf(s + 1, 0)) if (pattern.matchesAt(doc, o)) return o
        }
        return -1
    }

    /** All match offsets (overlapping allowed), capped at [limit] so a 1-byte pattern can't flood the UI. */
    fun findAll(doc: HexDocument, pattern: HexPattern, limit: Int = 10_000): List<Int> {
        require(limit > 0) { "limit must be positive" }
        val out = ArrayList<Int>()
        val last = doc.size - pattern.length
        var o = 0
        while (o <= last && out.size < limit) {
            if (pattern.matchesAt(doc, o)) out.add(o)
            o++
        }
        return out
    }
}
