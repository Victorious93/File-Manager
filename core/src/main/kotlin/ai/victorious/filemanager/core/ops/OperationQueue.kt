package ai.victorious.filemanager.core.ops

import ai.victorious.filemanager.core.fs.FileIo
import ai.victorious.filemanager.core.path.PathHelpers
import ai.victorious.filemanager.core.trash.TrashStore
import java.util.UUID

/**
 * Sequential file-operation queue with undo. Copy/move/delete/rename run one at
 * a time; a successful op pushes an inverse onto the undo stack.
 */
class OperationQueue(
    private val io: FileIo,
    private val trash: TrashStore,
    private val ids: () -> String = { UUID.randomUUID().toString() },
) {
    private val pending = ArrayDeque<FileOperation>()
    private val undoStack = ArrayDeque<CompletedOp>()
    private val history = mutableListOf<OpResult>()
    private var running: FileOperation? = null

    fun enqueue(kind: OpKind, sources: List<String>, destinationDir: String? = null, newName: String? = null): FileOperation {
        require(sources.isNotEmpty()) { "No sources" }
        val op = FileOperation(
            id = ids(),
            kind = kind,
            sources = sources.map(PathHelpers::normalize),
            destinationDir = destinationDir?.let(PathHelpers::normalize),
            newName = newName,
        )
        validate(op)
        pending.addLast(op)
        return op
    }

    fun snapshot(): QueueSnapshot = QueueSnapshot(
        pending = pending.toList(),
        running = running,
        history = history.toList(),
        canUndo = undoStack.isNotEmpty(),
    )

    fun processAll(): List<OpResult> {
        val results = mutableListOf<OpResult>()
        while (pending.isNotEmpty()) {
            results += processNext()
        }
        return results
    }

    fun processNext(): OpResult {
        val op = pending.removeFirstOrNull() ?: return OpResult("", OpStatus.CANCELLED, "Queue empty")
        running = op
        val result = try {
            val completed = execute(op)
            undoStack.addLast(completed)
            OpResult(op.id, OpStatus.SUCCEEDED, successMessage(op, completed), completed)
        } catch (t: Throwable) {
            OpResult(op.id, OpStatus.FAILED, t.message ?: t::class.simpleName ?: "failed")
        } finally {
            running = null
        }
        history += result
        return result
    }

    fun undoLast(): OpResult {
        val completed = undoStack.removeLastOrNull()
            ?: return OpResult("", OpStatus.FAILED, "Nothing to undo")
        val result = try {
            undo(completed)
            OpResult(completed.operation.id, OpStatus.UNDONE, "Undid ${completed.operation.kind.name.lowercase()}")
        } catch (t: Throwable) {
            undoStack.addLast(completed)
            OpResult(completed.operation.id, OpStatus.FAILED, t.message ?: "Undo failed")
        }
        history += result
        return result
    }

    fun clearUndo() {
        undoStack.clear()
    }

    private fun validate(op: FileOperation) {
        when (op.kind) {
            OpKind.COPY, OpKind.MOVE -> {
                val dest = op.destinationDir ?: throw IllegalArgumentException("Destination required")
                if (!io.isDirectory(dest)) throw IllegalArgumentException("Destination is not a directory: $dest")
                for (src in op.sources) {
                    if (!io.exists(src)) throw IllegalArgumentException("Missing: $src")
                    if (PathHelpers.isSelfOrDescendant(dest, src)) {
                        throw IllegalArgumentException("Cannot ${op.kind.name.lowercase()} a folder into itself")
                    }
                }
            }
            OpKind.DELETE -> op.sources.forEach {
                if (!io.exists(it)) throw IllegalArgumentException("Missing: $it")
            }
            OpKind.RENAME -> {
                require(op.sources.size == 1) { "Rename takes one path" }
                val name = op.newName ?: throw IllegalArgumentException("New name required")
                require(PathHelpers.isValidFileName(name)) { "Invalid name: $name" }
                if (!io.exists(op.sources.first())) throw IllegalArgumentException("Missing: ${op.sources.first()}")
            }
        }
    }

    private fun execute(op: FileOperation): CompletedOp = when (op.kind) {
        OpKind.COPY -> copyOp(op)
        OpKind.MOVE -> moveOp(op)
        OpKind.DELETE -> deleteOp(op)
        OpKind.RENAME -> renameOp(op)
    }

    private fun copyOp(op: FileOperation): CompletedOp {
        val destDir = op.destinationDir!!
        val created = mutableListOf<String>()
        val names = io.childNames(destDir).toMutableSet()
        for (src in op.sources) {
            val desired = PathHelpers.fileName(src)
            val unique = PathHelpers.uniqueChildName(names, desired)
            names += unique
            val dest = PathHelpers.join(destDir, unique)
            io.copy(src, dest)
            created += dest
        }
        return CompletedOp(op, createdPaths = created)
    }

    private fun moveOp(op: FileOperation): CompletedOp {
        val destDir = op.destinationDir!!
        val pairs = mutableListOf<Pair<String, String>>()
        val names = io.childNames(destDir).toMutableSet()
        for (src in op.sources) {
            val desired = PathHelpers.fileName(src)
            val unique = PathHelpers.uniqueChildName(names, desired)
            names += unique
            val dest = PathHelpers.join(destDir, unique)
            io.move(src, dest)
            pairs += src to dest
        }
        return CompletedOp(op, movedPairs = pairs)
    }

    private fun deleteOp(op: FileOperation): CompletedOp {
        val ids = op.sources.map { trash.moveToTrash(it) }
        return CompletedOp(op, trashIds = ids)
    }

    private fun renameOp(op: FileOperation): CompletedOp {
        val src = op.sources.first()
        val parent = PathHelpers.parentOf(src) ?: throw IllegalArgumentException("Cannot rename root")
        val dest = PathHelpers.join(parent, op.newName!!)
        if (io.exists(dest)) throw IllegalArgumentException("Already exists: ${op.newName}")
        io.rename(src, dest)
        return CompletedOp(op, renamedFrom = src, renamedTo = dest)
    }

    private fun undo(completed: CompletedOp) {
        when (completed.operation.kind) {
            OpKind.COPY -> completed.createdPaths.asReversed().forEach { io.deleteRecursively(it) }
            OpKind.MOVE -> completed.movedPairs.asReversed().forEach { (from, to) ->
                val parent = PathHelpers.parentOf(from) ?: throw IllegalStateException("Cannot undo move of root")
                io.mkdirs(parent)
                val name = PathHelpers.fileName(from)
                val dest = uniqueOrExact(parent, name)
                io.move(to, dest)
            }
            OpKind.DELETE -> completed.trashIds.asReversed().forEach { trash.restore(it) }
            OpKind.RENAME -> {
                val from = completed.renamedTo ?: return
                val to = completed.renamedFrom ?: return
                io.rename(from, to)
            }
        }
    }

    private fun uniqueOrExact(parent: String, name: String): String {
        val unique = PathHelpers.uniqueChildName(io.childNames(parent), name)
        return PathHelpers.join(parent, unique)
    }

    private fun successMessage(op: FileOperation, completed: CompletedOp): String = when (op.kind) {
        OpKind.COPY -> "Copied ${completed.createdPaths.size} item(s)"
        OpKind.MOVE -> "Moved ${completed.movedPairs.size} item(s)"
        OpKind.DELETE -> "Moved ${completed.trashIds.size} item(s) to trash"
        OpKind.RENAME -> "Renamed to ${op.newName}"
    }
}
