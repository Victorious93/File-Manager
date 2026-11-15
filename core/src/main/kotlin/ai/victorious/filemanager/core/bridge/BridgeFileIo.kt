package ai.victorious.filemanager.core.bridge

import ai.victorious.filemanager.core.fs.FileEntry
import ai.victorious.filemanager.core.fs.FileIo
import ai.victorious.filemanager.core.fs.FileIoException

/** One reply from Victorious Bridge's controlled API (`files.*` operations). */
data class BridgeReply(
    val ok: Boolean,
    /** Bridge error category (e.g. PATH_OUT_OF_SCOPE, DENIED, REVOKED, EXPIRED, CANCELLED, NOT_FOUND) or null when ok. */
    val errorCode: String? = null,
    val message: String? = null,
    val data: Map<String, Any?> = emptyMap(),
)

/**
 * Platform seam to Victorious Bridge. The Android app implements it over the Bridge SDK
 * (`VictoriousBridge.execute(operation, args)`); core stays a plain JVM module with no Android dependency and no
 * dependency on the ShizukuV repository. Contract: ShizukuV `docs/contract/bridge-contract-v1.json`.
 */
interface BridgeFileGateway {
    fun call(operation: String, args: Map<String, Any>): BridgeReply

    /** True once the user cancelled the running file operation. Polled between chunks. */
    fun isCancelled(): Boolean = false
}

/** Failure with the Bridge's own category, so the UI can say "approval revoked" rather than "I/O error". */
class BridgeFileException(val code: String, message: String) : FileIoException("$code: $message") {
    val isAccessLoss: Boolean get() = code in setOf("DENIED", "REVOKED", "EXPIRED", "IDENTITY_MISMATCH", "NOT_ATTACHED")
    val isOutOfScope: Boolean get() = code == "PATH_OUT_OF_SCOPE"
    val isCancelled: Boolean get() = code == "CANCELLED"
}

/**
 * [FileIo] that performs every operation through Victorious Bridge, where the *service* enforces the approved folders,
 * traversal and symlink rules. This class adds no authority: a path the Bridge refuses stays refused, and nothing here
 * falls back to a more privileged route (no shell, no root) when the Bridge says no.
 *
 * What it can and cannot do (see docs/VICTORIOUS_BRIDGE.md):
 *  - works only inside folders the owner approved for this app in Victorious Bridge;
 *  - in ADB mode it still cannot read other apps' private data - the Bridge's own protected-location list refuses it;
 *  - the Bridge moves at most [CHUNK] bytes per call, so large copies are chunked, cancellable, and clean up a partial file;
 *  - the Bridge has no rename/copy primitive, so `rename`, `copy` and `move` are read+write(+delete) and are NOT atomic.
 */
class BridgeFileIo(
    private val gateway: BridgeFileGateway,
    private val maxTextBytes: Long = 8L * 1024 * 1024,
    private val onProgress: (copiedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
) : FileIo {

    private fun call(op: String, vararg args: Pair<String, Any>): BridgeReply = gateway.call(op, mapOf(*args))

    private fun orThrow(r: BridgeReply): BridgeReply {
        if (!r.ok) throw BridgeFileException(r.errorCode ?: "INTERNAL", r.message ?: "Bridge request failed")
        return r
    }

    private fun stat(path: String): Map<String, Any?>? {
        val r = call("files.stat", "path" to path)
        if (r.ok) return r.data
        if (r.errorCode == "NOT_FOUND") return null
        throw BridgeFileException(r.errorCode ?: "INTERNAL", r.message ?: "stat failed")   // out of scope / access loss are NOT "does not exist"
    }

    override fun exists(path: String): Boolean = stat(path) != null
    override fun isDirectory(path: String): Boolean = stat(path)?.get("is_dir") == true
    override fun isFile(path: String): Boolean = stat(path)?.let { it["is_dir"] != true } ?: false
    override fun sizeOf(path: String): Long = (stat(path)?.get("size") as? Number)?.toLong() ?: throw FileIoException("No such file: $path")

    override fun list(path: String): List<FileEntry> {
        val out = ArrayList<FileEntry>()
        var offset = 0L
        val base = path.trimEnd('/')
        while (true) {
            if (gateway.isCancelled()) throw BridgeFileException("CANCELLED", "cancelled")
            val r = orThrow(call("files.list", "path" to path, "offset" to offset, "limit" to PAGE))
            @Suppress("UNCHECKED_CAST") val rows = (r.data["entries"] as? List<String>).orEmpty()
            for (row in rows) {
                val f = row.split('\t', limit = 4)
                if (f.size < 4 || f[0] == "l") continue            // symbolic links are never followed or listed as targets
                out += FileEntry("$base/${f[3]}", f[3], f[0] == "d", f[1].toLongOrNull() ?: 0, f[2].toLongOrNull() ?: 0, f[3].startsWith('.'))
            }
            offset += rows.size
            if (r.data["has_more"] != true || rows.isEmpty()) break
        }
        return out.sortedWith(compareBy<FileEntry> { !it.isDirectory }.thenBy { it.name.lowercase() })
    }

    override fun childNames(dir: String): Set<String> = list(dir).map { it.name }.toSet()

    override fun mkdir(path: String) { orThrow(call("files.mkdir", "path" to path)) }

    override fun mkdirs(path: String) {
        if (exists(path)) return
        val parent = path.trimEnd('/').substringBeforeLast('/', "")
        if (parent.isNotEmpty() && parent != path) mkdirs(parent)
        mkdir(path)
    }

    override fun readText(path: String): String {
        val size = sizeOf(path)
        if (size > maxTextBytes) throw BridgeFileException("TOO_LARGE", "file is $size bytes; text view is limited to $maxTextBytes")
        val bytes = java.io.ByteArrayOutputStream()
        readChunks(path, size) { bytes.write(it) }
        return bytes.toString(Charsets.UTF_8)
    }

    override fun writeText(path: String, text: String) {
        val data = text.toByteArray(Charsets.UTF_8)
        writeChunks(path, data.size.toLong()) { off, len -> data.copyOfRange(off.toInt(), (off + len).toInt()) }
    }

    override fun copy(from: String, to: String) {
        if (isDirectory(from)) {
            mkdirs(to)
            for (e in list(from)) copy(e.path, "${to.trimEnd('/')}/${e.name}")
            return
        }
        val size = sizeOf(from)
        var written = 0L
        try {
            // Stream chunk by chunk; nothing larger than CHUNK is ever held in memory.
            readChunks(from, size) { chunk ->
                val r = call("files.write", "path" to to, "offset" to written, "data" to chunk, "truncate" to (written == 0L))
                orThrow(r); written += chunk.size; onProgress(written, size)
            }
            if (size == 0L) orThrow(call("files.write", "path" to to, "offset" to 0L, "data" to ByteArray(0), "truncate" to true))
        } catch (e: Exception) {
            // A half-written destination is worse than none. Best effort: if access was lost this may itself be refused.
            runCatching { call("files.delete", "path" to to) }
            throw e
        }
    }

    override fun move(from: String, to: String) { copy(from, to); deleteRecursively(from) }
    override fun rename(from: String, to: String) = move(from, to)

    override fun deleteRecursively(path: String) {
        val s = stat(path) ?: return
        if (s["is_dir"] == true) for (e in list(path)) deleteRecursively(e.path)     // the Bridge only deletes empty folders
        orThrow(call("files.delete", "path" to path))
    }

    private fun readChunks(path: String, size: Long, sink: (ByteArray) -> Unit) {
        var off = 0L
        while (off < size) {
            if (gateway.isCancelled()) throw BridgeFileException("CANCELLED", "cancelled")
            val r = orThrow(call("files.read", "path" to path, "offset" to off, "length" to CHUNK.toLong()))
            val d = r.data["data"] as? ByteArray ?: throw FileIoException("Bridge returned no data")
            if (d.isEmpty()) break
            sink(d); off += d.size
            if (r.data["eof"] == true) break
        }
    }

    private fun writeChunks(path: String, total: Long, chunk: (Long, Int) -> ByteArray) {
        var off = 0L
        if (total == 0L) { orThrow(call("files.write", "path" to path, "offset" to 0L, "data" to ByteArray(0), "truncate" to true)); return }
        while (off < total) {
            if (gateway.isCancelled()) throw BridgeFileException("CANCELLED", "cancelled")
            val n = minOf(CHUNK.toLong(), total - off).toInt()
            orThrow(call("files.write", "path" to path, "offset" to off, "data" to chunk(off, n), "truncate" to (off == 0L)))
            off += n
        }
    }

    companion object {
        /** Matches the Bridge's per-call limit (1 MiB). */
        const val CHUNK = 1024 * 1024
        private const val PAGE = 500L
    }
}
