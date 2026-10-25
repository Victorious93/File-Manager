package ai.victorious.filemanager.core.trash

import ai.victorious.filemanager.core.fs.FileIo
import ai.victorious.filemanager.core.fs.FileIoException
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

    @Test
    fun sameVolumePayloadDirUsedAndRestorable() {
        val volume = tmp.newFolder("volume")
        val volTrash = File(volume, ".victorious-trash/items").absolutePath
        val t = TrashStore(io, File(tmp.root, "idx").absolutePath, payloadDirFor = { p ->
            if (p.startsWith(volume.absolutePath)) volTrash else null
        })
        val big = File(volume, "Movies/clip.mp4").apply { parentFile.mkdirs(); writeText("frames") }
        val id = t.moveToTrash(big.absolutePath)
        val entry = t.list().single()
        assertTrue("payload on same volume: ${entry.trashPayloadPath}", entry.trashPayloadPath.startsWith(volTrash))
        assertTrue(File(volume, ".victorious-trash/.nomedia").exists())
        assertFalse(big.exists())
        assertEquals(big.absolutePath, t.restore(id))
        assertEquals("frames", big.readText())

        // Trashing the volume trash folder (or a parent of it) falls back to the app trash root.
        val outer = File(volume, ".victorious-trash").absolutePath
        t.moveToTrash(outer)
        assertTrue(t.list().single().trashPayloadPath.startsWith(File(tmp.root, "idx").absolutePath))
    }

    @Test
    fun entryIsIndexedBeforeTheOriginalDisappears() {
        val file = File(docs, "race.txt").apply { writeText("x") }
        var listedDuringMove = false
        val spying = object : FileIo by io {
            override fun move(from: String, to: String) {
                listedDuringMove = trashOf(this).list().any { it.originalPath == file.absolutePath }
                io.move(from, to)
            }
        }
        val store = TrashStore(spying, File(tmp.root, "trash2").absolutePath)
        holder = store
        store.moveToTrash(file.absolutePath)
        assertTrue("index must list the entry before the file is moved", listedDuringMove)
    }

    @Test
    fun failedMoveLeavesIndexAndFileUntouched() {
        val file = File(docs, "stuck.txt").apply { writeText("x") }
        val failing = object : FileIo by io {
            override fun move(from: String, to: String) = throw FileIoException("disk full")
        }
        val store = TrashStore(failing, File(tmp.root, "trash3").absolutePath)
        assertTrue(runCatching { store.moveToTrash(file.absolutePath) }.isFailure)
        assertTrue(file.exists())
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun tornIndexDoesNotBlockDeletesAndIsKeptForRecovery() {
        val root = File(tmp.root, "trash")
        File(root, "index.json").writeText("[{\"id\":\"t-1\",\"originalPa") // crash mid-write
        val file = File(docs, "n.txt").apply { writeText("x") }

        val id = trash.moveToTrash(file.absolutePath)

        assertFalse(file.exists())
        assertEquals(listOf(id), trash.list().map { it.id })
        assertTrue("the unreadable index is set aside, not silently destroyed", root.list()!!.any { it.startsWith("index.json.corrupt") })
        assertEquals("x", File(trash.restore(id)).readText())
    }

    @Test
    fun failedIndexWriteLeavesThePreviousIndexIntact() {
        val first = File(docs, "first.txt").apply { writeText("1") }
        val firstId = trash.moveToTrash(first.absolutePath)

        val dying = object : FileIo by io {
            override fun writeText(path: String, text: String) {
                if (path.contains("index")) {
                    io.writeText(path, text.take(12)) // disk full part-way through
                    throw FileIoException("disk full")
                }
                io.writeText(path, text)
            }
        }
        val second = File(docs, "second.txt").apply { writeText("2") }
        assertTrue(runCatching { TrashStore(dying, File(tmp.root, "trash").absolutePath).moveToTrash(second.absolutePath) }.isFailure)

        assertTrue(second.exists())
        assertEquals(listOf(firstId), trash.list().map { it.id })
    }

    @Test
    fun concurrentTrashingNeverLosesOrPhantomsIndexEntries() {
        val shared = TrashStore(io, File(tmp.root, "trash-shared").absolutePath)
        val files = (0 until 8).flatMap { t -> (0 until 8).map { i -> File(docs, "t$t-$i.txt").apply { writeText("$t/$i") } } }
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val threads = (0 until 8).map { t ->
            Thread {
                runCatching {
                    (0 until 8).forEach { i -> shared.moveToTrash(files[t * 8 + i].absolutePath) }
                    shared.list() // readers interleave with the other threads' writers
                }.onFailure { failures += it }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertTrue("worker failures: $failures", failures.isEmpty())
        assertTrue(files.none { it.exists() })
        assertEquals(files.map { it.name }.toSet(), shared.list().map { it.originalName }.toSet())
        assertEquals(files.size, shared.list().size)
    }

    private var holder: TrashStore? = null
    private fun trashOf(@Suppress("UNUSED_PARAMETER") io: FileIo): TrashStore = holder!!
}
