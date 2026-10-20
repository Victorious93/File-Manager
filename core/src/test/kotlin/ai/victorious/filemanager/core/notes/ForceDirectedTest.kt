package ai.victorious.filemanager.core.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForceDirectedTest {
    @Test fun simulationRunsAndKeepsAllNodes() {
        val layout = ForceDirectedLayout(
            nodeIds = listOf("a", "b", "c"),
            edges = listOf("a" to "b", "b" to "c"),
            seed = 1,
        )
        val before = layout.positions.toMap()
        layout.step(40, temperature = 50f)
        assertEquals(setOf("a", "b", "c"), layout.positions.keys)
        // At least one free node should have moved under repulsion/attraction.
        assertTrue(layout.positions.any { (id, p) -> before.getValue(id) != p })
    }

    @Test fun pinnedNodeDoesNotMove() {
        val layout = ForceDirectedLayout(listOf("a", "b"), listOf("a" to "b"), seed = 2)
        layout.dragTo("a", 10f, 20f)
        val pinned = layout.positions.getValue("a")
        layout.step(30, temperature = 80f)
        assertEquals(pinned, layout.positions.getValue("a"))
        layout.release("a")
        layout.step(5, temperature = 40f)
        assertTrue(layout.pinned.isEmpty())
        assertNotEquals(Offsetish, layout.positions.getValue("b")) // smoke: b still present
        assertTrue(layout.positions.containsKey("b"))
    }

    private companion object {
        val Offsetish = Vec2(-999f, -999f)
    }
}
