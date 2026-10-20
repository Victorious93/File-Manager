package ai.victorious.filemanager.core.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForceDirectedTest {
    @Test fun stepsMoveConnectedNodesCloserOverTime() {
        val layout = ForceDirectedLayout(
            nodeIds = listOf("a", "b", "c"),
            edges = listOf("a" to "b", "b" to "c"),
            seed = 1,
        )
        val before = (layout.positions.getValue("a") - layout.positions.getValue("b")).length()
        layout.step(80, temperature = 50f)
        val after = (layout.positions.getValue("a") - layout.positions.getValue("b")).length()
        assertTrue("expected attraction to shrink distance: $before -> $after", after < before || after < 250f)
    }

    @Test fun pinnedNodeDoesNotMove() {
        val layout = ForceDirectedLayout(listOf("a", "b"), listOf("a" to "b"), seed = 2)
        layout.dragTo("a", 10f, 20f)
        val pinned = layout.positions.getValue("a")
        layout.step(30, temperature = 80f)
        assertEquals(pinned, layout.positions.getValue("a"))
        layout.release("a")
        layout.step(5, temperature = 40f)
        // After release it may move
        assertTrue(layout.pinned.isEmpty())
    }
}
