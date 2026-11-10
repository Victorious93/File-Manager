package ai.victorious.filemanager.mypc

import ai.victorious.filemanager.FileManagerApplication
import ai.victorious.filemanager.MainActivity
import ai.victorious.filemanager.core.mypc.HostKeyMismatchException
import ai.victorious.filemanager.core.mypc.HostKeyUnpinnedException
import ai.victorious.filemanager.core.mypc.KeyRejectedException
import ai.victorious.filemanager.core.mypc.LocalTransferSide
import ai.victorious.filemanager.core.mypc.TransferCancelledException
import ai.victorious.filemanager.core.mypc.TransferVerifyException
import ai.victorious.filemanager.core.mypc.TreeTransfer
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One My PC transfer (a file or a folder tree), run by WorkManager as a dataSync foreground
 * service with a progress notification. Interrupted runs are retried with back-off and resume
 * from the `.partial` file; a changed host key or a refused key fails at once and never retries.
 */
class MyPcTransferWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val app get() = applicationContext as FileManagerApplication
    private val notificationId = 0x4D50 + (id.hashCode() and 0x0fff)
    private val icon: Int
        get() = if (inputData.getString(IN_DIRECTION) == MyPcTransfers.Direction.UPLOAD.name) {
            android.R.drawable.stat_sys_upload
        } else {
            android.R.drawable.stat_sys_download
        }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val profileId = inputData.getString(IN_PROFILE) ?: return@withContext fail("Bad job")
        val direction = runCatching { MyPcTransfers.Direction.valueOf(inputData.getString(IN_DIRECTION).orEmpty()) }
            .getOrNull() ?: return@withContext fail("Bad job")
        val source = inputData.getString(IN_SOURCE) ?: return@withContext fail("Bad job")
        val dest = inputData.getString(IN_DEST) ?: return@withContext fail("Bad job")
        val move = inputData.getBoolean(IN_MOVE, false)
        val name = source.trimEnd('/').substringAfterLast('/')
        val pcName = app.myPc.get(profileId)?.name ?: "My PC"
        val label = (if (direction == MyPcTransfers.Direction.DOWNLOAD) "From $pcName: " else "To $pcName: ") + name
        runCatching { setForeground(foregroundInfo(label, 0, 0)) }

        val pc = try {
            app.myPc.openDetached(profileId)
        } catch (e: HostKeyMismatchException) {
            return@withContext fail("Blocked: ${pcName}'s host key changed. Nothing was transferred. Re-pair the PC.")
        } catch (e: HostKeyUnpinnedException) {
            return@withContext fail("Open My PC and confirm ${pcName}'s host key first.")
        } catch (e: KeyRejectedException) {
            return@withContext fail(e.message ?: "Key refused")
        } catch (e: IllegalArgumentException) {
            return@withContext fail(e.message ?: "PC removed")
        } catch (e: IOException) {
            return@withContext retryOrFail("Can't reach $pcName yet: ${e.message}")
        }
        val local = LocalTransferSide()
        val (src, dst) = if (direction == MyPcTransfers.Direction.DOWNLOAD) pc to local else local to pc
        try {
            var lastUpdate = 0L
            val summary = TreeTransfer(src, dst, move, isCancelled = { isStopped }) { done, total, current ->
                val now = System.currentTimeMillis()
                if (now - lastUpdate >= 400 || done == total) {
                    lastUpdate = now
                    setProgressAsync(workDataOf(P_DONE to done, P_TOTAL to total, P_NAME to current, P_LABEL to label))
                    notify(foregroundInfo(label, done, total))
                }
            }.run(source, dest)
            val how = if (summary.hashVerifiedFiles == summary.files) "SHA-256 verified" else "size verified (no sha256sum on the PC)"
            val msg = "${if (move) "Moved" else "Copied"} ${summary.files} file(s), $how" +
                if (summary.resumedFiles > 0) ", ${summary.resumedFiles} resumed" else ""
            done(label, msg)
            Result.success(workDataOf(OUT_MESSAGE to msg, P_LABEL to label, P_DONE to summary.bytes, P_TOTAL to summary.bytes))
        } catch (e: TransferCancelledException) {
            // A cancel is the user's choice: remove the half-written file instead of leaving litter.
            // (Drops and retries keep it, which is what makes them resume.)
            e.partialPath?.let { runCatching { dst.delete(it) } }
            fail("Cancelled. Files already finished were kept; the unfinished one was removed.")
        } catch (e: TransferVerifyException) {
            fail(e.message ?: "Verify failed")
        } catch (e: IOException) {
            retryOrFail("Interrupted: ${e.message}. Will resume from the .partial file.")
        } finally {
            pc.close()
        }
    }

    private fun fail(msg: String): Result = Result.failure(workDataOf(OUT_MESSAGE to msg))

    private fun retryOrFail(msg: String): Result =
        if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else fail("$msg (gave up after $MAX_ATTEMPTS tries)")

    private fun channel() {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "My PC transfers", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun foregroundInfo(label: String, done: Long, total: Long): ForegroundInfo {
        channel()
        val pct = if (total > 0) ((done * 100) / total).toInt() else 0
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(icon)
            .setContentTitle(label)
            .setContentText(if (total > 0) "${human(done)} of ${human(total)}" else "Connecting…")
            .setProgress(100, pct, total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .addAction(0, "Cancel", androidx.work.WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, n)
        }
    }

    private fun notify(info: ForegroundInfo) {
        runCatching { applicationContext.getSystemService(NotificationManager::class.java).notify(info.notificationId, info.notification) }
    }

    private fun done(label: String, msg: String) {
        channel()
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(icon)
            .setContentTitle(label)
            .setContentText(msg)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        runCatching { applicationContext.getSystemService(NotificationManager::class.java).notify(notificationId + 0x1000, n) }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        applicationContext,
        0,
        Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val IN_PROFILE = "profile"
        const val IN_DIRECTION = "direction"
        const val IN_SOURCE = "source"
        const val IN_DEST = "dest"
        const val IN_MOVE = "move"
        const val P_DONE = "done"
        const val P_TOTAL = "total"
        const val P_NAME = "name"
        const val P_LABEL = "label"
        const val OUT_MESSAGE = "message"
        const val CHANNEL = "mypc_transfers"
        const val MAX_ATTEMPTS = 6

        fun human(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val units = listOf("KB", "MB", "GB", "TB")
            var v = bytes / 1024.0
            var i = 0
            while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
            return String.format(java.util.Locale.US, "%.1f %s", v, units[i])
        }
    }
}
