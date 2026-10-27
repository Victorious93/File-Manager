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

    /**
     * Saves a finished run onto the job as it is *now* in the store. Returns null when the job was
     * removed while running (do not resurrect it). Settings edited during the run are kept; the
     * sync history is dropped if the folders changed, because it describes the old folders.
     */
    fun recordRun(current: SyncJob?, ran: SyncJob): SyncJob? {
        current ?: return null
        val sameFolders = current.aConnectionId == ran.aConnectionId && current.aRoot == ran.aRoot &&
            current.bConnectionId == ran.bConnectionId && current.bRoot == ran.bRoot
        return current.copy(
            previousBoth = if (sameFolders) ran.previousBoth else emptySet(),
            snapshot = if (sameFolders) ran.snapshot else emptyMap(),
            lastRunEpochMs = ran.lastRunEpochMs,
            lastResult = ran.lastResult,
        )
    }

    fun encode(jobs: Map<String, SyncJob>): String = buildJsonObject {
        jobs.forEach { (k, v) -> put(k, json.encodeToJsonElement(SyncJob.serializer(), v)) }
    }.toString()
}

/** The run refused to do something destructive; retrying will not help until the user acts. */
class SyncSafetyStop(message: String) : java.io.IOException(message)

/** Validation shared by the job editor and the worker. */
object SyncJobRules {
    /** A human-readable reason the job cannot run, or null when it is fine. */
    fun problem(job: SyncJob): String? {
        if (job.aRoot.isBlank() || job.bRoot.isBlank()) return "Pick both folders."
        val sameStore = job.aConnectionId.orEmpty() == job.bConnectionId.orEmpty()
        if (!sameStore) return null
        val a = norm(job.aRoot); val b = norm(job.bRoot)
        if (a == b) return "Both sides are the same folder."
        if (b.startsWith("$a/") || a.startsWith("$b/") || a == "/" || b == "/") {
            return "One folder is inside the other; syncing would copy the folder into itself on every run."
        }
        return null
    }

    private fun norm(p: String): String = p.trim().replace(Regex("/+"), "/").trimEnd('/').ifEmpty { "/" }
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
        // A path that was on both sides last run, is now missing on one side and is unchanged on
        // the other was deleted on purpose: mirror the delete instead of copying it back.
        val twoWayDeletes = job.previousBoth.filterTo(HashSet()) { rel ->
            val st = job.snapshot[rel] ?: return@filterTo false
            val af = aMap[rel]; val bf = bMap[rel]
            when {
                af != null && bf == null -> af.size == st.aSize && af.modifiedEpochMs == st.aModified
                af == null && bf != null -> bf.size == st.bSize && bf.modifiedEpochMs == st.bModified
                else -> false
            }
        }
        val plan = FolderSync.plan(aMap, bMap, job.direction, job.conflict, job.previousBoth, twoWayDeletes)
        // Safety net: a side that suddenly lists no files at all (unmounted SD card, wrong or
        // emptied remote folder, server glitch) must never be mirrored as "delete everything".
        // Deleting the single synced file is an ordinary edit, so the net needs at least two.
        if (plan.deleteCount > 1 && job.previousBoth.size > 1 && (aMap.isEmpty() || bMap.isEmpty())) {
            throw SyncSafetyStop(
                "Sync stopped: one side is empty but was not last time, so nothing was deleted. " +
                    "Check the folder is available, or recreate the job to start fresh.",
            )
        }
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
