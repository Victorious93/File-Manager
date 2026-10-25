package ai.victorious.filemanager.core.path

/**
 * Pure path helpers used by file ops, trash, and the UI. Separator is always `/`
 * in the public string API so JVM tests and Android share the same logic.
 */
object PathHelpers {
    const val SEPARATOR = '/'

    fun normalize(path: String): String {
        if (path.isBlank()) return SEPARATOR.toString()
        val absolute = path.replace('\\', SEPARATOR)
        val isAbsolute = absolute.startsWith(SEPARATOR)
        val parts = mutableListOf<String>()
        for (raw in absolute.split(SEPARATOR)) {
            when (raw) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts += raw
            }
        }
        val joined = parts.joinToString(SEPARATOR.toString())
        return if (isAbsolute) "$SEPARATOR$joined" else joined.ifEmpty { "." }
    }

    fun parentOf(path: String): String? {
        val n = normalize(path)
        if (n == SEPARATOR.toString() || n == ".") return null
        val idx = n.lastIndexOf(SEPARATOR)
        if (idx <= 0) {
            return if (n.startsWith(SEPARATOR)) SEPARATOR.toString() else null
        }
        return n.substring(0, idx).ifEmpty { SEPARATOR.toString() }
    }

    fun fileName(path: String): String {
        val n = normalize(path)
        if (n == SEPARATOR.toString()) return n
        return n.substringAfterLast(SEPARATOR)
    }

    fun join(parent: String, name: String): String {
        require(isValidFileName(name)) { "Invalid file name: $name" }
        val p = normalize(parent)
        return if (p == SEPARATOR.toString()) "$SEPARATOR$name" else "$p$SEPARATOR$name"
    }

    fun isValidFileName(name: String): Boolean {
        if (name.isBlank()) return false
        if (name == "." || name == "..") return false
        if (name.contains(SEPARATOR) || name.contains('\\') || name.contains('\u0000')) return false
        return true
    }

    fun splitBaseExt(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return name to ""
        return name.substring(0, dot) to name.substring(dot)
    }

    fun uniqueChildName(existingNames: Set<String>, desired: String): String {
        if (desired !in existingNames) return desired
        val (base, ext) = splitBaseExt(desired)
        var i = 1
        while (true) {
            val candidate = "$base ($i)$ext"
            if (candidate !in existingNames) return candidate
            i++
        }
    }

    /** True when [candidate] is the same path as [ancestor] or nested under it. */
    fun isSelfOrDescendant(candidate: String, ancestor: String): Boolean {
        val child = normalize(candidate).trimEnd(SEPARATOR)
        val parent = normalize(ancestor).trimEnd(SEPARATOR)
        if (parent == SEPARATOR.toString()) return child.startsWith(SEPARATOR)
        return child == parent || child.startsWith("$parent$SEPARATOR")
    }

    fun relativeTo(path: String, root: String): String {
        val child = normalize(path)
        val parent = normalize(root).trimEnd(SEPARATOR)
        if (child == parent) return "."
        val prefix = if (parent == SEPARATOR.toString()) SEPARATOR.toString() else "$parent$SEPARATOR"
        require(child.startsWith(prefix) || child == parent) { "$path is not under $root" }
        return child.removePrefix(prefix)
    }
}
