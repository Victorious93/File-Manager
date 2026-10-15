package ai.victorious.filemanager.core.trash

import ai.victorious.filemanager.core.fs.NioFileIo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TrashStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var io: NioFileIo
    private lateinit var trash: TrashStore
    private lateinit var docs: File

    @Before
    fun setUp() {
        io = NioFileIo()
        docs = tmp.newFolder("docs")
        trash = TrashStore(
            io,
            File(tmp.root, "trash").absolutePath,
            ids = {
                var n = 0
                { "t-${++n}" }
            }.let { it() },
            clock = { 1_700_000_000_000L },
        )
    }

    @Test
    fun softDeleteAndRestoreOriginalPath() {
        val file = File(docs, "note.txt").apply { writeText("hello") }
        val id = trash.moveToTrash(file.absolutePath)
        assertFalse(file.exists())
        assertEquals(1, trash.list().size)
        assertEquals("note.txt", trash.list().single().originalName)

        val restored = trash.restore(id)
        assertTrue(File(restored).exists())
        assertEquals("hello", File(restored).readText())
        assertTrue(trash.list().isEmpty())
    }

    @Test
    fun restoreUsesUniqueNameWhenOriginalOccupied() {
        val file = File(docs, "note.txt").apply { writeText("old") }
        val id = trash.moveToTrash(file.absolutePath)
        File(docs, "note.txt").writeText("new")
        val restored = trash.restore(id)
        assertTrue(restored.endsWith("note (1).txt"))
        assertEquals("old", File(restored).readText())
        assertEquals("new", File(docs, "note.txt").readText())
    }

    @Test
    fun emptyPermanentlyRemovesPayloads() {
        val file = File(docs, "gone.txt").apply { writeText("x") }
        trash.moveToTrash(file.absolutePath)
        trash.empty()
        assertTrue(trash.list().isEmpty())
        assertFalse(file.exists())
        val leftovers = File(tmp.root, "trash/items").list()?.filter { it != "." && it != ".." } ?: emptyList()
        assertTrue(leftovers.isEmpty())
    }

    @Test
    fun directorySoftDeleteRoundTrip() {
        val dir = File(docs, "folder").apply { mkdir() }
        File(dir, "inner.txt").writeText("in")
        val id = trash.moveToTrash(dir.absolutePath)
        assertFalse(dir.exists())
        trash.restore(id)
        assertTrue(File(docs, "folder/inner.txt").exists())
    }
}
