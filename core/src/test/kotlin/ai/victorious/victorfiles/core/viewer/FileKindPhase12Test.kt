package ai.victorious.victorfiles.core.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileKindPhase12Test {
    @Test fun pdfAndFontRouting() {
        assertEquals(FileKind.DOCUMENT, FileKinds.byName("manual.PDF"))
        assertEquals(FileKind.FONT, FileKinds.byName("Roboto.ttf"))
        assertEquals(FileKind.FONT, FileKinds.byName("Roboto.otf"))
    }

    @Test fun hasThumbnailForImagesAndVideos() {
        assertTrue(FileKinds.hasThumbnail("a.jpg"))
        assertTrue(FileKinds.hasThumbnail("b.mp4"))
        assertFalse(FileKinds.hasThumbnail("c.pdf"))
        assertFalse(FileKinds.hasThumbnail("d.txt"))
        assertFalse(FileKinds.hasThumbnail("e.ts")) // ts excluded (ambiguous)
    }
}
