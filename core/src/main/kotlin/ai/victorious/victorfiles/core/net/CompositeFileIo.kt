package ai.victorious.victorfiles.core.net

import ai.victorious.victorfiles.core.fs.FileEntry
import ai.victorious.victorfiles.core.fs.FileIo
import ai.victorious.victorfiles.core.fs.FileIoException
import ai.victorious.victorfiles.core.path.PathHelpers
import java.io.OutputStream

/**
 * Routes `/remote/{id}/…` to [RemoteBackend] instances and everything else to [local].
 * Cross-side copy/move streams through this process (upload/download).
 */
class CompositeFileIo(
    private val local: FileIo,
    private val backends: (String) -> RemoteBackend?,
    private val listConnections: () -> List<SavedConnection> = { emptyList() },
) : FileIo {

    private fun backend(path: String): Pair<RemoteBackend, String>? {
        if (!RemotePaths.isRemote(path)) return null
        if (PathHelpers.normalize(path) == RemotePaths.ROOT) return null
        val id = RemotePaths.connectionId(path) ?: return null
        val b = backends(id) ?: throw FileIoException("Remote connection not open: $id")
        return b to RemotePaths.remoteRelative(path)
    }

    override fun exists(path: String): Boolean {
        val n = PathHelpers.normalize(path)
        if (n == RemotePaths.ROOT) return true
        val rem = backend(path)
        return if (rem != null) rem.first.exists(rem.second) else local.exists(path)
    }

    override fun isDirectory(path: String): Boolean {
        val n = PathHelpers.normalize(path)
        if (n == RemotePaths.ROOT) return true
        if (n.startsWith("${RemotePaths.ROOT}/") && RemotePaths.remoteRelative(path) == "/") {
            return backends(RemotePaths.connectionId(path)!!) != null ||
                listConnections().any { it.id == RemotePaths.connectionId(path) }
        }
        val rem = backend(path)
        return if (rem != null) rem.first.isDirectory(rem.second) else local.isDirectory(path)
    }

    override fun isFile(path: String): Boolean = exists(path) && !isDirectory(path)

    override fun list(path: String): List<FileEntry> {
        val n = PathHelpers.normalize(path)
        if (n == RemotePaths.ROOT) {
            return listConnections().map {
                FileEntry(
                    path = RemotePaths.connectionRoot(it.id),
                    name = it.name.ifBlank { it.host },
                    isDirectory = true,
                    sizeBytes = 0,
                    modifiedEpochMs = 0,
                    isHidden = false,
                )
            }.sortedBy { it.name.lowercase() }
        }
        val rem = backend(path)
        return if (rem != null) rem.first.list(rem.second) else local.list(path)
    }

    override fun mkdir(path: String) {
        val rem = backend(path)
        if (rem != null) rem.first.mkdir(rem.second) else local.mkdir(path)
    }

    override fun mkdirs(path: String) {
        val rem = backend(path)
        if (rem != null) {
            val parts = rem.second.trim('/').split('/').filter { it.isNotEmpty() }
            var cur = ""
            for (part in parts) {
                cur = "$cur/$part"
                if (!rem.first.exists(cur)) rem.first.mkdir(cur)
            }
        } else {
            local.mkdirs(path)
        }
    }

    override fun rename(from: String, to: String) {
        val a = backend(from)
        val b = backend(to)
        when {
            a != null && b != null && a.first.connectionId == b.first.connectionId ->
                a.first.rename(a.second, b.second)
            a == null && b == null -> local.rename(from, to)
            else -> {
                copy(from, to)
                deleteRecursively(from)
            }
        }
    }

    override fun copy(from: String, to: String) {
        val a = backend(from)
        val b = backend(to)
        when {
            a == null && b == null -> local.copy(from, to)
            a != null && b == null -> download(a.first, a.second, to)
            a == null && b != null -> upload(from, b.first, b.second)
            a != null && b != null -> {
                a.first.openInput(a.second).use { input ->
                    b.first.openOutput(b.second).use { output -> input.copyTo(output) }
                }
            }
        }
    }

    override fun move(from: String, to: String) {
        copy(from, to)
        deleteRecursively(from)
    }

    override fun deleteRecursively(path: String) {
        val rem = backend(path)
        if (rem != null) {
            if (rem.first.isDirectory(rem.second)) {
                for (child in rem.first.list(rem.second)) {
                    deleteRecursively(child.path)
                }
            }
            if (rem.second != "/" && rem.second.isNotEmpty()) {
                rem.first.delete(rem.second)
            }
        } else {
            local.deleteRecursively(path)
        }
    }

    override fun sizeOf(path: String): Long {
        val rem = backend(path)
        return if (rem != null) rem.first.sizeOf(rem.second) else local.sizeOf(path)
    }

    override fun childNames(dir: String): Set<String> = list(dir).map { it.name }.toSet()

    override fun readText(path: String): String {
        val rem = backend(path)
        return if (rem != null) {
            rem.first.openInput(rem.second).bufferedReader().use { it.readText() }
        } else {
            local.readText(path)
        }
    }

    override fun writeText(path: String, text: String) {
        val rem = backend(path)
        if (rem != null) {
            rem.first.openOutput(rem.second).use { (it as OutputStream).write(text.toByteArray()) }
        } else {
            local.writeText(path, text)
        }
    }

    private fun download(backend: RemoteBackend, remotePath: String, localPath: String) {
        if (backend.isDirectory(remotePath)) {
            local.mkdirs(localPath)
            for (child in backend.list(remotePath)) {
                val name = child.name
                val dest = PathHelpers.join(localPath, name)
                download(backend, RemotePaths.remoteRelative(child.path), dest)
            }
        } else {
            val parent = PathHelpers.parentOf(localPath)
            if (parent != null) local.mkdirs(parent)
            backend.openInput(remotePath).use { input ->
                java.io.File(localPath).outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    private fun upload(localPath: String, backend: RemoteBackend, remotePath: String) {
        if (local.isDirectory(localPath)) {
            if (!backend.exists(remotePath)) backend.mkdir(remotePath)
            for (child in local.list(localPath)) {
                val destRemote = if (remotePath == "/") "/${child.name}" else "${remotePath.trimEnd('/')}/${child.name}"
                upload(child.path, backend, destRemote)
            }
        } else {
            val parent = remotePath.substringBeforeLast('/', missingDelimiterValue = "")
            if (parent.isNotEmpty() && parent != remotePath && !backend.exists(parent)) {
                mkdirs(RemotePaths.toVirtual(backend.connectionId, parent))
            }
            java.io.File(localPath).inputStream().use { input ->
                backend.openOutput(remotePath).use { output -> input.copyTo(output) }
            }
        }
    }
}
