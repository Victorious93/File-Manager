package ai.victorious.filemanager.sync

import ai.victorious.filemanager.core.sync.SyncFile
import ai.victorious.filemanager.core.sync.SyncFs
import ai.victorious.filemanager.core.sync.SYNC_PART_PREFIX
import java.io.File
import java.io.IOException
import java.io.InputStream

class LocalSyncFs : SyncFs {
    override fun listRecursive(root: String): List<SyncFile> {
        val base = File(root)
        if (!base.isDirectory) return emptyList()
        return base.walkTopDown().filter { it != base && !it.name.startsWith(SYNC_PART_PREFIX) }.map { f ->
            val rel = f.relativeTo(base).invariantSeparatorsPath
            SyncFile(rel, if (f.isFile) f.length() else 0L, f.lastModified(), f.isDirectory)
        }.toList()
    }

    override fun read(root: String, relative: String): ByteArray = File(root, relative).readBytes()

    override fun write(root: String, relative: String, bytes: ByteArray, modifiedEpochMs: Long) =
        writeFrom(root, relative, bytes.inputStream(), modifiedEpochMs)

    override fun openRead(root: String, relative: String): InputStream = File(root, relative).inputStream()

    /** Streams into a hidden temp file next to the target, then renames it into place. */
    override fun writeFrom(root: String, relative: String, input: InputStream, modifiedEpochMs: Long) {
        val f = File(root, relative)
        f.parentFile?.mkdirs()
        val part = File(f.parentFile, SYNC_PART_PREFIX + System.nanoTime() + "-" + f.name)
        try {
            part.outputStream().use { out -> input.copyTo(out, 256 * 1024) }
            part.setLastModified(modifiedEpochMs)
            if (!part.renameTo(f)) {
                f.delete()
                if (!part.renameTo(f)) throw IOException("Cannot replace $f")
            }
            f.setLastModified(modifiedEpochMs)
        } finally {
            if (part.exists()) part.delete()
        }
    }

    override fun delete(root: String, relative: String) {
        File(root, relative).deleteRecursively()
    }

    override fun mkdir(root: String, relative: String) {
        File(root, relative).mkdirs()
    }

    override fun exists(root: String, relative: String): Boolean = File(root, relative).exists()
}
