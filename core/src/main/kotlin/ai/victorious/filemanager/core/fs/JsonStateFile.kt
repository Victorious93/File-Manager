package ai.victorious.filemanager.core.fs

import ai.victorious.filemanager.core.path.PathHelpers
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * A small JSON state file (the trash index, the bookmark list) that survives a crash mid-write and
 * a file it cannot read.
 *
 * Rewriting such a file in place means a write that dies part-way (disk full, process killed)
 * leaves truncated JSON, and a store that throws on unparseable JSON then fails every later
 * operation until app data is cleared. So [save] writes `<path>.tmp` first and only then swaps it
 * in, [load] finishes that swap if a crash interrupted it, and a file that still cannot be parsed
 * is set aside as `<path>.corrupt-<time>` (kept for recovery) while callers carry on with [empty].
 *
 * Not thread-safe by itself: callers that share an instance across threads must synchronize.
 */
class JsonStateFile<T>(
    private val io: FileIo,
    private val path: String,
    private val serializer: KSerializer<T>,
    private val empty: T,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val tmpPath get() = "$path.tmp"

    /** True when there is a saved state to load (including a swap that [load] will finish). */
    fun exists(): Boolean = io.exists(path) || pendingSwap() != null

    fun load(): T {
        if (!io.exists(path)) {
            // Dying between "drop the old file" and "rename the new one" in save() leaves a complete .tmp.
            if (pendingSwap() == null) return empty
            io.rename(tmpPath, path)
        }
        return try {
            decode(io.readText(path))
        } catch (e: SerializationException) {
            quarantine()
            empty
        }
    }

    /** The decoded `.tmp` if it is complete; a half-written one (a failed save) is not a state. */
    private fun pendingSwap(): T? =
        if (io.exists(tmpPath)) runCatching { decode(io.readText(tmpPath)) }.getOrNull() else null

    fun save(value: T) {
        PathHelpers.parentOf(path)?.let { if (!io.exists(it)) io.mkdirs(it) }
        io.writeText(tmpPath, json.encodeToString(serializer, value))
        io.deleteRecursively(path) // FileIo.rename refuses to overwrite
        io.rename(tmpPath, path)
    }

    private fun decode(text: String): T = if (text.isBlank()) empty else json.decodeFromString(serializer, text)

    private fun quarantine() {
        val parent = PathHelpers.parentOf(path) ?: return
        val taken = io.childNames(parent)
        val base = "${PathHelpers.fileName(path)}.corrupt-${clock()}"
        var name = base
        var n = 1
        while (name in taken) name = "$base-${n++}"
        io.rename(path, PathHelpers.join(parent, name))
    }
}
