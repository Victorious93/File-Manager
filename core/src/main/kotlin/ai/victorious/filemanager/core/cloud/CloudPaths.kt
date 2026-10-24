package ai.victorious.filemanager.core.cloud

/** Helpers to translate the app's `/`-rooted remote paths to each provider's addressing. */
object CloudPaths {
    /** Split "/a/b/c" into ["a","b","c"]; root -> []. */
    fun segments(path: String): List<String> =
        path.trim('/').split('/').filter { it.isNotEmpty() }

    fun parent(path: String): String {
        val segs = segments(path)
        return if (segs.size <= 1) "/" else "/" + segs.dropLast(1).joinToString("/")
    }

    fun name(path: String): String = segments(path).lastOrNull() ?: ""

    /** Join a base dir and child name into a `/`-rooted path. */
    fun join(dir: String, child: String): String {
        val d = dir.trim('/')
        return if (d.isEmpty()) "/$child" else "/$d/$child"
    }

    /** S3 object key for a path + whether it is a "directory" (prefix). Root -> "". */
    fun s3Key(path: String, directory: Boolean): String {
        val key = segments(path).joinToString("/")
        return if (directory && key.isNotEmpty()) "$key/" else key
    }

    /** Dropbox path: "" for root, otherwise "/a/b". */
    fun dropboxPath(path: String): String {
        val segs = segments(path)
        return if (segs.isEmpty()) "" else "/" + segs.joinToString("/")
    }
}
