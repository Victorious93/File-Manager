package ai.victorious.filemanager.core.share

/**
 * Pure logic for "Share to File Manager": turning an incoming item's suggested display name into a
 * safe, collision-free file name inside a chosen folder. The Android content resolution and byte
 * copying live in :app; the sanitising and de-duplication are here so they can be unit-tested.
 */
object ShareIntake {
    private val reserved = setOf("", ".", "..")

    /**
     * Sanitises a suggested name into a single safe path segment:
     * strips any directory parts, drops path separators and control characters, trims, and falls
     * back to [fallback] when nothing usable remains.
     */
    fun sanitize(suggested: String?, fallback: String = "shared"): String {
        if (suggested == null) return fallback
        // Keep only the last segment: a hostile provider could send "../../etc/x".
        val base = suggested.substringAfterLast('/').substringAfterLast('\\').trim()
        val cleaned = buildString {
            for (c in base) {
                when {
                    c.code < 0x20 -> {} // drop control chars
                    c == '/' || c == '\\' || c == '\u0000' -> {}
                    else -> append(c)
                }
            }
        }.trim().trimEnd('.').trim()
        return if (cleaned in reserved) fallback else cleaned.take(200)
    }

    /** Splits a file name into base and dotted-extension, e.g. "a.tar.gz" -> ("a", ".tar.gz"? no) */
    private fun splitExt(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        // No extension, or dotfile like ".bashrc" (dot at 0): treat whole thing as the stem.
        if (dot <= 0) return name to ""
        return name.substring(0, dot) to name.substring(dot)
    }

    /**
     * Returns a name not present in [existing], appending " (2)", " (3)", … before the extension on
     * collision. [existing] comparison is case-insensitive to be safe on case-insensitive volumes.
     */
    fun uniqueName(desired: String, existing: Set<String>): String {
        val taken = existing.map { it.lowercase() }.toHashSet()
        if (desired.lowercase() !in taken) return desired
        val (stem, ext) = splitExt(desired)
        var n = 2
        while (true) {
            val candidate = "$stem ($n)$ext"
            if (candidate.lowercase() !in taken) return candidate
            n++
        }
    }

    /** Convenience: sanitise then de-duplicate in one call. */
    fun resolve(suggested: String?, existing: Set<String>, fallback: String = "shared"): String =
        uniqueName(sanitize(suggested, fallback), existing)
}
