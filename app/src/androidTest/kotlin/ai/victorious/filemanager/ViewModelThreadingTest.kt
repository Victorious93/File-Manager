package ai.victorious.filemanager

import ai.victorious.filemanager.core.fs.FileEntry
import ai.victorious.filemanager.core.fs.FileIo
import ai.victorious.filemanager.ui.FileManagerViewModel
import ai.victorious.filemanager.ui.UiEvent
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.Collections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression: the ViewModel listed folders on the main thread (initial state, openPath,
 * refreshAll, newTab). For /remote/... paths that is a network call and throws
 * NetworkOnMainThreadException; for big local folders it janks / ANRs.
 */
@RunWith(AndroidJUnit4::class)
class ViewModelThreadingTest {
    companion object {
        @BeforeClass @JvmStatic
        fun grant() = Smoke.grantStorage()
    }

    private class RecordingIo(private val d: FileIo) : FileIo by d {
        val mainThreadCalls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        private fun rec(op: String) { if (Looper.myLooper() == Looper.getMainLooper()) mainThreadCalls += op }
        override fun list(path: String): List<FileEntry> { rec("list $path"); return d.list(path) }
        override fun isDirectory(path: String): Boolean { rec("isDirectory $path"); return d.isDirectory(path) }
        override fun exists(path: String): Boolean { rec("exists $path"); return d.exists(path) }
    }

    @Test
    fun navigationNeverTouchesFileIoOnMainThread() {
        val root = Smoke.fixtureRoot("threading")
        File(root, "x").mkdirs()
        File(root, "x/y.txt").writeText("y")
        val io = RecordingIo(Smoke.app.io)
        lateinit var vm: FileManagerViewModel
        val inst = Smoke.instrumentation
        inst.runOnMainSync {
            vm = FileManagerViewModel(Smoke.app, io)
            vm.refreshAll()
            vm.onEvent(UiEvent.Open(0, root.absolutePath))
            vm.onEvent(UiEvent.NewTab(1, File(root, "x").absolutePath))
            vm.onEvent(UiEvent.GoUp(1))
        }
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val s = vm.ui.value
            if (s.left.active.path == root.absolutePath && s.left.active.entries.any { it.name == "x" }) break
            Thread.sleep(50)
        }
        inst.waitForIdleSync()
        assertEquals(root.absolutePath, vm.ui.value.left.active.path)
        assertTrue("left pane listed", vm.ui.value.left.active.entries.any { it.name == "x" })
        assertTrue("FileIo used on main thread: ${io.mainThreadCalls}", io.mainThreadCalls.isEmpty())
        root.deleteRecursively()
    }
}
