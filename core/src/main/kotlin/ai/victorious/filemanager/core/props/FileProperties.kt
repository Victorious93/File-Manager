package ai.victorious.filemanager.core.props

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest

enum class HashAlgorithm(val jca: String) { MD5("MD5"), SHA1("SHA-1"), SHA256("SHA-256") }

data class FolderStats(val files: Long, val folders: Long, val bytes: Long, val unreadable: Long)

/** Local-filesystem properties: checksums and recursive folder totals. */
object FileProperties {
    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun checksums(input: InputStream, algorithms: Set<HashAlgorithm> = HashAlgorithm.entries.toSet()): Map<HashAlgorithm, String> {
        val digests = algorithms.associateWith { MessageDigest.getInstance(it.jca) }
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            digests.values.forEach { it.update(buf, 0, n) }
        }
        return digests.mapValues { hex(it.value.digest()) }
    }

    fun checksums(file: File, algorithms: Set<HashAlgorithm> = HashAlgorithm.entries.toSet()): Map<HashAlgorithm, String> {
        require(file.isFile) { "Not a file: ${file.path}" }
        return file.inputStream().buffered().use { checksums(it, algorithms) }
    }

    /** Totals under [dir]. Symlinks are counted but never followed, so loops cannot hang it. */
    fun folderStats(dir: File, isCancelled: () -> Boolean = { false }): FolderStats {
        var files = 0L
        var folders = 0L
        var bytes = 0L
        var unreadable = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        while (stack.isNotEmpty() && !isCancelled()) {
            val current = stack.removeLast()
            val children = current.listFiles()
            if (children == null) {
                unreadable++
                continue
            }
            for (c in children) {
                val isLink = Files.isSymbolicLink(c.toPath())
                if (!isLink && c.isDirectory) {
                    folders++
                    stack.addLast(c)
                } else {
                    files++
                    if (!isLink) bytes += c.length()
                }
            }
        }
        return FolderStats(files, folders, bytes, unreadable)
    }

    /** True when a user-pasted checksum matches [actual], ignoring case, whitespace and colons. */
    fun matches(expected: String, actual: String): Boolean {
        val norm = expected.filter { !it.isWhitespace() && it != ':' }
        return norm.isNotEmpty() && norm.equals(actual, ignoreCase = true)
    }
}
