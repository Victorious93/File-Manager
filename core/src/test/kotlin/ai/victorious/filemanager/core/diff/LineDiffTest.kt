package ai.victorious.filemanager.core.diff

import org.junit.Assert.assertEquals
import org.junit.Test

class LineDiffTest {
    private fun apply(d: List<DiffLine>) = d.filter { it.op != DiffOp.DELETE }.map { it.text }
    private fun original(d: List<DiffLine>) = d.filter { it.op != DiffOp.INSERT }.map { it.text }

    @Test fun identical() {
        val d = LineDiff.diff("a\nb\n", "a\nb")
        assertEquals(listOf(DiffOp.EQUAL, DiffOp.EQUAL), d.map { it.op })
    }

    @Test fun insertDeleteChange() {
        val d = LineDiff.diff("a\nb\nc\nd", "a\nx\nc\nd\ne")
        assertEquals(" a\n-b\n+x\n c\n d\n+e", LineDiff.unified(d))
        assertEquals(2 to 1, LineDiff.stats(d))
        val e = d.last()
        assertEquals(null, e.oldLine); assertEquals(5, e.newLine)
    }

    @Test fun emptySides() {
        assertEquals(listOf(DiffOp.INSERT, DiffOp.INSERT), LineDiff.diff("", "a\nb").map { it.op })
        assertEquals(listOf(DiffOp.DELETE), LineDiff.diff("a", "").map { it.op })
    }

    @Test fun reconstructsBothSidesRandomized() {
        val rnd = java.util.Random(7)
        repeat(200) {
            val a = List(rnd.nextInt(15)) { "l" + rnd.nextInt(4) }
            val b = List(rnd.nextInt(15)) { "l" + rnd.nextInt(4) }
            val d = LineDiff.diff(a, b)
            assertEquals(a, original(d)); assertEquals(b, apply(d))
            // Line numbers are consistent.
            assertEquals(a.indices.map { it + 1 }, d.mapNotNull { it.oldLine })
            assertEquals(b.indices.map { it + 1 }, d.mapNotNull { it.newLine })
        }
    }

    @Test fun sideBySidePairsChanges() {
        val rows = LineDiff.sideBySide(LineDiff.diff("a\nb\nc", "a\nB\nc"))
        assertEquals(3, rows.size)
        assertEquals("b", rows[1].left!!.text); assertEquals("B", rows[1].right!!.text)
    }
}
