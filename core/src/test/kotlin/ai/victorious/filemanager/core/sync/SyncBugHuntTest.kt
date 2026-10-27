package ai.victorious.filemanager.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** Regression tests for bugs found in the bug-hunt pass on scheduled/two-way sync. */
class SyncBugHuntTest {

    private class CountingFs(val inner: MemorySyncFs) : SyncFs by inner {
        var lists = 0
        override fun listRecursive(root: String): List<SyncFile> { lists++; return inner.listRecursive(root) }
    }

    @Test fun twoWay_deleteOnOneSide_isPropagated_notResurrected() {
        val a = MemorySyncFs().apply { seed("keep.txt", "k", 1_000); seed("gone.txt", "g", 1_000) }
        val b = MemorySyncFs()
        var job = SyncJob(id = "j", aRoot = "/a", bRoot = "/b", direction = SyncDirection.TWO_WAY)
        job = SyncJobRunner.run(job, a, b).job
        assertTrue(b.exists("", "gone.txt"))
        b.delete("", "gone.txt")
        val second = SyncJobRunner.run(job, a, b)
        assertFalse("file deleted on B must be deleted on A, not copied back", a.exists("", "gone.txt"))
        assertFalse(b.exists("", "gone.txt"))
        assertEquals(1, second.plan.deleteCount)
        assertTrue(a.exists("", "keep.txt"))
    }

    @Test fun twoWay_deletedButEditedOnOtherSide_keepsTheEdit() {
        val a = MemorySyncFs().apply { seed("f.txt", "v1", 1_000) }
        val b = MemorySyncFs()
        var job = SyncJob(id = "j", aRoot = "/a", bRoot = "/b", direction = SyncDirection.TWO_WAY)
        job = SyncJobRunner.run(job, a, b).job
        b.delete("", "f.txt")
        a.seed("f.txt", "v2-longer", 9_000) // edited on A after the last run
        SyncJobRunner.run(job, a, b)
        assertEquals("v2-longer", a.read("", "f.txt").decodeToString())
        assertEquals("v2-longer", b.read("", "f.txt").decodeToString())
    }

    @Test fun emptySide_withHistory_refusesToMassDelete() {
        val a = MemorySyncFs().apply { seed("1.txt", "1"); seed("2.txt", "2") }
        val b = MemorySyncFs()
        var job = SyncJob(id = "j", aRoot = "/a", bRoot = "/b", direction = SyncDirection.TWO_WAY)
        job = SyncJobRunner.run(job, a, b).job
        // B comes back completely empty (unmounted card, wrong remote folder, server glitch).
        b.delete("", "1.txt"); b.delete("", "2.txt")
        try {
            SyncJobRunner.run(job, a, b)
            fail("expected the run to stop")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("empty"))
        }
        assertTrue(a.exists("", "1.txt")); assertTrue(a.exists("", "2.txt"))
    }

    @Test fun oneWay_emptySource_withHistory_refusesToWipeTarget() {
        val a = MemorySyncFs().apply { seed("1.txt", "1"); seed("2.txt", "2") }
        val b = MemorySyncFs()
        var job = SyncJob(id = "j", aRoot = "/a", bRoot = "/b", direction = SyncDirection.ONE_WAY_A_TO_B)
        job = SyncJobRunner.run(job, a, b).job
        a.delete("", "1.txt"); a.delete("", "2.txt")
        try { SyncJobRunner.run(job, a, b); fail("expected the run to stop") } catch (_: SyncSafetyStop) {}
        assertTrue(b.exists("", "1.txt")); assertTrue(b.exists("", "2.txt"))
    }

    @Test fun apply_listsEachSideAConstantNumberOfTimes() {
        val inner = MemorySyncFs().apply { repeat(200) { seed("f$it.txt", "x$it", 1_000L + it) } }
        val a = CountingFs(inner)
        val b = MemorySyncFs()
        val plan = FolderSync.plan(
            inner.listRecursive("").associateBy { it.relativePath }, emptyMap(),
            SyncDirection.ONE_WAY_A_TO_B, ConflictPolicy.NEWEST,
        )
        FolderSync.apply(plan, a, "", b, "")
        assertTrue("listed A ${a.lists} times for 200 copies", a.lists <= 1)
        assertEquals(1_000L + 7, b.listRecursive("").first { it.relativePath == "f7.txt" }.modifiedEpochMs)
    }

    @Test fun copyBToA_keepsSourceMtime() {
        val a = MemorySyncFs()
        val b = MemorySyncFs().apply { seed("x.txt", "x", 4_242) }
        val plan = FolderSync.plan(emptyMap(), b.listRecursive("").associateBy { it.relativePath },
            SyncDirection.ONE_WAY_B_TO_A, ConflictPolicy.NEWEST)
        FolderSync.apply(plan, a, "", b, "")
        assertEquals(4_242L, a.listRecursive("").single().modifiedEpochMs)
    }

    @Test fun nestedLocalRoots_areRejected() {
        assertEquals(null, SyncJobRules.problem(SyncJob(aRoot = "/sdcard/A", bRoot = "/sdcard/B")))
        assertTrue(SyncJobRules.problem(SyncJob(aRoot = "/sdcard/A", bRoot = "/sdcard/A/backup")) != null)
        assertTrue(SyncJobRules.problem(SyncJob(aRoot = "/sdcard/A/", bRoot = "/sdcard/A")) != null)
        assertTrue(SyncJobRules.problem(SyncJob(aRoot = "/sdcard/A/x", bRoot = "/sdcard/A")) != null)
        assertNull(SyncJobRules.problem(SyncJob(aRoot = "/sdcard/AB", bRoot = "/sdcard/A")))
        // Same path on a remote and locally is fine.
        assertNull(SyncJobRules.problem(SyncJob(aRoot = "/data", bConnectionId = "s", bRoot = "/data")))
        // Same remote connection, nested folders: also a loop.
        assertTrue(SyncJobRules.problem(SyncJob(aConnectionId = "s", aRoot = "/d", bConnectionId = "s", bRoot = "/d/e")) != null)
        assertTrue(SyncJobRules.problem(SyncJob(aRoot = " ", bRoot = "/x")) != null)
    }

    @Test fun recordRun_doesNotResurrectRemovedJob_orClobberEdits() {
        val old = SyncJob(id = "j", aRoot = "/a", bRoot = "/b", intervalHours = 24)
        val ran = old.copy(previousBoth = setOf("x"), lastRunEpochMs = 5, lastResult = "OK")
        assertNull(SyncJobStore.recordRun(null, ran))
        val edited = old.copy(intervalHours = 6, wifiOnly = true)
        val merged = SyncJobStore.recordRun(edited, ran)!!
        assertEquals(6L, merged.intervalHours)
        assertTrue(merged.wifiOnly)
        assertEquals(setOf("x"), merged.previousBoth)
        assertEquals("OK", merged.lastResult)
        // Roots changed while running → history from the old roots must not be applied.
        val moved = old.copy(bRoot = "/elsewhere")
        val m2 = SyncJobStore.recordRun(moved, ran)!!
        assertEquals(emptySet<String>(), m2.previousBoth)
        assertEquals("OK", m2.lastResult)
    }
}
