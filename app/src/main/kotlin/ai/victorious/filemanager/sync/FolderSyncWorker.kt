package ai.victorious.filemanager.sync

import ai.victorious.filemanager.FileManagerApplication
import ai.victorious.filemanager.core.sync.ConflictPolicy
import ai.victorious.filemanager.core.sync.FolderSync
import ai.victorious.filemanager.core.sync.SyncDirection
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * Scheduled local↔local folder sync (WorkManager). Remotes (SMB/SFTP/WebDAV/cloud) are planned
 * in the UI using the same [FolderSync] engine with [RemoteSyncFs]; scheduled jobs currently
 * cover filesystem paths stored in the job JSON (extend with connection ids as needed).
 */
class FolderSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB) ?: return Result.failure()
        val file = File(applicationContext.filesDir, "folder_sync_jobs.json")
        val root = JSONObject(runCatching { file.readText() }.getOrDefault("{}"))
        val job = root.optJSONObject(jobId) ?: return Result.failure()
        val aRoot = job.getString("aRoot")
        val bRoot = job.getString("bRoot")
        val direction = SyncDirection.valueOf(job.optString("direction", SyncDirection.TWO_WAY.name))
        val conflict = ConflictPolicy.valueOf(job.optString("conflict", ConflictPolicy.NEWEST.name))
        val a = LocalSyncFs(); val b = LocalSyncFs()
        val aMap = a.listRecursive(aRoot).filter { !it.isDirectory }.associateBy { it.relativePath }
        val bMap = b.listRecursive(bRoot).filter { !it.isDirectory }.associateBy { it.relativePath }
        val prev = job.optJSONArray("previousBoth")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        } ?: emptySet()
        val plan = FolderSync.plan(aMap, bMap, direction, conflict, prev)
        val app = applicationContext as FileManagerApplication
        FolderSync.apply(
            plan, a, aRoot, b, bRoot,
            trashOnDelete = { side, rel ->
                val path = if (side == 'A') File(aRoot, rel).absolutePath else File(bRoot, rel).absolutePath
                runCatching { app.trash.moveToTrash(path) }
                    .onFailure {
                        if (side == 'A') a.delete(aRoot, rel) else b.delete(bRoot, rel)
                    }
            },
        )
        // Update previousBoth snapshot
        val both = (a.listRecursive(aRoot).map { it.relativePath }.toSet() intersect
            b.listRecursive(bRoot).map { it.relativePath }.toSet())
        job.put("previousBoth", org.json.JSONArray(both.toList()))
        root.put(jobId, job)
        file.writeText(root.toString())
        return Result.success()
    }

    companion object {
        const val KEY_JOB = "job_id"
        fun schedule(context: Context, jobId: String, hours: Long) {
            val req = PeriodicWorkRequestBuilder<FolderSyncWorker>(hours.coerceAtLeast(1), TimeUnit.HOURS)
                .setInputData(workDataOf(KEY_JOB to jobId))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("folder_sync_$jobId", ExistingPeriodicWorkPolicy.UPDATE, req)
        }
        fun cancel(context: Context, jobId: String) {
            WorkManager.getInstance(context).cancelUniqueWork("folder_sync_$jobId")
        }
    }
}
