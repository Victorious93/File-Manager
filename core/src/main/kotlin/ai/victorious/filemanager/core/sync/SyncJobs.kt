package ai.victorious.filemanager.core.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * A saved, schedulable folder-sync job. Either side may be a local path (connection id null)
 * or a folder on a saved remote connection (SFTP/FTP/SMB/WebDAV/S3/cloud), addressed by
 * [aConnectionId]/[bConnectionId] plus the folder path on that remote.
 */
@Serializable
data class SyncJob(
    val id: String = "",
    val aConnectionId: String? = null,
    val aRoot: String,
    val bConnectionId: String? = null,
    val bRoot: String,
    val direction: SyncDirection = SyncDirection.TWO_WAY,
    val conflict: ConflictPolicy = ConflictPolicy.NEWEST,
    /** Paths present on both sides after the last run; lets two-way sync propagate deletes. */
    val previousBoth: Set<String> = emptySet(),
    /**
     * Size+mtime of each path on both sides right after the last run. Remotes cannot keep the
     * source mtime on upload, so without this every run would see "newer" copies and ping-pong.
     */
    val snapshot: Map<String, PairStamp> = emptyMap(),
    val intervalHours: Long = 24,
    /** Only run on an unmetered (normally Wi-Fi) network. Remote jobs always need a network. */
    val wifiOnly: Boolean = false,
    val requiresCharging: Boolean = false,
    val lastRunEpochMs: Long? = null,
    val lastResult: String? = null,
) {
    val aRemote: Boolean get() = !aConnectionId.isNullOrBlank()
    val bRemote: Boolean get() = !bConnectionId.isNullOrBlank()
    val involvesRemote: Boolean get() = aRemote || bRemote
}

@Serializable
data class PairStamp(val aSize: Long, val aModified: Long, val bSize: Long, val bModified: Long)

/** JSON layout of `folder_sync_jobs.json`: `{ "<jobId>": { ...job... } }`. Reads the older local-only format too. */
object SyncJobStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun decode(text: String): Map<String, SyncJob> {
        if (text.isBlank()) return emptyMap()
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyMap()
        return root.mapNotNull { (key, value) ->
            val obj = (value as? JsonObject) ?: return@mapNotNull null
            val withId = if (obj["id"] == null) JsonObject(obj + ("id" to JsonPrimitive(key))) else obj
            runCatching { json.decodeFromJsonElement(SyncJob.serializer(), withId) }.getOrNull()?.let { key to it }
        }.toMap()
    }

    fun encode(jobs: Map<String, SyncJob>): String = buildJsonObject {
        jobs.forEach { (k, v) -> put(k, json.encodeToJsonElement(SyncJob.serializer(), v)) }
    }.toString()
}

/** Platform-neutral description of when a job may run; the app maps it to WorkManager Constraints. */
data class SyncRunConstraints(
    val network: Network,
    val requiresCharging: Boolean,
    /** Skip runs when the battery is low, for remote jobs that may transfer a lot. */
    val batteryNotLow: Boolean,
) {
    enum class Network { NOT_REQUIRED, CONNECTED, UNMETERED }

    companion object {
        fun forJob(job: SyncJob): SyncRunConstraints = SyncRunConstraints(
            network = when {
                job.involvesRemote && job.wifiOnly -> Network.UNMETERED
                job.involvesRemote -> Network.CONNECTED
                else -> Network.NOT_REQUIRED
            },
            requiresCharging = job.requiresCharging,
            batteryNotLow = job.involvesRemote,
        )
    }
}

/** Runs one job with the shared [FolderSync] engine. Pure: the caller opens the two [SyncFs] sides. */
object SyncJobRunner {
    data class Outcome(val plan: SyncPlan, val job: SyncJob) {
        val summary: String get() =
            "${plan.copyCount} copies, ${plan.deleteCount} deletes, ${plan.conflicts.size} conflicts"
    }

    /**
     * @param trashOnDelete called for deletes instead of a hard delete (local sides go to the app trash);
     *   return false to fall back to deleting through the [SyncFs].
     */
    fun run(
        job: SyncJob,
        a: SyncFs,
        b: SyncFs,
        nowMs: Long = System.currentTimeMillis(),
        trashOnDelete: ((side: Char, relative: String) -> Boolean)? = null,
    ): Outcome {
        val aMap = files(a, job.aRoot)
        val bMap = files(b, job.bRoot).mapValues { (rel, bf) ->
            // Unchanged on both sides since the last run → treat as identical (align mtimes).
            val af = aMap[rel]
            val st = job.snapshot[rel]
            if (af != null && st != null && af.size == st.aSize && af.modifiedEpochMs == st.aModified &&
                bf.size == st.bSize && bf.modifiedEpochMs == st.bModified && af.size == bf.size
            ) bf.copy(modifiedEpochMs = af.modifiedEpochMs) else bf
        }
        val plan = FolderSync.plan(aMap, bMap, job.direction, job.conflict, job.previousBoth)
        FolderSync.apply(
            plan, a, job.aRoot, b, job.bRoot,
            trashOnDelete = { side, rel ->
                val handled = trashOnDelete?.invoke(side, rel) ?: false
                if (!handled) {
                    if (side == 'A') a.delete(job.aRoot, rel) else b.delete(job.bRoot, rel)
                }
            },
        )
        val afterA = files(a, job.aRoot)
        val afterB = files(b, job.bRoot)
        val both = afterA.keys intersect afterB.keys
        val updated = job.copy(
            previousBoth = both,
            snapshot = both.associateWith { rel ->
                val x = afterA.getValue(rel); val y = afterB.getValue(rel)
                PairStamp(x.size, x.modifiedEpochMs, y.size, y.modifiedEpochMs)
            },
            lastRunEpochMs = nowMs,
            lastResult = "OK: ${plan.copyCount} copies, ${plan.deleteCount} deletes, ${plan.conflicts.size} conflicts",
        )
        return Outcome(plan, updated)
    }

    private fun files(fs: SyncFs, root: String): Map<String, SyncFile> =
        fs.listRecursive(root).filter { !it.isDirectory }.associateBy { it.relativePath }
}
