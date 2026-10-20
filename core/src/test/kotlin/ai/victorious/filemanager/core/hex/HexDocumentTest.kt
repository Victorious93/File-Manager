package ai.victorious.filemanager.core.hex

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HexDocumentTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun rowsFormat() {
        val doc = HexDocument("Hello, hex world!".toByteArray())
        assertEquals(2, doc.rowCount)
        val r = doc.row(0)
        assertEquals("00000000", r.offsetLabel)
        assertEquals("48 65 6C 6C 6F 2C 20 68 65 78 20 77 6F 72 6C 64", r.hex)
        assertEquals("Hello, hex world", r.ascii)
        assertEquals("00000010", doc.row(1).offsetLabel)
        assertEquals("!", doc.row(1).ascii)
    }

    @Test fun editUndoSave() {
        val f = tmp.newFile("b.bin").apply { writeBytes(byteArrayOf(0, 1, 2, 3)) }
        val doc = HexDocument.load(f)
        doc.set(1, 0x7F); doc.set(1, 0x10); doc.set(3, 9)
        assertTrue(doc.isDirty)
        doc.undo()
        assertArrayEquals(byteArrayOf(0, 0x10, 2, 3), doc.bytes())
        doc.undo()
        assertEquals(0x7F.toByte(), doc[1])
        doc.saveTo(f)
        assertArrayEquals(byteArrayOf(0, 0x7F, 2, 3), f.readBytes())
        assertFalse(doc.isDirty)
        assertFalse(doc.canUndo)
    }

    @Test fun settingOriginalValueClearsPatch() {
        val doc = HexDocument(byteArrayOf(5))
        doc.set(0, 6); doc.set(0, 5)
        assertFalse(doc.isDirty)
    }

    @Test fun parseByte() {
        assertEquals(0xFF.toByte(), Hex.parseByte("ff"))
        assertEquals(0x0A.toByte(), Hex.parseByte("a"))
        assertNull(Hex.parseByte("xyz"))
        assertNull(Hex.parseByte(""))
        assertEquals("0A", Hex.byte(10))
    }
}
