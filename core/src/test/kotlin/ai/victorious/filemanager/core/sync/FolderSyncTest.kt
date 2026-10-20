package ai.victorious.filemanager.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderSyncTest {
    @Test fun oneWayCopiesAndDryRun() {
        val a = MemorySyncFs().also { it.seed("x.txt", "hello", 10); it.seed("y.txt", "y", 10) }
        val b = MemorySyncFs().also { it.seed("y.txt", "y", 10) }
        val plan = FolderSync.plan(
            a.listRecursive("").associateBy { it.relativePath },
            b.listRecursive("").associateBy { it.relativePath },
            SyncDirection.ONE_WAY_A_TO_B,
            ConflictPolicy.NEWEST,
        )
        assertEquals(1, plan.copyCount)
        FolderSync.apply(plan, a, "", b, "", dryRun = true)
        assertTrue(!b.exists("", "x.txt"))
        FolderSync.apply(plan, a, "", b, "")
        assertEquals("hello", b.read("", "x.txt").decodeToString())
    }

    @Test fun twoWayNewestAndKeepBoth() {
        val a = MemorySyncFs().also { it.seed("c.txt", "A", 100) }
        val b = MemorySyncFs().also { it.seed("c.txt", "B", 50) }
        val newest = FolderSync.plan(
            a.listRecursive("").associateBy { it.relativePath },
            b.listRecursive("").associateBy { it.relativePath },
            SyncDirection.TWO_WAY, ConflictPolicy.NEWEST,
        )
        assertEquals(SyncActionKind.COPY_A_TO_B, newest.actions.single().kind)
        val both = FolderSync.plan(
            a.listRecursive("").associateBy { it.relativePath },
            b.listRecursive("").associateBy { it.relativePath },
            SyncDirection.TWO_WAY, ConflictPolicy.KEEP_BOTH,
        )
        FolderSync.apply(both, a, "", b, "")
        assertTrue(b.exists("", FolderSync.conflictCopyName("c.txt", "A")))
        assertTrue(a.exists("", FolderSync.conflictCopyName("c.txt", "B")))
    }

    @Test fun deletesUseTrashHook() {
        val a = MemorySyncFs().also { it.seed("gone.txt", "x", 1) }
        val b = MemorySyncFs()
        val plan = FolderSync.plan(
            a.listRecursive("").associateBy { it.relativePath },
            b.listRecursive("").associateBy { it.relativePath },
            SyncDirection.ONE_WAY_B_TO_A, ConflictPolicy.NEWEST,
            previousBoth = setOf("gone.txt"),
        )
        val trashed = mutableListOf<String>()
        FolderSync.apply(plan, a, "", b, "", trashOnDelete = { side, rel -> trashed += "$side:$rel"; a.delete("", rel) })
        assertEquals(listOf("A:gone.txt"), trashed)
        assertTrue(!a.exists("", "gone.txt"))
    }

    @Test fun askLeavesConflictSkippedUntilResolved() {
        val a = MemorySyncFs().also { it.seed("z.txt", "A", 1) }
        val b = MemorySyncFs().also { it.seed("z.txt", "B", 2) }
        val plan = FolderSync.plan(
            a.listRecursive("").associateBy { it.relativePath },
            b.listRecursive("").associateBy { it.relativePath },
            SyncDirection.TWO_WAY, ConflictPolicy.ASK,
        )
        assertEquals(listOf("z.txt"), plan.conflicts)
        FolderSync.apply(plan, a, "", b, "")
        assertEquals("A", a.read("", "z.txt").decodeToString())
        FolderSync.apply(plan, a, "", b, "", resolutions = mapOf("z.txt" to SyncActionKind.COPY_B_TO_A))
        assertEquals("B", a.read("", "z.txt").decodeToString())
    }
}
