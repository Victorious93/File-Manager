package ai.victorious.filemanager.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareServerPinsTest {
    @Test fun passwordLengthAndCharset() {
        val p = ShareServerPins.randomPassword(12)
        assertEquals(12, p.length)
        assertTrue(p.all { it.isLetterOrDigit() })
    }
}
