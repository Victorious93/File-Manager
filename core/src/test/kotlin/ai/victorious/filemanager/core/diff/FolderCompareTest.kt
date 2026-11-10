package ai.victorious.filemanager.core.diff

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FolderCompareTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun setup(): Pair<File, File> {
        val l = tmp.newFolder("left")
        val r = tmp.newFolder("right")
        File(l, "same.txt").writeText("same")
        File(r, "same.txt").writeText("same")
        File(l, "edited.txt").writeText("aaaa")
        File(r, "edited.txt").writeText("aaab") // same size, different bytes
        File(l, "grew.txt").writeText("short")
        File(r, "grew.txt").writeText("much longer")
        File(l, "only-left.txt").writeText("L")
        File(r, "sub").mkdirs(); File(r, "sub/only-right.txt").writeText("R")
        File(l, "sub").mkdirs(); File(l, "sub/both.txt").writeText("b"); File(r, "sub/both.txt").writeText("b")
        File(l, "newdir/inner").mkdirs(); File(l, "newdir/inner/x.txt").writeText("12345")
        File(l, "clash").writeText("file")
        File(r, "clash").mkdirs()
        File(l, "edited.txt").setLastModified(2_000_000_000_000L)
        File(r, "edited.txt").setLastModified(1_000_000_000_000L)
        return l to r
    }

    @Test fun deepCompareFindsEveryKind() {
        val (l, r) = setup()
        val d = FolderCompare.compare(l, r)
        val byPath = d.entries.associateBy { it.relPath }
        assertEquals(EntryStatus.SAME, byPath["same.txt"]!!.status)
        assertEquals(EntryStatus.SAME, byPath["sub/both.txt"]!!.status)
        assertEquals(EntryStatus.DIFFERENT, byPath["edited.txt"]!!.status)
        assertEquals(NewerSide.LEFT, byPath["edited.txt"]!!.newer)
        assertEquals(EntryStatus.DIFFERENT, byPath["grew.txt"]!!.status)
        assertEquals(EntryStatus.ONLY_LEFT, byPath["only-left.txt"]!!.status)
        assertEquals(EntryStatus.ONLY_RIGHT, byPath["sub/only-right.txt"]!!.status)
        assertEquals(EntryStatus.TYPE_MISMATCH, byPath["clash"]!!.status)
        // A one-sided folder is reported once with its total size, not expanded.
        assertTrue(byPath["newdir"]!!.isDirectory)
        assertEquals(5L, byPath["newdir"]!!.leftSize)
        assertNull(byPath["newdir/inner/x.txt"])
        val s = d.summary
        assertEquals(2, s.same); assertEquals(2, s.different); assertEquals(2, s.onlyLeft); assertEquals(1, s.onlyRight); assertEquals(1, s.typeMismatch)
        assertFalse(s.identical)
    }

    @Test fun fastModeUsesSizeAndTime() {
        val (l, r) = setup()
        File(r, "edited.txt").setLastModified(File(l, "edited.txt").lastModified())
        val fast = FolderCompare.compare(l, r, deep = false).entries.first { it.relPath == "edited.txt" }
        assertEquals(EntryStatus.SAME, fast.status) // documented trade-off of fast mode
        val deep = FolderCompare.compare(l, r, deep = true).entries.first { it.relPath == "edited.txt" }
        assertEquals(EntryStatus.DIFFERENT, deep.status)
    }

    @Test fun identicalFoldersAndIgnoreFilter() {
        val l = tmp.newFolder("a"); val r = tmp.newFolder("b")
        File(l, "x").writeText("1"); File(r, "x").writeText("1")
        File(l, ".git").mkdirs(); File(l, ".git/HEAD").writeText("ref")
        assertFalse(FolderCompare.compare(l, r).summary.identical)
        assertTrue(FolderCompare.compare(l, r, ignore = { it == ".git" || it.startsWith(".git/") }).summary.identical)
    }

    @Test fun copyMissingNeverOverwrites() {
        val (l, r) = setup()
        val d = FolderCompare.compare(l, r)
        val (copied, skipped) = FolderCompare.copyMissing(d, toRight = true)
        assertEquals(2, copied); assertEquals(0, skipped)
        assertEquals("L", File(r, "only-left.txt").readText())
        assertEquals("12345", File(r, "newdir/inner/x.txt").readText())
        assertEquals("aaab", File(r, "edited.txt").readText()) // differing files untouched
        // Re-running with the stale diff skips what now exists.
        assertEquals(0 to 2, FolderCompare.copyMissing(d, toRight = true))
        val back = FolderCompare.copyMissing(FolderCompare.compare(l, r), toRight = false, only = setOf("sub/only-right.txt"))
        assertEquals(1 to 0, back)
        assertEquals("R", File(l, "sub/only-right.txt").readText())
    }

    @Test fun rejectsSameFolderAndFiles() {
        val l = tmp.newFolder("same")
        assertTrue(runCatching { FolderCompare.compare(l, l) }.isFailure)
        assertTrue(runCatching { FolderCompare.compare(l, tmp.newFile("f")) }.isFailure)
    }
}
