package ai.victorious.filemanager.sync

import ai.victorious.filemanager.FileManagerApplication
import ai.victorious.filemanager.core.net.RemoteBackend
import ai.victorious.filemanager.core.sync.RemoteSyncFs
import ai.victorious.filemanager.core.sync.SyncFs
import ai.victorious.filemanager.core.sync.SyncJob
import ai.victorious.filemanager.core.sync.SyncJobRules
import ai.victorious.filemanager.core.sync.SyncJobRunner
import ai.victorious.filemanager.core.sync.SyncSafetyStop
import ai.victorious.filemanager.core.sync.SyncJobStore
import ai.victorious.filemanager.core.sync.SyncRunConstraints
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Saved sync jobs in `filesDir/folder_sync_jobs.json` (see [SyncJobStore]). */
object FolderSyncJobs {
    private val lock = Any()
    private fun file(context: Context) = File(context.filesDir, "folder_sync_jobs.json")

    fun all(context: Context): Map<String, SyncJob> = synchronized(lock) {
        SyncJobStore.decode(runCatching { file(context).readText() }.getOrDefault(""))
    }

    /** One sync at a time per process, so "Run sync" and a scheduled run never write the same files at once. */
    val runLock = Mutex()

    fun put(context: Context, job: SyncJob) = update(context) { it + (job.id to job) }

    fun remove(context: Context, id: String) = update(context) { it - id }

    /** Saves a finished run only if the job still exists, keeping any edits made meanwhile. */
    fun recordRun(context: Context, ran: SyncJob) = update(context) { jobs ->
        val merged = SyncJobStore.recordRun(jobs[ran.id], ran)
        if (merged == null) jobs else jobs + (ran.id to merged)
    }

    private fun update(context: Context, change: (Map<String, SyncJob>) -> Map<String, SyncJob>) = synchronized(lock) {
        val f = file(context)
        val jobs = change(SyncJobStore.decode(runCatching { f.readText() }.getOrDefault("")))
        val tmp = File(f.path + ".tmp")
        tmp.writeText(SyncJobStore.encode(jobs))
        if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
    }
}

/**
 * Scheduled folder sync (WorkManager) for local folders and saved remotes
 * (SFTP/FTP/SMB/WebDAV/S3/cloud). Uses the same [ai.victorious.filemanager.core.sync.FolderSync]
 * engine as the screen. Remote jobs wait for a network (Wi-Fi only if asked) and can wait for
 * charging. Local deletes go to the app trash; remote deletes are done on the server.
 */
class FolderSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB) ?: return Result.failure()
        val job = FolderSyncJobs.all(applicationContext)[jobId] ?: return Result.failure()
        SyncJobRules.problem(job)?.let { reason ->
            FolderSyncJobs.recordRun(applicationContext, job.copy(lastRunEpochMs = System.currentTimeMillis(), lastResult = "Not run: $reason"))
            return Result.failure()
        }
        return FolderSyncJobs.runLock.withLock { runJob(job) }
    }

    private fun runJob(job: SyncJob): Result {
        val app = applicationContext as FileManagerApplication
        val opened = mutableListOf<RemoteBackend>()
        fun side(connectionId: String?): SyncFs =
            if (connectionId.isNullOrBlank()) LocalSyncFs()
            else RemoteSyncFs(app.connections.openDetached(connectionId).also { opened += it })
        return try {
            val a = side(job.aConnectionId)
            val b = side(job.bConnectionId)
            val outcome = SyncJobRunner.run(job, a, b, trashOnDelete = { s, rel ->
                val localRoot = when {
                    s == 'A' && !job.aRemote -> job.aRoot
                    s == 'B' && !job.bRemote -> job.bRoot
                    else -> null
                } ?: return@run false
                runCatching { app.trash.moveToTrash(File(localRoot, rel).absolutePath) }.isSuccess
            })
            FolderSyncJobs.recordRun(applicationContext, outcome.job)
            Result.success(workDataOf(KEY_SUMMARY to outcome.summary))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val transient = e !is SyncSafetyStop && (e is IOException || e.cause is IOException ||
                e.javaClass.name.startsWith("com.jcraft.jsch") || e.javaClass.name.contains("Timeout"))
            FolderSyncJobs.recordRun(applicationContext, job.copy(lastRunEpochMs = System.currentTimeMillis(),
                lastResult = "Failed: ${e.message ?: e.javaClass.simpleName}"))
            if (transient && runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        } finally {
            opened.forEach { runCatching { it.close() } }
        }
    }

    companion object {
        const val KEY_JOB = "job_id"
        const val KEY_SUMMARY = "summary"
        private const val MAX_RETRIES = 3

        fun uniqueName(jobId: String) = "folder_sync_$jobId"

        fun constraintsFor(job: SyncJob): Constraints {
            val c = SyncRunConstraints.forJob(job)
            return Constraints.Builder()
                .setRequiredNetworkType(
                    when (c.network) {
                        SyncRunConstraints.Network.NOT_REQUIRED -> NetworkType.NOT_REQUIRED
                        SyncRunConstraints.Network.CONNECTED -> NetworkType.CONNECTED
                        SyncRunConstraints.Network.UNMETERED -> NetworkType.UNMETERED
                    },
                )
                .setRequiresCharging(c.requiresCharging)
                .setRequiresBatteryNotLow(c.batteryNotLow)
                .build()
        }

        /** Saves [job] and (re)schedules it every [SyncJob.intervalHours] with its constraints. */
        fun schedule(context: Context, job: SyncJob) {
            FolderSyncJobs.put(context, job)
            val req = PeriodicWorkRequestBuilder<FolderSyncWorker>(job.intervalHours.coerceAtLeast(1), TimeUnit.HOURS)
                .setInputData(workDataOf(KEY_JOB to job.id))
                .setConstraints(constraintsFor(job))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(uniqueName(job.id), ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun cancel(context: Context, jobId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(uniqueName(jobId))
            FolderSyncJobs.remove(context, jobId)
        }
    }
}
