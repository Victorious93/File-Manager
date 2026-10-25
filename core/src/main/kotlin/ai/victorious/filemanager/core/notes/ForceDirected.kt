package ai.victorious.filemanager.core.notes

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** 2D point for the notes graph (pure Kotlin — UI maps to Offset). */
data class Vec2(val x: Float, val y: Float) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(s: Float) = Vec2(x * s, y * s)
    fun length() = sqrt(x * x + y * y)
    fun normalized(): Vec2 {
        val l = length()
        return if (l < 1e-6f) Vec2(0f, 0f) else Vec2(x / l, y / l)
    }
}

/**
 * Simple force-directed layout (Fruchterman–Reingold style). Pure / unit-testable.
 * Call [step] repeatedly; pin nodes with [pinned] so the UI can drag them.
 */
class ForceDirectedLayout(
    nodeIds: List<String>,
    private val edges: List<Pair<String, String>>,
    seed: Int = nodeIds.size,
    private val area: Float = 800f,
) {
    private val rnd = Random(seed)
    val positions: MutableMap<String, Vec2> = nodeIds.associateWith { id ->
        val angle = rnd.nextDouble() * Math.PI * 2
        val r = 80.0 + rnd.nextDouble() * 200.0
        Vec2((cos(angle) * r).toFloat() + area / 2, (sin(angle) * r).toFloat() + area / 2)
    }.toMutableMap()

    /** Nodes the user is holding — skipped by the simulation. */
    val pinned: MutableSet<String> = mutableSetOf()

    fun step(iterations: Int = 1, temperature: Float = 40f) {
        if (positions.isEmpty()) return
        val k = sqrt((area * area) / positions.size.coerceAtLeast(1))
        repeat(iterations) {
            val disp = positions.keys.associateWith { Vec2(0f, 0f) }.toMutableMap()
            // Repulsion
            val ids = positions.keys.toList()
            for (i in ids.indices) {
                for (j in i + 1 until ids.size) {
                    val a = ids[i]; val b = ids[j]
                    val delta = positions.getValue(a) - positions.getValue(b)
                    var dist = delta.length().coerceAtLeast(0.01f)
                    val force = (k * k) / dist
                    val push = delta.normalized() * force
                    disp[a] = disp.getValue(a) + push
                    disp[b] = disp.getValue(b) - push
                }
            }
            // Attraction along edges
            for ((a, b) in edges) {
                if (a !in positions || b !in positions) continue
                val delta = positions.getValue(a) - positions.getValue(b)
                val dist = delta.length().coerceAtLeast(0.01f)
                val force = (dist * dist) / k
                val pull = delta.normalized() * force
                disp[a] = disp.getValue(a) - pull
                disp[b] = disp.getValue(b) + pull
            }
            // Apply with temperature clamp
            for ((id, d) in disp) {
                if (id in pinned) continue
                val len = d.length().coerceAtLeast(0.01f)
                val limited = d * (minOf(len, temperature) / len)
                positions[id] = positions.getValue(id) + limited
            }
        }
    }

    fun dragTo(id: String, x: Float, y: Float) {
        positions[id] = Vec2(x, y)
        pinned += id
    }

    fun release(id: String) { pinned -= id }
}
