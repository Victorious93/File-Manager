package ai.victorious.filemanager.core.mypc

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/** One side of a transfer: the phone's storage or the PC over SFTP. Paths are side-native. */
interface TransferSide {
    data class Stat(val size: Long, val isDirectory: Boolean)

    fun stat(path: String): Stat?
    fun listNames(dir: String): List<String>
    fun mkdirs(path: String)
    fun openRead(path: String, offset: Long): InputStream

    /** Opens [path] for writing at [offset]. Offset 0 creates or truncates; above 0 keeps the first [offset] bytes. */
    fun openWrite(path: String, offset: Long): OutputStream
    fun rename(from: String, to: String)
    fun delete(path: String)

    /** Lower-case hex SHA-256, or null when this side cannot hash (e.g. no `sha256sum` on the PC). */
    fun sha256(path: String): String?
    fun join(dir: String, name: String): String = if (dir.endsWith("/")) dir + name else "$dir/$name"
}

class TransferVerifyException(message: String) : IOException(message)
/** Thrown between chunks when the job was cancelled. [partialPath] is left in place; the caller decides. */
class TransferCancelledException(val partialPath: String? = null) : IOException("Transfer cancelled")

/**
 * Resumable, verified single-file copy:
 * 1. write to `<dest>.partial`, resuming from its current length when that is not past the source size;
 * 2. check the finished size, then SHA-256 on both sides when both can hash;
 * 3. only then rename `.partial` to the final name. A resumed file that fails the hash is
 *    restarted once from zero (the old bytes may have come from a different version of the source).
 */
object ResumableTransfer {
    const val PARTIAL_SUFFIX = ".partial"
    private const val BUFFER = 32 * 1024

    data class Result(
        val bytesCopied: Long,
        val resumedFrom: Long,
        val size: Long,
        /** True when SHA-256 matched on both sides; false when only the size could be checked. */
        val hashVerified: Boolean,
        /** True when the final file was already there from an earlier, interrupted attempt. */
        val alreadyDone: Boolean = false,
    )

    fun partialPath(dest: String) = dest + PARTIAL_SUFFIX

    fun copyFile(
        src: TransferSide,
        srcPath: String,
        dst: TransferSide,
        dstPath: String,
        onProgress: (fileBytesDone: Long, fileSize: Long) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): Result {
        val srcStat = src.stat(srcPath) ?: throw IOException("Source is gone: $srcPath")
        require(!srcStat.isDirectory) { "copyFile needs a file" }
        val size = srcStat.size
        dst.stat(dstPath)?.let { existing ->
            // The destination name is chosen (unique) when the job is queued, so a final file with
            // the right size here means an earlier attempt finished but stopped before cleanup.
            if (!existing.isDirectory && existing.size == size) {
                val agree = hashesAgree(src, srcPath, dst, dstPath)
                if (agree != false) return Result(0, size, size, agree == true, alreadyDone = true)
            }
            throw IOException("Destination already exists: $dstPath")
        }
        val partial = partialPath(dstPath)
        var attempt = 0
        while (true) {
            attempt++
            val have = dst.stat(partial)?.takeIf { !it.isDirectory }?.size ?: 0L
            val offset = if (have in 0..size) have else 0L
            if (have > size) dst.delete(partial)
            val copied = if (offset < size || size == 0L) copyRange(src, srcPath, dst, partial, offset, size, onProgress, isCancelled) else 0L
            onProgress(size, size)
            val got = dst.stat(partial)?.size ?: -1L
            if (got != size) {
                if (offset > 0 && attempt == 1) { dst.delete(partial); continue }
                throw TransferVerifyException("Size check failed for $dstPath: expected $size bytes, got $got")
            }
            when (hashesAgree(src, srcPath, dst, partial)) {
                false -> {
                    dst.delete(partial)
                    if (offset > 0 && attempt == 1) continue
                    throw TransferVerifyException("SHA-256 check failed for $dstPath; the copy was discarded")
                }
                true -> { dst.rename(partial, dstPath); return Result(copied, offset, size, hashVerified = true) }
                null -> { dst.rename(partial, dstPath); return Result(copied, offset, size, hashVerified = false) }
            }
        }
    }

    /** True = both hashed and equal, false = both hashed and different, null = a side could not hash. */
    private fun hashesAgree(src: TransferSide, srcPath: String, dst: TransferSide, dstPath: String): Boolean? {
        val a = src.sha256(srcPath) ?: return null
        val b = dst.sha256(dstPath) ?: return null
        return a.equals(b, ignoreCase = true)
    }

    private fun copyRange(
        src: TransferSide,
        srcPath: String,
        dst: TransferSide,
        partial: String,
        offset: Long,
        size: Long,
        onProgress: (Long, Long) -> Unit,
        isCancelled: () -> Boolean,
    ): Long {
        var done = offset
        val buf = ByteArray(BUFFER)
        src.openRead(srcPath, offset).use { input ->
            dst.openWrite(partial, offset).use { output ->
                onProgress(done, size)
                while (true) {
                    if (isCancelled()) throw TransferCancelledException(partial)
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    done += n
                    onProgress(done, size)
                }
            }
        }
        return done - offset
    }
}

/**
 * Copies or moves a file or folder tree with [ResumableTransfer]. A move deletes each source file
 * only after its copy verified, and a source folder only once it is empty.
 */
class TreeTransfer(
    private val src: TransferSide,
    private val dst: TransferSide,
    private val move: Boolean,
    private val isCancelled: () -> Boolean = { false },
    private val onProgress: (bytesDone: Long, bytesTotal: Long, currentName: String) -> Unit = { _, _, _ -> },
) {
    data class Summary(val files: Int, val bytes: Long, val hashVerifiedFiles: Int, val resumedFiles: Int)

    private var total = 0L
    private var doneBefore = 0L
    private var files = 0
    private var hashed = 0
    private var resumed = 0

    fun run(srcPath: String, dstPath: String): Summary {
        total = measure(srcPath)
        transfer(srcPath, dstPath)
        return Summary(files, total, hashed, resumed)
    }

    private fun measure(path: String): Long {
        val st = src.stat(path) ?: throw IOException("Source is gone: $path")
        if (!st.isDirectory) return st.size
        return src.listNames(path).sumOf { measure(src.join(path, it)) }
    }

    private fun transfer(srcPath: String, dstPath: String) {
        if (isCancelled()) throw TransferCancelledException()
        val st = src.stat(srcPath) ?: throw IOException("Source is gone: $srcPath")
        if (st.isDirectory) {
            if (dst.stat(dstPath) == null) dst.mkdirs(dstPath)
            for (name in src.listNames(srcPath)) transfer(src.join(srcPath, name), dst.join(dstPath, name))
            if (move && src.listNames(srcPath).isEmpty()) src.delete(srcPath)
            return
        }
        val name = srcPath.substringAfterLast('/')
        val r = ResumableTransfer.copyFile(src, srcPath, dst, dstPath, { d, _ -> onProgress(doneBefore + d, total, name) }, isCancelled)
        files++
        if (r.hashVerified) hashed++
        if (r.resumedFrom > 0 && !r.alreadyDone) resumed++
        doneBefore += r.size
        if (move) src.delete(srcPath)
    }
}

object TransferNames {
    /** `name`, else `name (1).ext`, `name (2).ext`, … — the first that [exists] says is free. */
    fun unique(dir: String, name: String, join: (String, String) -> String, exists: (String) -> Boolean): String {
        if (!exists(join(dir, name)) && !exists(join(dir, name + ResumableTransfer.PARTIAL_SUFFIX))) return join(dir, name)
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val base = name.substring(0, dot)
        val ext = name.substring(dot)
        for (i in 1..9_999) {
            val candidate = join(dir, "$base ($i)$ext")
            if (!exists(candidate) && !exists(candidate + ResumableTransfer.PARTIAL_SUFFIX)) return candidate
        }
        throw IOException("No free name for $name in $dir")
    }
}

/** The phone side: plain java.io files. */
class LocalTransferSide : TransferSide {
    override fun stat(path: String): TransferSide.Stat? {
        val f = File(path)
        if (!f.exists()) return null
        return TransferSide.Stat(if (f.isDirectory) 0L else f.length(), f.isDirectory)
    }

    override fun listNames(dir: String): List<String> = File(dir).list()?.sorted() ?: throw IOException("Cannot list $dir")

    override fun mkdirs(path: String) {
        val f = File(path)
        if (!f.isDirectory && !f.mkdirs()) throw IOException("Cannot create $path")
    }

    override fun openRead(path: String, offset: Long): InputStream {
        val raf = RandomAccessFile(path, "r")
        raf.seek(offset)
        return Channels.newInputStream(raf.channel)
    }

    override fun openWrite(path: String, offset: Long): OutputStream {
        File(path).parentFile?.let { if (!it.isDirectory) it.mkdirs() }
        val ch = FileChannel.open(File(path).toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        ch.truncate(offset)
        ch.position(offset)
        return Channels.newOutputStream(ch)
    }

    override fun rename(from: String, to: String) {
        val t = File(to)
        if (t.exists()) throw IOException("Destination already exists: $to")
        if (!File(from).renameTo(t)) throw IOException("Cannot rename $from")
    }

    override fun delete(path: String) {
        val f = File(path)
        if (f.exists() && !f.delete()) throw IOException("Cannot delete $path")
    }

    override fun sha256(path: String): String? = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        File(path).inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()
}
