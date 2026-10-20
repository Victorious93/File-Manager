package ai.victorious.filemanager.core.share

import org.junit.Assert.assertEquals
import org.junit.Test

class ShareIntakeTest {
    @Test fun sanitizeStripsPathTraversal() {
        assertEquals("x.jpg", ShareIntake.sanitize("../../etc/x.jpg"))
        assertEquals("x.jpg", ShareIntake.sanitize("/sdcard/Download/x.jpg"))
        assertEquals("x.jpg", ShareIntake.sanitize("a\\b\\x.jpg"))
    }

    @Test fun sanitizeFallsBackOnEmptyOrDots() {
        assertEquals("shared", ShareIntake.sanitize(null))
        assertEquals("shared", ShareIntake.sanitize("   "))
        assertEquals("shared", ShareIntake.sanitize(".."))
        assertEquals("photo", ShareIntake.sanitize("", fallback = "photo"))
    }

    @Test fun sanitizeDropsControlChars() {
        assertEquals("ab.txt", ShareIntake.sanitize("a\u0000b\u0007.txt"))
    }

    @Test fun uniqueNameNoCollision() {
        assertEquals("a.jpg", ShareIntake.uniqueName("a.jpg", setOf("b.jpg")))
    }

    @Test fun uniqueNameAppendsCounterBeforeExtension() {
        assertEquals("a (2).jpg", ShareIntake.uniqueName("a.jpg", setOf("a.jpg")))
        assertEquals("a (3).jpg", ShareIntake.uniqueName("a.jpg", setOf("a.jpg", "a (2).jpg")))
    }

    @Test fun uniqueNameNoExtension() {
        assertEquals("README (2)", ShareIntake.uniqueName("README", setOf("README")))
    }

    @Test fun uniqueNameCaseInsensitive() {
        assertEquals("a (2).jpg", ShareIntake.uniqueName("a.jpg", setOf("A.JPG")))
    }

    @Test fun uniqueNameDotfile() {
        assertEquals(".bashrc (2)", ShareIntake.uniqueName(".bashrc", setOf(".bashrc")))
    }

    @Test fun resolveCombinesSanitizeAndDedup() {
        assertEquals("x (2).jpg", ShareIntake.resolve("../x.jpg", setOf("x.jpg")))
    }
}
