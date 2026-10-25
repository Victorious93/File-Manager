package ai.victorious.filemanager.core.fs

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class JsonStateFileTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val io = NioFileIo()
    private val dir get() = File(tmp.root, "state")
    private fun state(io: FileIo = this.io) =
        JsonStateFile(io, File(dir, "list.json").absolutePath, ListSerializer(String.serializer()), emptyList(), clock = { 42L })

    @Test
    fun aMissingFileLoadsAsEmptyAndCreatesNothing() {
        val s = state()
        assertFalse(s.exists())
        assertEquals(emptyList<String>(), s.load())
        assertFalse(dir.exists())
    }

    @Test
    fun saveCreatesParentFoldersAndRoundTrips() {
        val s = state()
        s.save(listOf("a", "b"))
        assertTrue(s.exists())
        assertEquals(listOf("a", "b"), state().load())
        s.save(listOf("c"))
        assertEquals(listOf("c"), state().load())
        assertEquals("only the state file remains", listOf("list.json"), dir.list()!!.toList())
    }

    @Test
    fun aCompleteTmpLeftByACrashDuringTheSwapIsPromoted() {
        // save() = write .tmp, delete old, rename .tmp; dying after the delete leaves only a complete .tmp.
        dir.mkdirs()
        File(dir, "list.json.tmp").writeText("[\"a\",\"b\"]")
        val s = state()
        assertTrue(s.exists())
        assertEquals(listOf("a", "b"), s.load())
        assertTrue(File(dir, "list.json").exists())
        assertFalse(File(dir, "list.json.tmp").exists())
    }

    @Test
    fun anIncompleteTmpIsNotAState() {
        dir.mkdirs()
        File(dir, "list.json.tmp").writeText("[\"a\",")
        val s = state()
        assertFalse(s.exists())
        assertEquals(emptyList<String>(), s.load())
    }

    @Test
    fun anUnreadableFileIsSetAsideUnderAUniqueNameEachTime() {
        dir.mkdirs()
        val s = state()
        File(dir, "list.json").writeText("[\"a\"")
        assertEquals(emptyList<String>(), s.load())
        File(dir, "list.json").writeText("not json")
        assertEquals(emptyList<String>(), s.load())

        assertEquals(setOf("list.json.corrupt-42", "list.json.corrupt-42-1"), dir.list()!!.toSet())
        assertEquals("[\"a\"", File(dir, "list.json.corrupt-42").readText())
        s.save(listOf("fresh"))
        assertEquals(listOf("fresh"), s.load())
    }

    @Test
    fun aSaveThatDiesPartWayLeavesThePreviousStateIntact() {
        state().save(listOf("keep"))
        val dying = object : FileIo by io {
            override fun writeText(path: String, text: String) {
                io.writeText(path, text.take(5))
                throw FileIoException("disk full")
            }
        }
        assertTrue(runCatching { state(dying).save(listOf("lost")) }.isFailure)
        assertEquals(listOf("keep"), state().load())
    }
}
