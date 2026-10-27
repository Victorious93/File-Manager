package ai.victorious.filemanager

import ai.victorious.filemanager.core.sync.SyncDirection
import ai.victorious.filemanager.core.sync.SyncJob
import ai.victorious.filemanager.sync.FolderSyncJobs
import ai.victorious.filemanager.sync.FolderSyncWorker
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Manual sync runs in WorkManager (no screen involved), and the job list loads off the main thread. */
@RunWith(AndroidJUnit4::class)
class FolderSyncWorkTest {
    private val ctx get() = Smoke.instrumentation.targetContext

    @After fun tearDown() { FolderSyncJobs.onDiskRead = null }

    @Test fun manualSyncRunsInWorkManagerWithoutAnyScreen() {
        val root = File(ctx.filesDir, "syncwork-" + System.nanoTime())
        val a = File(root, "a").apply { mkdirs() }
        val b = File(root, "b").apply { mkdirs() }
        File(a, "one.txt").writeText("1")
        File(a, "sub").mkdirs(); File(a, "sub/two.txt").writeText("2")
        FolderSyncWorker.runNow(ctx, SyncJob(id = "adhoc", aRoot = a.path, bRoot = b.path, direction = SyncDirection.ONE_WAY_A_TO_B))
        val wm = WorkManager.getInstance(ctx)
        val deadline = System.currentTimeMillis() + 60_000
        var info: WorkInfo? = null
        while (System.currentTimeMillis() < deadline) {
            info = wm.getWorkInfosForUniqueWork(FolderSyncWorker.MANUAL_WORK).get().firstOrNull()
            if (info != null && info.state.isFinished) break
            Thread.sleep(200)
        }
        assertEquals("state=${info?.state} out=${info?.outputData}", WorkInfo.State.SUCCEEDED, info?.state)
        assertEquals("1", File(b, "one.txt").readText())
        assertEquals("2", File(b, "sub/two.txt").readText())
        assertFalse("manual runs are not saved as scheduled jobs", FolderSyncJobs.all(ctx).containsKey("adhoc"))
        root.deleteRecursively()
    }

    @Test fun jobListLoadsOffTheMainThread() {
        val threads = mutableListOf<Thread>()
        FolderSyncJobs.onDiskRead = { synchronized(threads) { threads += it } }
        runBlocking(Dispatchers.Main) { FolderSyncJobs.load(ctx) }
        assertTrue(threads.isNotEmpty())
        assertTrue("read on ${threads.map { it.name }}", threads.none { it == Looper.getMainLooper().thread })
    }
}
