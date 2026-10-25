package ai.victorious.filemanager.core.net

import ai.victorious.filemanager.core.fs.FileIo
import ai.victorious.filemanager.core.fs.NioFileIo
import ai.victorious.filemanager.core.trash.TrashStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CompositeFileIoTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val nio = NioFileIo()
    private val calls = mutableListOf<String>()
    private val spy = object : FileIo by nio {
        override fun move(from: String, to: String) { calls += "move"; nio.move(from, to) }
        override fun copy(from: String, to: String) { calls += "copy"; nio.copy(from, to) }
        override fun deleteRecursively(path: String) { calls += "delete"; nio.deleteRecursively(path) }
    }

    @Test
    fun movingWithinLocalStorageIsOneMoveNotACopyAndDelete() {
        // The app's FileIo is a CompositeFileIo, so this is what every on-device Move goes through:
        // copy-then-delete needs free space equal to the file and leaves two copies if it is interrupted.
        val io = CompositeFileIo(spy, { null })
        val src = tmp.newFile("big.bin").apply { writeText("payload") }
        val dest = File(tmp.newFolder("to"), "big.bin")

        io.move(src.absolutePath, dest.absolutePath)

        assertEquals(listOf("move"), calls)
        assertEquals("payload", dest.readText())
        assertFalse(src.exists())
    }

    @Test
    fun movingAFolderWithinLocalStorageIsOneMove() {
        val io = CompositeFileIo(spy, { null })
        val dir = tmp.newFolder("album").also { File(it, "a.txt").writeText("a") }
        val dest = File(tmp.newFolder("to"), "album")

        io.move(dir.absolutePath, dest.absolutePath)

        assertEquals(listOf("move"), calls)
        assertEquals("a", File(dest, "a.txt").readText())
    }

    @Test
    fun trashingALocalFileIsASingleMove() {
        val io = CompositeFileIo(spy, { null })
        val trash = TrashStore(io, File(tmp.root, "trash").absolutePath)
        val file = tmp.newFile("movie.mkv").apply { writeText("frames") }

        trash.moveToTrash(file.absolutePath)

        // (the index swap also deletes its old copy; what matters is the payload is renamed, not copied)
        assertFalse("payload was copied", "copy" in calls)
        assertEquals(1, calls.count { it == "move" })
        assertFalse(file.exists())
    }

    @Test
    fun movingBetweenLocalAndRemoteStillCopiesThenDeletes() {
        val mem = InMemoryRemoteBackend("c1").also { it.seedDir("/docs") }
        val io = CompositeFileIo(spy, { if (it == "c1") mem else null })
        val src = tmp.newFile("up.txt").apply { writeText("hi") }

        io.move(src.absolutePath, RemotePaths.toVirtual("c1", "/docs/up.txt"))

        assertTrue(mem.exists("/docs/up.txt"))
        assertFalse(src.exists())
    }
}
