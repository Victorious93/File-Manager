package ai.victorious.filemanager.mypc

import ai.victorious.filemanager.core.mypc.MyPcIds
import ai.victorious.filemanager.core.mypc.TransferNames
import ai.victorious.filemanager.core.net.RemotePaths
import ai.victorious.filemanager.core.path.PathHelpers
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Turns dual-pane copy/move between this phone and My PC into background transfer jobs. */
object MyPcTransfers {
    const val TAG = "mypc-transfer"

    enum class Direction { DOWNLOAD, UPLOAD }

    data class Job(val profileId: String, val direction: Direction, val source: String, val dest: String, val move: Boolean)

    /**
     * Plans jobs when every source is on one My PC and the destination is a phone folder
     * (download), or every source is on the phone and the destination is on My PC (upload).
     * Returns null for anything else (it stays a normal file operation).
     */
    fun plan(sources: List<String>, destDir: String, move: Boolean, repo: MyPcRepository): List<Job>? {
        if (sources.isEmpty()) return null
        val destPc = pcOf(destDir)
        val srcPcs = sources.map(::pcOf).toSet()
        return when {
            destPc == null && srcPcs.size == 1 && srcPcs.first() != null && !RemotePaths.isRemote(destDir) -> {
                val pid = srcPcs.first()!!
                sources.map { s ->
                    val remote = RemotePaths.remoteRelative(s)
                    val name = remote.trimEnd('/').substringAfterLast('/')
                    val dest = TransferNames.unique(destDir, name, PathHelpers::join) { File(it).exists() }
                    Job(pid, Direction.DOWNLOAD, remote, dest, move)
                }
            }
            destPc != null && srcPcs == setOf(null) && sources.none(RemotePaths::isRemote) -> {
                val backend = repo.connect(destPc)
                val remoteDir = RemotePaths.remoteRelative(destDir)
                sources.map { s ->
                    val name = File(s).name
                    val dest = TransferNames.unique(remoteDir, name, { d, n -> if (d == "/") "/$n" else "${d.trimEnd('/')}/$n" }) { backend.exists(it) }
                    Job(destPc, Direction.UPLOAD, s, dest, move)
                }
            }
            else -> null
        }
    }

    /** Cheap check (no network): phone -> one PC, or one PC -> phone. PC -> PC stays a normal file operation. */
    fun isPhonePcTransfer(sources: List<String>, destDir: String): Boolean {
        if (sources.isEmpty()) return false
        val destPc = pcOf(destDir)
        val srcPcs = sources.map(::pcOf).toSet()
        return if (destPc == null) {
            srcPcs.size == 1 && srcPcs.first() != null && !RemotePaths.isRemote(destDir)
        } else {
            srcPcs == setOf(null) && sources.none(RemotePaths::isRemote)
        }
    }

    private fun pcOf(path: String): String? =
        if (RemotePaths.isRemote(path)) RemotePaths.connectionId(path)?.let(MyPcIds::profileIdOf) else null

    fun enqueue(context: Context, job: Job): UUID {
        val req = OneTimeWorkRequestBuilder<MyPcTransferWorker>()
            .setInputData(
                workDataOf(
                    MyPcTransferWorker.IN_PROFILE to job.profileId,
                    MyPcTransferWorker.IN_DIRECTION to job.direction.name,
                    MyPcTransferWorker.IN_SOURCE to job.source,
                    MyPcTransferWorker.IN_DEST to job.dest,
                    MyPcTransferWorker.IN_MOVE to job.move,
                ),
            )
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .addTag(TAG)
            .addTag("$TAG-${job.profileId}")
            .build()
        WorkManager.getInstance(context).enqueue(req)
        return req.id
    }
}
