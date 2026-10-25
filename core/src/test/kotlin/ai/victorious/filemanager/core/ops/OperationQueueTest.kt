package ai.victorious.filemanager.core.ops

import ai.victorious.filemanager.core.fs.NioFileIo
import ai.victorious.filemanager.core.trash.TrashStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OperationQueueTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var io: NioFileIo
    private lateinit var trash: TrashStore
    private lateinit var left: File
    private lateinit var right: File

    @Before
    fun setUp() {
        io = NioFileIo()
        trash = TrashStore(io, File(tmp.root, ".victorious-trash").absolutePath)
        left = tmp.newFolder("left")
        right = tmp.newFolder("right")
        File(left, "alpha.txt").writeText("alpha")
        File(left, "beta.txt").writeText("beta")
        File(left, "nested").mkdir()
        File(left, "nested/deep.txt").writeText("deep")
    }

    @Test
    fun copyThenUndoRemovesCopiesAndKeepsOriginals() {
        val q = newQueue()
        q.enqueue(OpKind.COPY, listOf(File(left, "alpha.txt").absolutePath), right.absolutePath)
        val result = q.processNext()
        assertEquals(OpStatus.SUCCEEDED, result.status)
        assertTrue(File(right, "alpha.txt").exists())
        assertTrue(File(left, "alpha.txt").exists())
        assertTrue(q.snapshot().canUndo)

        val undo = q.undoLast()
        assertEquals(OpStatus.UNDONE, undo.status)
        assertFalse(File(right, "alpha.txt").exists())
        assertTrue(File(left, "alpha.txt").exists())
        assertFalse(q.snapshot().canUndo)
    }

    @Test
    fun copyCollisionGetsUniqueName() {
        File(right, "alpha.txt").writeText("existing")
        val q = newQueue()
        q.enqueue(OpKind.COPY, listOf(File(left, "alpha.txt").absolutePath), right.absolutePath)
        q.processNext()
        assertTrue(File(right, "alpha.txt").readText() == "existing")
        assertTrue(File(right, "alpha (1).txt").exists())
        assertEquals("alpha", File(right, "alpha (1).txt").readText())
    }

    @Test
    fun moveThenUndoRestoresOriginalPath() {
        val src = File(left, "beta.txt")
        val q = newQueue()
        q.enqueue(OpKind.MOVE, listOf(src.absolutePath), right.absolutePath)
        q.processNext()
        assertFalse(src.exists())
        assertTrue(File(right, "beta.txt").exists())

        q.undoLast()
        assertTrue(src.exists())
        assertFalse(File(right, "beta.txt").exists())
        assertEquals("beta", src.readText())
    }

    @Test
    fun deleteGoesToTrashAndUndoRestores() {
        val src = File(left, "alpha.txt")
        val q = newQueue()
        q.enqueue(OpKind.DELETE, listOf(src.absolutePath))
        q.processNext()
        assertFalse(src.exists())
        assertEquals(1, trash.list().size)

        q.undoLast()
        assertTrue(src.exists())
        assertEquals("alpha", src.readText())
        assertTrue(trash.list().isEmpty())
    }

    @Test
    fun renameThenUndoRestoresName() {
        val src = File(left, "alpha.txt")
        val q = newQueue()
        q.enqueue(OpKind.RENAME, listOf(src.absolutePath), newName = "gamma.txt")
        q.processNext()
        assertFalse(src.exists())
        assertTrue(File(left, "gamma.txt").exists())

        q.undoLast()
        assertTrue(src.exists())
        assertFalse(File(left, "gamma.txt").exists())
    }

    @Test
    fun refusesMoveIntoSelf() {
        val nested = File(left, "nested")
        val q = newQueue()
        try {
            q.enqueue(OpKind.MOVE, listOf(nested.absolutePath), nested.absolutePath)
            throw AssertionError("expected failure")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("itself"))
        }
    }

    @Test
    fun processesQueueInOrder() {
        val q = newQueue()
        q.enqueue(OpKind.COPY, listOf(File(left, "alpha.txt").absolutePath), right.absolutePath)
        q.enqueue(OpKind.RENAME, listOf(File(left, "beta.txt").absolutePath), newName = "beta2.txt")
        val results = q.processAll()
        assertEquals(2, results.size)
        assertTrue(results.all { it.status == OpStatus.SUCCEEDED })
        assertTrue(File(right, "alpha.txt").exists())
        assertTrue(File(left, "beta2.txt").exists())
        assertEquals(true, q.snapshot().canUndo)
        q.undoLast()
        assertTrue(File(left, "beta.txt").exists())
        q.undoLast()
        assertFalse(File(right, "alpha.txt").exists())
    }

    @Test
    fun copyDirectoryRecursively() {
        val q = newQueue()
        q.enqueue(OpKind.COPY, listOf(File(left, "nested").absolutePath), right.absolutePath)
        q.processNext()
        assertTrue(File(right, "nested/deep.txt").exists())
        assertEquals("deep", File(right, "nested/deep.txt").readText())
        q.undoLast()
        assertFalse(File(right, "nested").exists())
        assertTrue(File(left, "nested/deep.txt").exists())
    }

    @Test
    fun createFolderAndFileThenUndoMovesThemToTrash() {
        val q = newQueue()
        q.enqueue(OpKind.CREATE_FOLDER, listOf(right.absolutePath), newName = "made")
        assertEquals(OpStatus.SUCCEEDED, q.processNext().status)
        assertTrue(File(right, "made").isDirectory)
        q.enqueue(OpKind.CREATE_FILE, listOf(right.absolutePath), newName = "note.txt")
        assertEquals(OpStatus.SUCCEEDED, q.processNext().status)
        assertTrue(File(right, "note.txt").isFile)
        assertEquals("", File(right, "note.txt").readText())

        assertEquals(OpStatus.UNDONE, q.undoLast().status)
        assertFalse(File(right, "note.txt").exists())
        assertEquals(OpStatus.UNDONE, q.undoLast().status)
        assertFalse(File(right, "made").exists())
        // Undo of create never hard-deletes: both items are restorable from trash.
        assertEquals(setOf("made", "note.txt"), trash.list().map { it.originalName }.toSet())
    }

    @Test
    fun createRejectsExistingAndInvalidNames() {
        val q = newQueue()
        val existing = runCatching { q.enqueue(OpKind.CREATE_FILE, listOf(left.absolutePath), newName = "alpha.txt") }
        assertTrue(existing.isFailure)
        val bad = runCatching { q.enqueue(OpKind.CREATE_FOLDER, listOf(left.absolutePath), newName = "a/b") }
        assertTrue(bad.isFailure)
    }

    @Test
    fun concurrentEnqueueAndProcessDoesNotCorruptQueue() {
        val q = newQueue()
        val threads = (0 until 8).map { i ->
            Thread {
                repeat(10) { j ->
                    q.enqueue(OpKind.CREATE_FILE, listOf(right.absolutePath), newName = "t$i-$j.txt")
                    q.processAll()
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(80, right.listFiles()!!.count { it.name.startsWith("t") })
    }

    private fun newQueue(): OperationQueue {
        var n = 0
        return OperationQueue(io, trash, ids = { "op-${++n}" })
    }
}
