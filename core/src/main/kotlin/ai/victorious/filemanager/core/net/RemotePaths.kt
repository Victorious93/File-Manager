package ai.victorious.filemanager.core.net

import ai.victorious.filemanager.core.path.PathHelpers

/**
 * Virtual paths for remote connections live under `/remote/{connectionId}/…`
 * so existing [PathHelpers] and dual-pane navigation keep working.
 */
object RemotePaths {
    const val ROOT = "/remote"

    fun isRemote(path: String): Boolean {
        val n = PathHelpers.normalize(path)
        return n == ROOT || n.startsWith("$ROOT/")
    }

    fun connectionId(path: String): String? {
        val n = PathHelpers.normalize(path)
        if (!n.startsWith("$ROOT/")) return null
        val rest = n.removePrefix("$ROOT/")
        return rest.substringBefore('/')
    }

    fun remoteRelative(path: String): String {
        val n = PathHelpers.normalize(path)
        val id = connectionId(n) ?: return "/"
        val prefix = "$ROOT/$id"
        if (n == prefix) return "/"
        return "/" + n.removePrefix("$prefix/").trimStart('/')
    }

    fun toVirtual(connectionId: String, remotePath: String): String {
        require(connectionId.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid connection id" }
        val rel = PathHelpers.normalize(remotePath).trimStart('/')
        return if (rel.isEmpty() || rel == ".") "$ROOT/$connectionId" else "$ROOT/$connectionId/$rel"
    }

    fun connectionRoot(connectionId: String): String = "$ROOT/$connectionId"
}
