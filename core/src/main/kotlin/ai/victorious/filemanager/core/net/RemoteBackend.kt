package ai.victorious.filemanager.core.net

import ai.victorious.filemanager.core.fs.FileEntry
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

interface RemoteBackend : AutoCloseable {
    val connectionId: String
    fun list(remotePath: String): List<FileEntry>
    fun exists(remotePath: String): Boolean
    fun isDirectory(remotePath: String): Boolean
    fun mkdir(remotePath: String)
    fun delete(remotePath: String)
    fun rename(from: String, to: String)
    fun openInput(remotePath: String): InputStream
    fun openOutput(remotePath: String): OutputStream
    fun sizeOf(remotePath: String): Long
}

interface RemoteBackendFactory {
    fun open(connection: SavedConnection, secret: String?): RemoteBackend
}

/** In-memory backend for unit tests. */
class InMemoryRemoteBackend(
    override val connectionId: String,
) : RemoteBackend {
    private data class Node(
        var isDir: Boolean,
        var bytes: ByteArray = ByteArray(0),
        val children: MutableMap<String, Node> = linkedMapOf(),
        var modified: Long = System.currentTimeMillis(),
    )

    private val root = Node(isDir = true)

    private fun parts(path: String): List<String> =
        path.trim('/').split('/').filter { it.isNotEmpty() }

    private fun walk(path: String): Node? {
        var cur = root
        for (part in parts(path)) {
            cur = cur.children[part] ?: return null
        }
        return cur
    }

    private fun parentAndName(path: String): Pair<Node, String>? {
        val p = parts(path)
        if (p.isEmpty()) return null
        var cur = root
        for (part in p.dropLast(1)) {
            cur = cur.children[part] ?: return null
            if (!cur.isDir) return null
        }
        return cur to p.last()
    }

    fun seedDir(path: String) {
        var cur = root
        for (part in parts(path)) {
            cur = cur.children.getOrPut(part) { Node(isDir = true) }
            cur.isDir = true
        }
    }

    fun seedFile(path: String, content: ByteArray) {
        val p = parts(path)
        require(p.isNotEmpty())
        var cur = root
        for (part in p.dropLast(1)) {
            cur = cur.children.getOrPut(part) { Node(isDir = true) }
            cur.isDir = true
        }
        cur.children[p.last()] = Node(isDir = false, bytes = content)
    }

    override fun list(remotePath: String): List<FileEntry> {
        val node = walk(remotePath) ?: throw IllegalArgumentException("Missing: $remotePath")
        require(node.isDir) { "Not a directory: $remotePath" }
        return node.children.map { (name, child) ->
            val childRemote = when {
                remotePath.isBlank() || remotePath == "/" -> "/$name"
                else -> "${remotePath.trimEnd('/')}/$name"
            }
            FileEntry(
                path = RemotePaths.toVirtual(connectionId, childRemote),
                name = name,
                isDirectory = child.isDir,
                sizeBytes = if (child.isDir) 0L else child.bytes.size.toLong(),
                modifiedEpochMs = child.modified,
                isHidden = name.startsWith('.'),
            )
        }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    override fun exists(remotePath: String): Boolean = walk(remotePath) != null

    override fun isDirectory(remotePath: String): Boolean = walk(remotePath)?.isDir == true

    override fun mkdir(remotePath: String) = seedDir(remotePath)

    override fun delete(remotePath: String) {
        val (parent, name) = parentAndName(remotePath) ?: throw IllegalArgumentException("Cannot delete root")
        parent.children.remove(name) ?: throw IllegalArgumentException("Missing: $remotePath")
    }

    override fun rename(from: String, to: String) {
        val (fp, fn) = parentAndName(from) ?: throw IllegalArgumentException("Missing: $from")
        val node = fp.children.remove(fn) ?: throw IllegalArgumentException("Missing: $from")
        val destParts = parts(to)
        require(destParts.isNotEmpty())
        var cur = root
        for (part in destParts.dropLast(1)) {
            cur = cur.children.getOrPut(part) { Node(isDir = true) }
            require(cur.isDir)
        }
        val tn = destParts.last()
        require(!cur.children.containsKey(tn)) { "Exists: $to" }
        cur.children[tn] = node
    }

    override fun openInput(remotePath: String): InputStream {
        val node = walk(remotePath) ?: throw IllegalArgumentException("Missing: $remotePath")
        require(!node.isDir) { "Is a directory" }
        return node.bytes.inputStream()
    }

    override fun openOutput(remotePath: String): OutputStream {
        val p = parts(remotePath)
        require(p.isNotEmpty())
        var cur = root
        for (part in p.dropLast(1)) {
            cur = cur.children.getOrPut(part) { Node(isDir = true) }
            require(cur.isDir)
        }
        val name = p.last()
        val buf = ByteArrayOutputStream()
        return object : OutputStream() {
            override fun write(b: Int) = buf.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = buf.write(b, off, len)
            override fun close() {
                cur.children[name] = Node(isDir = false, bytes = buf.toByteArray())
            }
        }
    }

    override fun sizeOf(remotePath: String): Long {
        val node = walk(remotePath) ?: return 0L
        return if (node.isDir) 0L else node.bytes.size.toLong()
    }

    override fun close() = Unit
}
