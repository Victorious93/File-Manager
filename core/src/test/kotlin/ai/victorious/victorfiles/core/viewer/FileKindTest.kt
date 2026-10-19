package ai.victorious.victorfiles.core.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileKindTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun byExtension() {
        assertEquals(FileKind.IMAGE, FileKinds.byName("a.JPG"))
        assertEquals(FileKind.ARCHIVE, FileKinds.byName("b.tar.gz"))
        assertEquals(FileKind.AUDIO, FileKinds.byName("c.mp3"))
        assertEquals(FileKind.VIDEO, FileKinds.byName("d.mp4"))
        assertEquals(FileKind.TEXT, FileKinds.byName("e.kt"))
    }

    @Test fun sniffsUnknownExtensions() {
        val t = tmp.newFile("notes.weird").apply { writeText("héllo\nworld") }
        val b = tmp.newFile("blob.weird").apply { writeBytes(byteArrayOf(1, 0, 2, 3)) }
        assertEquals(FileKind.TEXT, FileKinds.detect(t))
        assertEquals(FileKind.BINARY, FileKinds.detect(b))
    }

    @Test fun utf8Validation() {
        assertTrue(Utf8.isValid("ünïcödé ✓".toByteArray()))
        assertFalse(Utf8.isValid(byteArrayOf(0xC3.toByte())))
        assertTrue(Utf8.isValid(byteArrayOf(0xC3.toByte()), allowTruncatedTail = true))
        assertFalse(Utf8.isValid(byteArrayOf(0xFF.toByte(), 0x41)))
    }
}
