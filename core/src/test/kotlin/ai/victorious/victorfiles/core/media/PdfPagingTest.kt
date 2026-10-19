package ai.victorious.victorfiles.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfPagingTest {
    @Test fun clampWithinBounds() {
        assertEquals(0, PdfPaging.clampPage(-5, 10))
        assertEquals(9, PdfPaging.clampPage(100, 10))
        assertEquals(3, PdfPaging.clampPage(3, 10))
    }

    @Test fun clampEmptyDocument() {
        assertEquals(0, PdfPaging.clampPage(3, 0))
        assertEquals("0 / 0", PdfPaging.label(3, 0))
    }

    @Test fun labelIsOneBased() {
        assertEquals("1 / 12", PdfPaging.label(0, 12))
        assertEquals("12 / 12", PdfPaging.label(50, 12))
    }

    @Test fun fitWidthPreservesAspect() {
        val s = PdfPaging.fitWidth(pageWidthPt = 612, pageHeightPt = 792, targetWidthPx = 1224)
        assertEquals(1224, s.widthPx)
        assertEquals(1584, s.heightPx) // 792 * 2
    }

    @Test fun fitWidthCapsLongEdge() {
        val s = PdfPaging.fitWidth(pageWidthPt = 100, pageHeightPt = 10000, targetWidthPx = 1000, maxBitmapPx = 4096)
        assertTrue(maxOf(s.widthPx, s.heightPx) <= 4096)
        assertTrue(s.widthPx >= 1 && s.heightPx >= 1)
    }

    @Test fun fitWidthDegenerateInputs() {
        assertEquals(PdfPaging.RenderSize(1, 1), PdfPaging.fitWidth(0, 0, 100))
        assertEquals(PdfPaging.RenderSize(1, 1), PdfPaging.fitWidth(100, 100, 0))
    }
}
