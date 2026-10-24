package ai.victorious.filemanager.core.fs

data class FileEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val modifiedEpochMs: Long,
    val isHidden: Boolean,
)

interface FileIo {
    fun exists(path: String): Boolean
    fun isDirectory(path: String): Boolean
    fun isFile(path: String): Boolean
    fun list(path: String): List<FileEntry>
    fun mkdir(path: String)
    fun mkdirs(path: String)
    fun rename(from: String, to: String)
    fun copy(from: String, to: String)
    fun move(from: String, to: String)
    fun deleteRecursively(path: String)
    fun sizeOf(path: String): Long
    fun childNames(dir: String): Set<String>
    fun readText(path: String): String
    fun writeText(path: String, text: String)
}

class FileIoException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
