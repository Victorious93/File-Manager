package ai.victorious.filemanager.core.diff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WordDiffTest {
    private fun join(s: List<InlineSegment>) = s.joinToString("") { it.text }

    @Test fun highlightsOnlyTheChangedWord() {
        val (l, r) = WordDiff.inline("val count = 10 // items", "val count = 12 // items")
        assertEquals("val count = 10 // items", join(l))
        assertEquals("val count = 12 // items", join(r))
        assertEquals(listOf("10"), l.filter { it.changed }.map { it.text })
        assertEquals(listOf("12"), r.filter { it.changed }.map { it.text })
    }

    @Test fun insertionsAndDeletionsAreOneSided() {
        val (l, r) = WordDiff.inline("call(a, b)", "call(a, b, c)")
        assertTrue(l.none { it.changed })
        assertEquals(", c", r.filter { it.changed }.joinToString("") { it.text })
    }

    @Test fun similarityAndEdgeCases() {
        assertEquals(1.0, WordDiff.similarity("same line", "same line"), 1e-9)
        assertEquals(1.0, WordDiff.similarity("", ""), 1e-9)
        assertTrue(WordDiff.similarity("alpha beta gamma", "zeta eta theta") < 0.3)
        val (l, r) = WordDiff.inline("", "new")
        assertTrue(l.isEmpty()); assertEquals(listOf(InlineSegment("new", true)), r)
        val huge = (1..5000).joinToString(" ")
        val (hl, hr) = WordDiff.inline(huge, huge + " x")
        assertEquals(1, hl.size); assertTrue(hl[0].changed); assertEquals(huge + " x", join(hr))
    }

    @Test fun tokensKeepEveryCharacter() {
        val s = "  if (x==1) {\tfoo_bar(); }  "
        assertEquals(s, WordDiff.tokens(s).joinToString(""))
    }
}
