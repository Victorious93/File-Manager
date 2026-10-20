package ai.victorious.filemanager.core.photo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoToolsTest {
    @Test fun renameTokens() {
        val p = BatchRename.applyPattern("IMG_001.JPG", "{date:yyyy}-{index:00}_{name}.{ext}", 3, "Camera", 1_700_000_000_000L)
        assertTrue(p.contains("_IMG_001.JPG") || p.endsWith("IMG_001.JPG"))
        assertTrue(p.contains("03"))
        val preview = BatchRename.preview(listOf("a.jpg", "b.jpg"), "pic-{index:00}.{ext}", startIndex = 1)
        assertEquals("pic-01.jpg", preview[0].to)
        assertEquals("pic-02.jpg", preview[1].to)
    }

    @Test fun fitWithinDoesNotUpscale() {
        assertEquals(100 to 50, ImageBatch.fitWithin(100, 50, 200, 200))
        assertEquals(50 to 25, ImageBatch.fitWithin(100, 50, 50, 50))
        assertEquals("x.webp", ImageBatch.outputName("x.jpg", "webp"))
    }
}
