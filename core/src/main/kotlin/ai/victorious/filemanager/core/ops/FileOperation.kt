package ai.victorious.filemanager.core.ops

enum class OpKind { COPY, MOVE, DELETE, RENAME, BATCH_RENAME, CREATE_FOLDER, CREATE_FILE }

enum class OpStatus { QUEUED, RUNNING, SUCCEEDED, FAILED, UNDONE, CANCELLED }

data class FileOperation(
    val id: String,
    val kind: OpKind,
    val sources: List<String>,
    val destinationDir: String? = null,
    val newName: String? = null,
    /** BATCH_RENAME only: (old path, new path) pairs within the same directory. */
    val renames: List<Pair<String, String>> = emptyList(),
)

data class CompletedOp(
    val operation: FileOperation,
    val createdPaths: List<String> = emptyList(),
    val movedPairs: List<Pair<String, String>> = emptyList(),
    val trashIds: List<String> = emptyList(),
    val renamedFrom: String? = null,
    val renamedTo: String? = null,
)

data class OpResult(
    val operationId: String,
    val status: OpStatus,
    val message: String,
    val completed: CompletedOp? = null,
)

data class QueueSnapshot(
    val pending: List<FileOperation>,
    val running: FileOperation?,
    val history: List<OpResult>,
    val canUndo: Boolean,
)
