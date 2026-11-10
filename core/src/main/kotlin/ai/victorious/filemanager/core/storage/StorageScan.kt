package ai.victorious.filemanager.core.storage

import ai.victorious.filemanager.core.search.FileCategory
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.PriorityQueue
import java.util.concurrent.CancellationException

/** One folder in a [StorageTree]: its recursive size and file count, and its sub-folders. */
class DirNode internal constructor(val path: String, val name: String) {
    var bytes: Long = 0L
        internal set
    var files: Int = 0
        internal set
    internal val childList = ArrayList<DirNode>()
    val children: List<DirNode> get() = childList
}

data class LargeFile(val path: String, val bytes: Long, val modified: Long)

data class CategoryTotal(val bytes: Long, val files: Int)

/** Snapshot of how far a scan has got; [currentDir] is for display only. */
data class ScanProgress(val files: Int, val folders: Int, val bytes: Long, val currentDir: String)

/**
 * The result of one [StorageScan.scan]: every folder's recursive size (so drilling down is instant,
 * with no second walk), the largest files anywhere under the root, and totals per file type.
 */
class StorageTree internal constructor(
    val root: DirNode,
    val largest: List<LargeFile>,
    val byCategory: Map<FileCategory?, CategoryTotal>,
    val folders: Int,
    /** Folders the app could not open (permissions, Android/data on newer Android, …). */
    val skipped: Int,
    val elapsedMs: Long,
) {
    private val index: Map<String, DirNode> by lazy {
        val m = HashMap<String, DirNode>()
        val stack = ArrayDeque<DirNode>().apply { add(root) }
        while (stack.isNotEmpty()) { val n = stack.removeLast(); m[n.path] = n; stack.addAll(n.childList) }
        m
    }

    fun node(dir: File): DirNode? = index[dir.absolutePath]

    /** The share of the scanned total taken by each file type, largest first. "Other" is the null key. */
    fun categoriesBySize(): List<Pair<FileCategory?, CategoryTotal>> =
        byCategory.entries.map { it.key to it.value }.sortedByDescending { it.second.bytes }
}

/**
 * Fast storage analyzer. One `Files.walkFileTree` pass: every entry's size comes with its directory
 * listing attributes (no extra `stat` per file as `File.length()` needs), symlinks are never followed,
 * unreadable folders are counted and skipped instead of aborting, and the walk can be cancelled.
 * Written from scratch for this app.
 */
object StorageScan {
    fun scan(
        root: File,
        topFiles: Int = 50,
        isCancelled: () -> Boolean = { false },
        onProgress: (ScanProgress) -> Unit = {},
    ): StorageTree {
        val started = System.nanoTime()
        val rootPath = root.absoluteFile.toPath()
        val stack = ArrayDeque<DirNode>()
        var top: DirNode? = null
        var folders = 0
        var skipped = 0
        var files = 0
        var bytes = 0L
        var lastReport = 0L
        val heap = PriorityQueue<LargeFile>(compareBy { it.bytes })
        val cats = HashMap<FileCategory?, LongArray>()
        fun report(dir: String, force: Boolean = false) {
            val now = System.nanoTime()
            if (force || now - lastReport > 150_000_000L) {
                lastReport = now
                onProgress(ScanProgress(files, folders, bytes, dir))
            }
        }
        Files.walkFileTree(
            rootPath,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (isCancelled()) throw CancellationException("Scan cancelled")
                    val node = DirNode(dir.toString(), dir.fileName?.toString() ?: dir.toString())
                    stack.lastOrNull()?.childList?.add(node)
                    if (top == null) top = node
                    stack.addLast(node)
                    folders++
                    report(node.path)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    // Symlinks (and sockets / pipes) take no space of their own here.
                    if (!attrs.isRegularFile) return FileVisitResult.CONTINUE
                    val size = attrs.size()
                    val node = stack.last()
                    node.bytes += size
                    node.files++
                    files++
                    bytes += size
                    val name = file.fileName.toString().lowercase()
                    val cat = FileCategory.entries.firstOrNull { it.matches(name) }
                    cats.getOrPut(cat) { LongArray(2) }.let { it[0] += size; it[1]++ }
                    if (topFiles > 0 && (heap.size < topFiles || size > heap.peek().bytes)) {
                        heap.add(LargeFile(file.toString(), size, attrs.lastModifiedTime().toMillis()))
                        if (heap.size > topFiles) heap.poll()
                    }
                    if (files and 1023 == 0) {
                        if (isCancelled()) throw CancellationException("Scan cancelled")
                        report(node.path)
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    // An unreadable folder (or a file that vanished mid-scan) is skipped, not fatal.
                    if (file != rootPath) skipped++ else throw exc
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (exc != null && dir != rootPath) skipped++
                    val done = stack.removeLast()
                    stack.lastOrNull()?.let { parent -> parent.bytes += done.bytes; parent.files += done.files }
                    done.childList.sortWith(compareByDescending<DirNode> { it.bytes }.thenBy { it.name })
                    return FileVisitResult.CONTINUE
                }
            },
        )
        val rootNode = top ?: throw IOException("Cannot read ${root.path}")
        report(rootNode.path, force = true)
        return StorageTree(
            root = rootNode,
            largest = heap.sortedByDescending { it.bytes },
            byCategory = cats.mapValues { CategoryTotal(it.value[0], it.value[1].toInt()) },
            folders = folders,
            skipped = skipped,
            elapsedMs = (System.nanoTime() - started) / 1_000_000,
        )
    }

    /**
     * The breakdown of one folder inside a scanned tree, in the same shape as
     * [StorageAnalyzer.analyze]: sub-folder sizes come from the tree (no rescan), loose files are
     * listed directly (one cheap directory read). Returns null when [dir] was not part of the scan.
     */
    fun breakdown(tree: StorageTree, dir: File): FolderBreakdown? {
        val node = tree.node(dir.absoluteFile) ?: return null
        val folderEntries = node.children.map { SizeEntry(File(it.path), true, it.bytes, it.files) }
        val childDirs = node.children.mapTo(HashSet()) { it.name }
        val fileEntries = dir.listFiles().orEmpty()
            .filter { it.name !in childDirs }
            .map { f ->
                val link = Files.isSymbolicLink(f.toPath())
                SizeEntry(f, false, if (link || !f.isFile) 0 else f.length(), 1)
            }
        return StorageAnalyzer.aggregate(dir, folderEntries + fileEntries)
    }
}
