package ai.victorious.filemanager.core.rename

import ai.victorious.filemanager.core.fs.NioFileIo
import ai.victorious.filemanager.core.ops.OpKind
import ai.victorious.filemanager.core.ops.OpStatus
import ai.victorious.filemanager.core.ops.OperationQueue
import ai.victorious.filemanager.core.trash.TrashStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BatchRenameTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun names(dir: File) = dir.list()!!.toSet()
    private fun plan(dir: File, files: List<String>, spec: RenameSpec) =
        BatchRename.plan(files.map { File(dir, it).absolutePath }, spec) { names(File(it)) }

    @Test
    fun literalReplaceKeepsExtension() {
        val d = tmp.newFolder()
        listOf("IMG_1.jpg", "IMG_2.jpg").forEach { File(d, it).writeText("x") }
        val p = plan(d, listOf("IMG_1.jpg", "IMG_2.jpg"), RenameSpec(find = "IMG_", replace = "trip-"))
        assertEquals(listOf("trip-1.jpg", "trip-2.jpg"), p.items.map { it.newName })
        assertTrue(p.isApplicable)
    }

    @Test
    fun regexWithGroupsAndCaseInsensitive() {
        val d = tmp.newFolder()
        File(d, "Show S01E02.mkv").writeText("x")
        val p = plan(d, listOf("Show S01E02.mkv"), RenameSpec(find = "s(\\d+)e(\\d+)", replace = "$1x$2", regex = true, caseSensitive = false))
        assertEquals("Show 01x02.mkv", p.items.single().newName)
    }

    @Test
    fun literalReplaceTreatsDollarAsText() {
        val d = tmp.newFolder()
        File(d, "a.txt").writeText("x")
        val p = plan(d, listOf("a.txt"), RenameSpec(find = "a", replace = "$1"))
        assertEquals("\$1.txt", p.items.single().newName)
    }

    @Test
    fun badReplacementReferenceIsReportedNotSilentlyIgnored() {
        val d = tmp.newFolder()
        listOf("a.txt", "b.txt").forEach { File(d, it).writeText("x") }
        // $1 with no capture group in the pattern: used to make every file look "unchanged".
        val p = plan(d, listOf("a.txt", "b.txt"), RenameSpec(find = "a", replace = "$1", regex = true))
        assertTrue("expected an error, got $p", p.error != null)
        assertFalse(p.isApplicable)
        val dangling = plan(d, listOf("a.txt"), RenameSpec(find = "a", replace = "x\\", regex = true))
        assertTrue("expected an error, got $dangling", dangling.error != null)
    }

    @Test
    fun invalidRegexReportsErrorNotCrash() {
        val d = tmp.newFolder()
        File(d, "a.txt").writeText("x")
        val p = plan(d, listOf("a.txt"), RenameSpec(find = "(", regex = true))
        assertTrue(p.error != null)
        assertFalse(p.isApplicable)
    }

    @Test
    fun counterTemplateAndExtensionChange() {
        val d = tmp.newFolder()
        listOf("a.JPG", "b.JPG", "c.JPG").forEach { File(d, it).writeText("x") }
        val p = plan(d, listOf("a.JPG", "b.JPG", "c.JPG"), RenameSpec(template = "photo_{n}", counterStart = 8, counterPad = 3, newExtension = "jpg"))
        assertEquals(listOf("photo_008.jpg", "photo_009.jpg", "photo_010.jpg"), p.items.map { it.newName })
    }

    @Test
    fun caseModes() {
        val d = tmp.newFolder()
        File(d, "hello_big world.TXT").writeText("x")
        assertEquals("Hello_Big World.TXT", plan(d, listOf("hello_big world.TXT"), RenameSpec(caseMode = CaseMode.TITLE)).items.single().newName)
        assertEquals("HELLO_BIG WORLD.TXT", plan(d, listOf("hello_big world.TXT"), RenameSpec(caseMode = CaseMode.UPPER)).items.single().newName)
    }

    @Test
    fun unchangedItemsAreNotRenamed() {
        val d = tmp.newFolder()
        File(d, "a.txt").writeText("x")
        val p = plan(d, listOf("a.txt"), RenameSpec())
        assertEquals(PlanStatus.UNCHANGED, p.items.single().status)
        assertFalse(p.isApplicable)
    }

    @Test
    fun duplicateTargetsAndOutsiderCollisionsConflict() {
        val d = tmp.newFolder()
        listOf("a1.txt", "a2.txt", "taken.txt").forEach { File(d, it).writeText("x") }
        val dupes = plan(d, listOf("a1.txt", "a2.txt"), RenameSpec(find = "a1", replace = "a2").copy(template = "same"))
        assertTrue(dupes.items.all { it.status == PlanStatus.CONFLICT })
        val outsider = plan(d, listOf("a1.txt"), RenameSpec(template = "taken"))
        assertEquals(PlanStatus.CONFLICT, outsider.items.single().status)
        assertFalse(outsider.isApplicable)
    }

    @Test
    fun invalidNameIsFlagged() {
        val d = tmp.newFolder()
        File(d, "a.txt").writeText("x")
        val p = plan(d, listOf("a.txt"), RenameSpec(template = "x/{name}"))
        assertEquals(PlanStatus.INVALID, p.items.single().status)
    }

    private fun queue(): OperationQueue {
        val io = NioFileIo()
        return OperationQueue(io, TrashStore(io, File(tmp.root, ".trash").absolutePath))
    }

    @Test
    fun swapRenameWorksAndUndoRestores() {
        val d = tmp.newFolder()
        File(d, "a.txt").writeText("A")
        File(d, "b.txt").writeText("B")
        val pa = File(d, "a.txt").absolutePath
        val pb = File(d, "b.txt").absolutePath
        val q = queue()
        q.enqueueBatchRename(listOf(pa to pb, pb to pa))
        assertEquals(OpStatus.SUCCEEDED, q.processNext().status)
        assertEquals("B", File(d, "a.txt").readText())
        assertEquals("A", File(d, "b.txt").readText())
        assertEquals(OpStatus.UNDONE, q.undoLast().status)
        assertEquals("A", File(d, "a.txt").readText())
        assertEquals("B", File(d, "b.txt").readText())
        assertEquals(setOf("a.txt", "b.txt"), names(d))
    }

    @Test
    fun planThenApplyThenUndoEndToEnd() {
        val d = tmp.newFolder()
        listOf("x.txt", "y.txt").forEach { File(d, it).writeText(it) }
        val p = plan(d, listOf("x.txt", "y.txt"), RenameSpec(template = "doc_{n}"))
        val q = queue()
        q.enqueueBatchRename(p.renames)
        assertEquals(OpKind.BATCH_RENAME, q.snapshot().pending.single().kind)
        q.processNext()
        assertEquals(setOf("doc_1.txt", "doc_2.txt"), names(d))
        q.undoLast()
        assertEquals(setOf("x.txt", "y.txt"), names(d))
    }

    @Test
    fun rejectsTargetThatExistsOutsideBatchAndDuplicateTargets() {
        val d = tmp.newFolder()
        listOf("a", "b", "c").forEach { File(d, it).writeText(it) }
        val q = queue()
        val a = File(d, "a").absolutePath
        val b = File(d, "b").absolutePath
        val c = File(d, "c").absolutePath
        val x = File(d, "x").absolutePath
        runCatching { q.enqueueBatchRename(listOf(a to c)) }.also { assertTrue(it.isFailure) }
        runCatching { q.enqueueBatchRename(listOf(a to x, b to x)) }.also { assertTrue(it.isFailure) }
        runCatching { q.enqueueBatchRename(listOf(a to File(d, "sub/x").absolutePath)) }.also { assertTrue(it.isFailure) }
        assertEquals(setOf("a", "b", "c"), names(d))
    }
}
