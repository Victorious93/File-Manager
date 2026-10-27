package ai.victorious.filemanager.core.sync

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

enum class SyncDirection { ONE_WAY_A_TO_B, ONE_WAY_B_TO_A, TWO_WAY }

enum class ConflictPolicy { KEEP_BOTH, NEWEST, ASK }

data class SyncFile(
    val relativePath: String,
    val size: Long,
    val modifiedEpochMs: Long,
    val isDirectory: Boolean = false,
)

/** Minimal filesystem view used by the sync planner (local, SMB/SFTP/WebDAV, or cloud via adapters). */
interface SyncFs {
    fun listRecursive(root: String): List<SyncFile>
    fun read(root: String, relative: String): ByteArray
    fun write(root: String, relative: String, bytes: ByteArray, modifiedEpochMs: Long = System.currentTimeMillis())
    fun delete(root: String, relative: String)
    fun mkdir(root: String, relative: String)
    fun exists(root: String, relative: String): Boolean
}

enum class SyncActionKind { COPY_A_TO_B, COPY_B_TO_A, DELETE_A, DELETE_B, KEEP_BOTH_A, KEEP_BOTH_B, SKIP_CONFLICT }

data class SyncAction(
    val kind: SyncActionKind,
    val relativePath: String,
    val detail: String = "",
)

data class SyncPlan(
    val actions: List<SyncAction>,
    val conflicts: List<String>,
) {
    val copyCount get() = actions.count { it.kind == SyncActionKind.COPY_A_TO_B || it.kind == SyncActionKind.COPY_B_TO_A }
    val deleteCount get() = actions.count { it.kind == SyncActionKind.DELETE_A || it.kind == SyncActionKind.DELETE_B }
}

/**
 * Folder sync planner/executor. Deletes never erase immediately on the protected side when a
 * [trashHook] is provided — they go through trash first (safety rule).
 */
object FolderSync {
    fun plan(
        aFiles: Map<String, SyncFile>,
        bFiles: Map<String, SyncFile>,
        direction: SyncDirection,
        conflict: ConflictPolicy,
        /** Relative paths previously seen on both sides; used to detect deletions. */
        previousBoth: Set<String> = emptySet(),
        /**
         * TWO_WAY only: paths that were on both sides last time and are unchanged on the side that
         * still has them, so a copy missing on the other side was deleted there and the delete
         * should be mirrored (instead of copying the file back). Callers without a per-file
         * snapshot pass nothing and keep the safe copy-back behaviour.
         */
        twoWayDeletes: Set<String> = emptySet(),
    ): SyncPlan {
        val actions = mutableListOf<SyncAction>()
        val conflicts = mutableListOf<String>()
        val all = (aFiles.keys + bFiles.keys).toSortedSet()
        for (rel in all) {
            if (rel.endsWith("/") || aFiles[rel]?.isDirectory == true || bFiles[rel]?.isDirectory == true) {
                // Directories are created implicitly on write; skip as actions.
                continue
            }
            val a = aFiles[rel]
            val b = bFiles[rel]
            when {
                a != null && b == null -> when (direction) {
                    SyncDirection.ONE_WAY_B_TO_A -> {
                        if (rel in previousBoth) actions += SyncAction(SyncActionKind.DELETE_A, rel, "deleted on B")
                    }
                    SyncDirection.TWO_WAY ->
                        actions += if (rel in previousBoth && rel in twoWayDeletes) SyncAction(SyncActionKind.DELETE_A, rel, "deleted on B")
                        else SyncAction(SyncActionKind.COPY_A_TO_B, rel)
                    SyncDirection.ONE_WAY_A_TO_B ->
                        actions += SyncAction(SyncActionKind.COPY_A_TO_B, rel)
                }
                b != null && a == null -> when (direction) {
                    SyncDirection.ONE_WAY_A_TO_B -> {
                        if (rel in previousBoth) actions += SyncAction(SyncActionKind.DELETE_B, rel, "deleted on A")
                    }
                    SyncDirection.TWO_WAY ->
                        actions += if (rel in previousBoth && rel in twoWayDeletes) SyncAction(SyncActionKind.DELETE_B, rel, "deleted on A")
                        else SyncAction(SyncActionKind.COPY_B_TO_A, rel)
                    SyncDirection.ONE_WAY_B_TO_A ->
                        actions += SyncAction(SyncActionKind.COPY_B_TO_A, rel)
                }
                a != null && b != null -> {
                    if (sameContentHint(a, b)) continue
                    when (direction) {
                        SyncDirection.ONE_WAY_A_TO_B ->
                            actions += SyncAction(SyncActionKind.COPY_A_TO_B, rel, "overwrite B")
                        SyncDirection.ONE_WAY_B_TO_A ->
                            actions += SyncAction(SyncActionKind.COPY_B_TO_A, rel, "overwrite A")
                        SyncDirection.TWO_WAY -> when (conflict) {
                            ConflictPolicy.NEWEST -> {
                                if (a.modifiedEpochMs >= b.modifiedEpochMs)
                                    actions += SyncAction(SyncActionKind.COPY_A_TO_B, rel, "newer A")
                                else
                                    actions += SyncAction(SyncActionKind.COPY_B_TO_A, rel, "newer B")
                            }
                            ConflictPolicy.KEEP_BOTH -> {
                                actions += SyncAction(SyncActionKind.KEEP_BOTH_A, rel, "conflict")
                                actions += SyncAction(SyncActionKind.KEEP_BOTH_B, rel, "conflict")
                                conflicts += rel
                            }
                            ConflictPolicy.ASK -> {
                                actions += SyncAction(SyncActionKind.SKIP_CONFLICT, rel, "ask user")
                                conflicts += rel
                            }
                        }
                    }
                }
            }
        }
        return SyncPlan(actions, conflicts.distinct())
    }

    /** Cheap equality hint: same size and identical mtime (callers may refine with hashes later). */
    private fun sameContentHint(a: SyncFile, b: SyncFile): Boolean =
        a.size == b.size && a.modifiedEpochMs == b.modifiedEpochMs

    fun conflictCopyName(relative: String, sideLabel: String): String {
        val dot = relative.lastIndexOf('.')
        val slash = relative.lastIndexOf('/')
        return if (dot > slash) {
            relative.substring(0, dot) + " (conflict $sideLabel)" + relative.substring(dot)
        } else relative + " (conflict $sideLabel)"
    }

    /**
     * Apply [plan]. When [dryRun] is true, returns the same plan without I/O.
     * [trashOnDelete] if non-null is called instead of hard-delete (deletes-go-to-trash rule).
     */
    fun apply(
        plan: SyncPlan,
        a: SyncFs,
        aRoot: String,
        b: SyncFs,
        bRoot: String,
        dryRun: Boolean = false,
        trashOnDelete: ((side: Char, relative: String) -> Unit)? = null,
        resolutions: Map<String, SyncActionKind> = emptyMap(), // for ASK conflicts
    ): SyncPlan {
        if (dryRun) return plan
        // List each side at most once (not once per copied file: that was O(n²) and, on a remote,
        // a full recursive network walk for every file).
        val aTimes by lazy { a.listRecursive(aRoot).associate { it.relativePath to it.modifiedEpochMs } }
        val bTimes by lazy { b.listRecursive(bRoot).associate { it.relativePath to it.modifiedEpochMs } }
        for (action in plan.actions) {
            val kind = if (action.kind == SyncActionKind.SKIP_CONFLICT)
                resolutions[action.relativePath] ?: continue
            else action.kind
            when (kind) {
                SyncActionKind.COPY_A_TO_B -> {
                    val bytes = a.read(aRoot, action.relativePath)
                    ensureParent(b, bRoot, action.relativePath)
                    b.write(bRoot, action.relativePath, bytes, aTimes[action.relativePath] ?: System.currentTimeMillis())
                }
                SyncActionKind.COPY_B_TO_A -> {
                    val bytes = b.read(bRoot, action.relativePath)
                    ensureParent(a, aRoot, action.relativePath)
                    a.write(aRoot, action.relativePath, bytes, bTimes[action.relativePath] ?: System.currentTimeMillis())
                }
                SyncActionKind.DELETE_A -> {
                    if (trashOnDelete != null) trashOnDelete('A', action.relativePath)
                    else a.delete(aRoot, action.relativePath)
                }
                SyncActionKind.DELETE_B -> {
                    if (trashOnDelete != null) trashOnDelete('B', action.relativePath)
                    else b.delete(bRoot, action.relativePath)
                }
                SyncActionKind.KEEP_BOTH_A -> {
                    val bytes = a.read(aRoot, action.relativePath)
                    val name = conflictCopyName(action.relativePath, "A")
                    ensureParent(b, bRoot, name)
                    b.write(bRoot, name, bytes)
                }
                SyncActionKind.KEEP_BOTH_B -> {
                    val bytes = b.read(bRoot, action.relativePath)
                    val name = conflictCopyName(action.relativePath, "B")
                    ensureParent(a, aRoot, name)
                    a.write(aRoot, name, bytes)
                }
                SyncActionKind.SKIP_CONFLICT -> Unit
            }
        }
        return plan
    }

    private fun ensureParent(fs: SyncFs, root: String, relative: String) {
        val parent = relative.substringBeforeLast('/', missingDelimiterValue = "")
        if (parent.isNotEmpty() && !fs.exists(root, parent)) fs.mkdir(root, parent)
    }
}

/** In-memory SyncFs for tests. */
class MemorySyncFs : SyncFs {
    private data class Node(var bytes: ByteArray, var modified: Long, var isDir: Boolean = false)
    private val files = linkedMapOf<String, Node>()

    fun seed(rel: String, content: String, modified: Long = 1_000L) {
        files[rel] = Node(content.toByteArray(), modified)
    }

    override fun listRecursive(root: String): List<SyncFile> =
        files.map { (k, v) -> SyncFile(k, v.bytes.size.toLong(), v.modified, v.isDir) }

    override fun read(root: String, relative: String): ByteArray =
        files[relative]?.bytes ?: error("missing $relative")

    override fun write(root: String, relative: String, bytes: ByteArray, modifiedEpochMs: Long) {
        files[relative] = Node(bytes, modifiedEpochMs)
    }

    override fun delete(root: String, relative: String) { files.remove(relative) }

    override fun mkdir(root: String, relative: String) {
        files[relative] = Node(ByteArray(0), System.currentTimeMillis(), isDir = true)
    }

    override fun exists(root: String, relative: String): Boolean = relative in files
}

/** Adapts [ai.victorious.filemanager.core.net.RemoteBackend] to [SyncFs]. */
class RemoteSyncFs(private val remote: ai.victorious.filemanager.core.net.RemoteBackend) : SyncFs {
    override fun listRecursive(root: String): List<SyncFile> {
        val out = mutableListOf<SyncFile>()
        fun walk(path: String, relBase: String) {
            for (e in remote.list(path)) {
                val name = e.name
                val rel = if (relBase.isEmpty()) name else "$relBase/$name"
                val childPath = path.trimEnd('/') + "/" + name
                if (e.isDirectory) {
                    out += SyncFile(rel, 0, e.modifiedEpochMs, isDirectory = true)
                    walk(childPath, rel)
                } else {
                    out += SyncFile(rel, e.sizeBytes, e.modifiedEpochMs)
                }
            }
        }
        walk(root, "")
        return out
    }

    override fun read(root: String, relative: String): ByteArray =
        remote.openInput(join(root, relative)).use { it.readBytes() }

    override fun write(root: String, relative: String, bytes: ByteArray, modifiedEpochMs: Long) {
        remote.openOutput(join(root, relative)).use { it.write(bytes) }
    }

    override fun delete(root: String, relative: String) = remote.delete(join(root, relative))

    override fun mkdir(root: String, relative: String) {
        val parts = relative.split('/').filter { it.isNotEmpty() }
        var cur = root.trimEnd('/')
        for (p in parts) {
            cur = "$cur/$p"
            if (!remote.exists(cur)) remote.mkdir(cur)
        }
    }

    override fun exists(root: String, relative: String): Boolean = remote.exists(join(root, relative))

    private fun join(root: String, relative: String): String =
        if (relative.isEmpty()) root else root.trimEnd('/') + "/" + relative.trimStart('/')
}
