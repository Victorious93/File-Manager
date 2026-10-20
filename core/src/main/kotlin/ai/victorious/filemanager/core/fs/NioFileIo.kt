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

    private fun copyDirectory(src: Path, dst: Path) {
        Files.walk(src).use { walk ->
            walk.forEach { current ->
                val relative = src.relativize(current)
                val dest = dst.resolve(relative.toString())
                if (current.isDirectory()) {
                    Files.createDirectories(dest)
                } else {
                    dest.parent?.let { Files.createDirectories(it) }
                    Files.copy(current, dest, StandardCopyOption.COPY_ATTRIBUTES)
                }
            }
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
