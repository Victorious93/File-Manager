package ai.victorious.filemanager.core.hex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HexSearchTest {
    private val doc = HexDocument("abcXYZabcXYZ".toByteArray())

    @Test fun parsesHexAndWildcards() {
        assertNotNull(HexPattern.fromHex("61 62 ?? 58"))
        assertNotNull(HexPattern.fromHex("6162??58"))
        assertNull(HexPattern.fromHex(""))
        assertNull(HexPattern.fromHex("6"))
        assertNull(HexPattern.fromHex("ZZ"))
        assertNull(HexPattern.fromHex("?? ??"))
    }

    @Test fun findsForwardBackwardAndWraps() {
        val p = HexPattern.fromText("XYZ")!!
        assertEquals(3, HexSearch.find(doc, p, 0))
        assertEquals(9, HexSearch.find(doc, p, 4))
        // "Next" from the last match must wrap, not re-find it.
        assertEquals(3, HexSearch.find(doc, p, 10))
        assertEquals(-1, HexSearch.find(doc, p, 10, wrap = false))
        // "Previous" from before the first match must wrap to the last one.
        assertEquals(9, HexSearch.find(doc, p, -1, forward = false))
        assertEquals(-1, HexSearch.find(doc, p, -1, forward = false, wrap = false))
        val abc = HexPattern.fromText("abc")!!
        assertEquals(-1, HexSearch.find(doc, abc, 7, forward = true, wrap = false))
        assertEquals(0, HexSearch.find(doc, abc, 7, forward = true, wrap = true))
        assertEquals(3, HexSearch.find(doc, p, 8, forward = false))
        assertEquals(9, HexSearch.find(doc, p, 2, forward = false, wrap = true))
        assertEquals(-1, HexSearch.find(doc, p, 2, forward = false, wrap = false))
    }

    @Test fun wildcardMatches() {
        val p = HexPattern.fromHex("61 ?? 63")!!
        assertEquals(listOf(0, 6), HexSearch.findAll(doc, p))
    }

    @Test fun seesUnsavedEdits() {
        val p = HexPattern.fromText("abcQ")!!
        assertEquals(-1, HexSearch.find(doc, p, 0))
        doc.set(3, 'Q'.code.toByte())
        assertEquals(0, HexSearch.find(doc, p, 0))
    }

    @Test fun patternLongerThanDocAndLimit() {
        assertEquals(-1, HexSearch.find(HexDocument(byteArrayOf(1)), HexPattern.fromHex("0102")!!, 0))
        val big = HexDocument(ByteArray(100) { 7 })
        assertEquals(5, HexSearch.findAll(big, HexPattern.fromHex("07")!!, limit = 5).size)
    }
}
