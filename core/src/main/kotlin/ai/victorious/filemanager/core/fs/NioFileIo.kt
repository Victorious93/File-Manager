package ai.victorious.filemanager.core.fs

import ai.victorious.filemanager.core.path.PathHelpers
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.isHidden
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/** java.nio-backed [FileIo] used by unit tests and the Android app on local storage. */
class NioFileIo : FileIo {
    private fun p(path: String): Path = Path.of(PathHelpers.normalize(path))

    override fun exists(path: String): Boolean = p(path).exists()

    override fun isDirectory(path: String): Boolean = p(path).isDirectory()

    override fun isFile(path: String): Boolean = p(path).isRegularFile()

    /**
     * One stat per child (readAttributes) instead of four (isDirectory/size/mtime/hidden), and a
     * precomputed sort key so 50k-entry folders sort without re-lowercasing per comparison.
     */
    override fun list(path: String): List<FileEntry> {
        val dir = p(path)
        if (!dir.isDirectory()) throw FileIoException("Not a directory: $path")
        val parent = dir.toAbsolutePath().toString()
        val keyed = ArrayList<Pair<String, FileEntry>>()
        Files.newDirectoryStream(dir).use { stream ->
            for (child in stream) {
                val name = child.fileName.toString()
                val attrs = runCatching { Files.readAttributes(child, BasicFileAttributes::class.java) }.getOrNull()
                val isDir = attrs?.isDirectory ?: false
                val entry = FileEntry(
                    path = if (parent == "/") "/$name" else "$parent/$name",
                    name = name,
                    isDirectory = isDir,
                    sizeBytes = if (isDir || attrs == null) 0 else attrs.size(),
                    modifiedEpochMs = attrs?.lastModifiedTime()?.toMillis() ?: 0,
                    isHidden = name.startsWith('.'),
                )
                keyed += ((if (isDir) "0" else "1") + name.lowercase()) to entry
            }
        }
        keyed.sortWith(compareBy { it.first })
        return keyed.map { it.second }
    }

    override fun mkdir(path: String) {
        Files.createDirectory(p(path))
    }

    override fun mkdirs(path: String) {
        Files.createDirectories(p(path))
    }

    override fun rename(from: String, to: String) {
        val src = p(from)
        val dst = p(to)
        if (!src.exists()) throw FileIoException("Missing: $from")
        if (dst.exists()) throw FileIoException("Already exists: $to")
        Files.move(src, dst)
    }

    override fun copy(from: String, to: String) {
        val src = p(from)
        val dst = p(to)
        if (!src.exists()) throw FileIoException("Missing: $from")
        if (dst.exists()) throw FileIoException("Already exists: $to")
        if (src.isDirectory()) copyDirectory(src, dst) else {
            dst.parent?.let { Files.createDirectories(it) }
            Files.copy(src, dst, StandardCopyOption.COPY_ATTRIBUTES)
        }
    }

    override fun move(from: String, to: String) {
        val src = p(from)
        val dst = p(to)
        if (!src.exists()) throw FileIoException("Missing: $from")
        if (dst.exists()) throw FileIoException("Already exists: $to")
        dst.parent?.let { Files.createDirectories(it) }
        try {
            Files.move(src, dst)
        } catch (_: Exception) {
            copy(from, to)
            deleteRecursively(from)
        }
    }

    override fun deleteRecursively(path: String) {
        val target = p(path)
        if (!target.exists()) return
        Files.walkFileTree(
            target,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                    Files.deleteIfExists(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    override fun sizeOf(path: String): Long {
        val target = p(path)
        if (target.isRegularFile()) return target.fileSize()
        if (!target.isDirectory()) return 0
        var total = 0L
        Files.walk(target).use { walk ->
            walk.filter { it.isRegularFile() }.forEach { total += it.fileSize() }
        }
        return total
    }

    override fun childNames(dir: String): Set<String> {
        val target = p(dir)
        if (!target.isDirectory()) return emptySet()
        return Files.list(target).use { it.map { child -> child.name }.toList().toSet() }
    }

    override fun readText(path: String): String {
        val target = p(path)
        if (!target.exists()) return ""
        return Files.newBufferedReader(target).use { it.readText() }
    }

    override fun writeText(path: String, text: String) {
        val target = p(path)
        target.parent?.let { Files.createDirectories(it) }
        Files.newBufferedWriter(target).use { it.write(text) }
    }

    /**
     * Copies a folder's contents. A link to a file already copies as the file (Files.copy follows it), so a link
     * to a folder copies as that folder too, here and anywhere inside the tree. Files.walk does not follow links,
     * so it used to see a folder link as one entry and left an empty folder behind.
     */
    private fun copyDirectory(src: Path, dst: Path) = copyTree(src, dst, emptySet())

    /** [open] holds the real path of every folder being copied above this one, to recognise a link back into them. */
    private fun copyTree(src: Path, dst: Path, open: Set<Path>) {
        Files.createDirectories(dst)
        // plusElement, not +: a Path is itself an Iterable<Path>, so + would add its name segments one by one.
        val here = open.plusElement(src.toRealPath())
        Files.newDirectoryStream(src).use { children ->
            for (child in children) {
                val dest = dst.resolve(child.fileName.toString())
                when {
                    Files.isSymbolicLink(child) -> copyLink(child, dest, here)
                    Files.isDirectory(child) -> {
                        // Only reachable through a followed link; the folder is already being copied further up.
                        if (child.toRealPath() !in here) copyTree(child, dest, here)
                    }
                    else -> Files.copy(child, dest, StandardCopyOption.COPY_ATTRIBUTES)
                }
            }
        }
    }

    private fun copyLink(link: Path, dest: Path, open: Set<Path>) {
        // toRealPath fails for a link with nothing behind it (dangling, or a cycle of links).
        val target = runCatching { link.toRealPath() }.getOrNull()
        when {
            target == null || (target.isDirectory() && target in open) -> keepAsLink(link, dest)
            target.isDirectory() -> copyTree(link, dest, open)
            else -> Files.copy(link, dest, StandardCopyOption.COPY_ATTRIBUTES)
        }
    }

    /**
     * A link that cannot be followed has no content to copy, so it is recreated as a link, with the same target
     * text. Following a link back into a folder being copied would never end. Some destinations (FAT/exFAT cards,
     * Android shared storage) cannot hold links at all; the link is then left out rather than failing a copy that
     * would otherwise succeed, since nothing but the link itself is lost.
     */
    private fun keepAsLink(link: Path, dest: Path) {
        try {
            Files.createSymbolicLink(dest, Files.readSymbolicLink(link))
        } catch (_: java.io.IOException) {
            // not permitted here
        } catch (_: UnsupportedOperationException) {
            // file system has no links
        }
    }

    private fun toEntry(path: Path): FileEntry {
        val isDir = path.isDirectory()
        return FileEntry(
            path = path.toAbsolutePath().toString(),
            name = path.name,
            isDirectory = isDir,
            sizeBytes = if (isDir) 0 else runCatching { path.fileSize() }.getOrDefault(0),
            modifiedEpochMs = runCatching { path.getLastModifiedTime().toMillis() }.getOrDefault(0),
            isHidden = runCatching { path.isHidden() }.getOrDefault(path.name.startsWith('.')),
        )
    }
}
