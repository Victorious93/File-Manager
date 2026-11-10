package ai.victorious.filemanager.core.dupes

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class DuplicateGroup(val size: Long, val hash: String, val files: List<File>) {
    /** Bytes reclaimable if all but one copy are removed. */
    val wasted: Long get() = size * (files.size - 1)
}

/** Scan options. Hidden folders (`.thumbnails`, `.trash`, app caches) are skipped by default. */
data class DupeOptions(
    val minSize: Long = 1,
    val skipHidden: Boolean = true,
    /** Parallel hashing threads; 2-4 keeps phone flash busy without starving the UI. */
    val threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
)

enum class DupePhase { LISTING, QUICK_CHECK, FULL_CHECK, DONE }

/** [done]/[total] count files in the current phase; [bytesRead] is everything read so far. */
data class DupeProgress(val phase: DupePhase, val done: Int, val total: Int, val bytesRead: Long, val filesSeen: Int)

/**
 * Remembers full hashes by path + size + modified time, so scanning the same folders again
 * (after deleting some copies, or from a sub-folder) skips files that have not changed.
 * Thread-safe; lives in memory only.
 */
class HashCache(private val maxEntries: Int = 200_000) {
    private val map = ConcurrentHashMap<String, String>()
    private fun key(path: String, size: Long, modified: Long) = "$size:$modified:$path"
    fun get(path: String, size: Long, modified: Long): String? = map[key(path, size, modified)]
    fun put(path: String, size: Long, modified: Long, hash: String) {
        if (map.size >= maxEntries) map.clear()
        map[key(path, size, modified)] = hash
    }
    val size: Int get() = map.size
}

/**
 * Finds duplicate files, written from scratch. Stages, each only for files still possibly equal:
 * 1. size (from one `walkFileTree` pass, no extra `stat` per file),
 * 2. a quick check: SHA-256 of the first and last 16 KB (catches same-header media fast),
 * 3. full SHA-256, in parallel, reusing [HashCache] hits.
 * Symlinks are never followed; unreadable files are skipped.
 */
object DuplicateFinder {
    private const val EDGE = 16L * 1024

    internal data class Candidate(val file: File, val size: Long, val modified: Long)

    fun scan(root: File, minSize: Long = 1, isCancelled: () -> Boolean = { false }): List<DuplicateGroup> =
        scan(root, DupeOptions(minSize = minSize, skipHidden = false), null, isCancelled) {}

    fun scan(
        root: File,
        options: DupeOptions,
        cache: HashCache? = null,
        isCancelled: () -> Boolean = { false },
        onProgress: (DupeProgress) -> Unit = {},
    ): List<DuplicateGroup> {
        val listed = list(root, options, isCancelled, onProgress)
        return groupCandidates(listed, options, cache, isCancelled, onProgress)
    }

    fun group(files: List<File>, minSize: Long = 1, isCancelled: () -> Boolean = { false }): List<DuplicateGroup> =
        groupCandidates(
            files.filter { it.isFile }.map { Candidate(it, it.length(), it.lastModified()) },
            DupeOptions(minSize = minSize, skipHidden = false),
            null,
            isCancelled,
        ) {}

    internal fun list(root: File, options: DupeOptions, isCancelled: () -> Boolean, onProgress: (DupeProgress) -> Unit): List<Candidate> {
        val out = ArrayList<Candidate>()
        val rootPath = root.absoluteFile.toPath()
        var last = 0L
        Files.walkFileTree(
            rootPath,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (isCancelled()) throw CancellationException("Scan cancelled")
                    if (options.skipHidden && dir != rootPath && dir.fileName?.toString()?.startsWith('.') == true) {
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (!attrs.isRegularFile || attrs.size() < options.minSize) return FileVisitResult.CONTINUE
                    if (options.skipHidden && file.fileName.toString().startsWith('.')) return FileVisitResult.CONTINUE
                    out += Candidate(file.toFile(), attrs.size(), attrs.lastModifiedTime().toMillis())
                    val now = System.nanoTime()
                    if (now - last > 150_000_000L) {
                        last = now
                        if (isCancelled()) throw CancellationException("Scan cancelled")
                        onProgress(DupeProgress(DupePhase.LISTING, out.size, 0, 0, out.size))
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                    if (file == rootPath) throw exc else FileVisitResult.CONTINUE
            },
        )
        return out
    }

    internal fun groupCandidates(
        files: List<Candidate>,
        options: DupeOptions,
        cache: HashCache?,
        isCancelled: () -> Boolean,
        onProgress: (DupeProgress) -> Unit,
    ): List<DuplicateGroup> {
        val seen = files.size
        val bySize = files.filter { it.size >= options.minSize }.groupBy { it.size }.filterValues { it.size > 1 }
        val bytesRead = AtomicLong()
        val pool = Executors.newFixedThreadPool(options.threads.coerceAtLeast(1)) { r -> Thread(r, "dupe-hash").apply { isDaemon = true } }
        try {
            // Stage 2: quick edge hash, only where sizes collide.
            val quickInput = bySize.values.flatten()
            val quick = hashAll(pool, quickInput, DupePhase.QUICK_CHECK, bytesRead, seen, isCancelled, onProgress) { c ->
                edgeHash(c.file, c.size).also { bytesRead.addAndGet(minOf(c.size, 2 * EDGE)) }
            }
            val afterQuick = quickInput.filter { quick[it] != null }
                .groupBy { it.size to quick[it] }
                .filterValues { it.size > 1 }
            // Stage 3: full hash, except for files small enough that the edge hash already read all of them.
            val needFull = afterQuick.values.flatten().filter { it.size > 2 * EDGE }
            val full = hashAll(pool, needFull, DupePhase.FULL_CHECK, bytesRead, seen, isCancelled, onProgress) { c ->
                cache?.get(c.file.path, c.size, c.modified) ?: hash(c.file, Long.MAX_VALUE).also {
                    bytesRead.addAndGet(c.size)
                    cache?.put(c.file.path, c.size, c.modified, it)
                }
            }
            val out = ArrayList<DuplicateGroup>()
            for ((key, cands) in afterQuick) {
                val (size, edge) = key
                val groups = if (size <= 2 * EDGE) mapOf(edge to cands) else cands.filter { full[it] != null }.groupBy { full[it] }
                for ((h, same) in groups) if (h != null && same.size > 1) {
                    out += DuplicateGroup(size, h, same.map { it.file }.sortedBy { it.path })
                }
            }
            onProgress(DupeProgress(DupePhase.DONE, 0, 0, bytesRead.get(), seen))
            return out.sortedWith(compareByDescending<DuplicateGroup> { it.wasted }.thenBy { it.files.first().path })
        } finally {
            pool.shutdownNow()
        }
    }

    private fun hashAll(
        pool: java.util.concurrent.ExecutorService,
        input: List<Candidate>,
        phase: DupePhase,
        bytesRead: AtomicLong,
        seen: Int,
        isCancelled: () -> Boolean,
        onProgress: (DupeProgress) -> Unit,
        hasher: (Candidate) -> String,
    ): Map<Candidate, String?> {
        if (input.isEmpty()) return emptyMap()
        val done = AtomicInteger()
        val futures: List<Pair<Candidate, Future<String?>>> = input.map { c ->
            c to pool.submit<String?> {
                if (isCancelled()) null else runCatching { hasher(c) }.getOrNull().also { done.incrementAndGet() }
            }
        }
        val out = HashMap<Candidate, String?>(input.size * 2)
        var last = 0L
        for ((c, f) in futures) {
            if (isCancelled()) {
                futures.forEach { it.second.cancel(true) }
                throw CancellationException("Scan cancelled")
            }
            out[c] = f.get()
            val now = System.nanoTime()
            if (now - last > 150_000_000L) {
                last = now
                onProgress(DupeProgress(phase, done.get(), input.size, bytesRead.get(), seen))
            }
        }
        onProgress(DupeProgress(phase, input.size, input.size, bytesRead.get(), seen))
        return out
    }

    /** SHA-256 over the size, the first 16 KB and the last 16 KB (the whole file when it is ≤ 32 KB). */
    internal fun edgeHash(file: File, size: Long): String {
        val md = MessageDigest.getInstance("SHA-256")
        for (i in 0 until 8) md.update((size ushr (8 * i)).toByte())
        RandomAccessFile(file, "r").use { raf ->
            val buf = ByteArray(EDGE.toInt())
            fun readAt(pos: Long, len: Int) {
                raf.seek(pos)
                var off = 0
                while (off < len) { val n = raf.read(buf, off, len - off); if (n < 0) break; off += n }
                md.update(buf, 0, off)
            }
            if (size <= 2 * EDGE) {
                var pos = 0L
                while (pos < size) { val len = minOf(EDGE, size - pos).toInt(); readAt(pos, len); pos += len }
            } else {
                readAt(0, EDGE.toInt())
                readAt(size - EDGE, EDGE.toInt())
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Default selection: keep the first (shortest path, then alphabetical) and mark the rest. */
    fun defaultSelection(groups: List<DuplicateGroup>): Set<File> =
        groups.flatMap { g -> g.files.sortedWith(compareBy({ it.path.length }, { it.path })).drop(1) }.toSet()

    /** Alternative selection: keep the newest copy in each group (by modified time) and mark the rest. */
    fun keepNewestSelection(groups: List<DuplicateGroup>): Set<File> =
        groups.flatMap { g -> g.files.sortedWith(compareByDescending<File> { it.lastModified() }.thenBy { it.path }).drop(1) }.toSet()

    /** Alternative selection: keep the oldest copy in each group (usually the original) and mark the rest. */
    fun keepOldestSelection(groups: List<DuplicateGroup>): Set<File> =
        groups.flatMap { g -> g.files.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.path }).drop(1) }.toSet()

    fun hash(file: File, limit: Long): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            var remaining = limit
            while (remaining > 0) {
                val r = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (r < 0) break
                md.update(buf, 0, r)
                remaining -= r
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
