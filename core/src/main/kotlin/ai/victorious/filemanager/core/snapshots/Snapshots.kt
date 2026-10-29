package ai.victorious.filemanager.core.snapshots

import java.io.File
import java.security.MessageDigest

/**
 * File version snapshots: copies of a file kept in an app-private store so you can see,
 * diff and restore earlier versions. Identical content is stored once (by SHA-256).
 * Layout: <store>/<sha256(path) first 16 hex>/<epochMs>_<sha16>.snap plus `path.txt`.
 */
data class Snapshot(val sourcePath: String, val takenAtMs: Long, val sha: String, val sizeBytes: Long, val file: File)

class SnapshotStore(private val storeDir: File, private val keepPerFile: Int = 20, private val clock: () -> Long = System::currentTimeMillis) {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun sha(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun fileSha(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s -> val buf = ByteArray(64 * 1024); while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return hex(md.digest())
    }
    private fun dirFor(path: String) = File(storeDir, sha(File(path).absolutePath.toByteArray()).take(16))

    /** Take a snapshot. Returns null when the newest snapshot already has identical content. */
    @Synchronized fun take(source: File, maxBytes: Long = 50L * 1024 * 1024): Snapshot? {
        require(source.isFile) { "Not a file: ${source.path}" }
        require(source.length() <= maxBytes) { "File too large to snapshot (${source.length()} bytes > $maxBytes)" }
        val h = fileSha(source)
        if (list(source.absolutePath).firstOrNull()?.sha == h.take(16)) return null
        val dir = dirFor(source.absolutePath).apply { mkdirs() }
        File(dir, "path.txt").writeText(source.absolutePath)
        var t = clock()
        while (File(dir, "${t}_${h.take(16)}.snap").exists()) t++
        val out = File(dir, "${t}_${h.take(16)}.snap")
        val tmp = File(dir, out.name + ".tmp")
        source.copyTo(tmp, overwrite = true)
        check(tmp.renameTo(out)) { "Could not finalise snapshot" }
        prune(source.absolutePath)
        return Snapshot(source.absolutePath, t, h.take(16), out.length(), out)
    }

    /** Newest first. */
    fun list(path: String): List<Snapshot> {
        val dir = dirFor(path)
        return dir.listFiles { f -> f.name.endsWith(".snap") }.orEmpty().mapNotNull { f ->
            val parts = f.name.removeSuffix(".snap").split('_')
            val t = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            Snapshot(File(path).absolutePath, t, parts.getOrElse(1) { "" }, f.length(), f)
        }.sortedByDescending { it.takenAtMs }
    }

    /** Every file that has snapshots (path -> count). */
    fun trackedFiles(): Map<String, Int> = storeDir.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { d ->
        val p = File(d, "path.txt").takeIf { it.isFile }?.readText() ?: return@mapNotNull null
        p to d.listFiles { f -> f.name.endsWith(".snap") }.orEmpty().size
    }.toMap()

    /** Restore: snapshots the current content first (so restore is undoable), then copies back. */
    @Synchronized fun restore(snapshot: Snapshot): Snapshot? {
        val target = File(snapshot.sourcePath)
        target.parentFile?.mkdirs()
        // Stage the old content first: the safety snapshot below may prune [snapshot] itself.
        val tmp = File(target.parentFile, ".${target.name}.restore.tmp")
        snapshot.file.copyTo(tmp, overwrite = true)
        val safety = if (target.isFile) runCatching { take(target) }.getOrElse { tmp.delete(); throw it } else null
        if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
        return safety
    }

    @Synchronized fun delete(snapshot: Snapshot): Boolean = snapshot.file.delete()

    private fun prune(path: String) {
        list(path).drop(keepPerFile).forEach { it.file.delete() }
    }
}
