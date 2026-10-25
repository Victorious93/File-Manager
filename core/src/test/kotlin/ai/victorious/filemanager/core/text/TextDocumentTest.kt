package ai.victorious.filemanager.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TextDocumentTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun undoRedo() {
        val h = UndoHistory("a")
        h.push("ab"); h.push("abc")
        assertEquals("ab", h.undo())
        assertEquals("a", h.undo())
        assertFalse(h.canUndo)
        assertEquals("ab", h.redo())
        h.push("abX")
        assertFalse(h.canRedo)
        assertEquals("abX", h.current)
    }

    @Test fun historyIsBounded() {
        val h = UndoHistory(0, limit = 3)
        (1..10).forEach { h.push(it) }
        repeat(10) { h.undo() }
        assertEquals(7, h.current)
    }

    @Test fun loadSaveRoundTripUtf8() {
        val f = tmp.newFile("x.txt")
        TextFiles.save(f, "Grüße ✓\nline2")
        assertEquals("Grüße ✓\nline2", TextFiles.load(f))
        assertFalse(java.io.File(f.parentFile, ".x.txt.saving").exists())
    }

    @Test fun rejectsInvalidUtf8AndHugeFiles() {
        val bad = tmp.newFile("bad.txt").apply { writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00)) }
        assertTrue(runCatching { TextFiles.load(bad) }.exceptionOrNull() is TextLoadException)
        val big = tmp.newFile("big.txt").apply { writeText("x".repeat(100)) }
        assertTrue(runCatching { TextFiles.load(big, maxBytes = 10) }.exceptionOrNull() is TextLoadException)
    }

    @Test fun stripsBom() {
        val f = tmp.newFile("bom.txt").apply { writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 0x41)) }
        assertEquals("A", TextFiles.load(f))
    }

    @Test fun failedSaveLeavesNoTempFileBehind() {
        val dir = tmp.newFolder("docs")
        val target = java.io.File(dir, "notes.txt").apply { mkdir() }
        java.io.File(target, "inner").writeText("x") // a non-empty folder where the file should be: the save must fail

        assertTrue(runCatching { TextFiles.save(target, "hello") }.isFailure)
        assertEquals(listOf("notes.txt"), dir.list()!!.toList())
    }
}
