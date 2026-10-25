package ai.victorious.filemanager.sync

import ai.victorious.filemanager.core.sync.SyncFile
import ai.victorious.filemanager.core.sync.SyncFs
import java.io.File

class LocalSyncFs : SyncFs {
    override fun listRecursive(root: String): List<SyncFile> {
        val base = File(root)
        if (!base.isDirectory) return emptyList()
        return base.walkTopDown().filter { it != base }.map { f ->
            val rel = f.relativeTo(base).invariantSeparatorsPath
            SyncFile(rel, if (f.isFile) f.length() else 0L, f.lastModified(), f.isDirectory)
        }.toList()
    }

    override fun read(root: String, relative: String): ByteArray = File(root, relative).readBytes()

    override fun write(root: String, relative: String, bytes: ByteArray, modifiedEpochMs: Long) {
        val f = File(root, relative)
        f.parentFile?.mkdirs()
        f.writeBytes(bytes)
        f.setLastModified(modifiedEpochMs)
    }

    override fun delete(root: String, relative: String) {
        File(root, relative).deleteRecursively()
    }

    override fun mkdir(root: String, relative: String) {
        File(root, relative).mkdirs()
    }

    override fun exists(root: String, relative: String): Boolean = File(root, relative).exists()
}
