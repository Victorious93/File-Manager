package ai.victorious.victorfiles.core.dupes

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicateFinderTest {
    @Test fun groupsBySizeThenHash() {
        val root = Files.createTempDirectory("dupes").toFile()
        fun f(name: String, content: String) = File(root, name).apply { parentFile.mkdirs(); writeText(content) }
        val a = f("a.txt", "hello")
        val b = f("sub/b.txt", "hello")
        val c = f("c.txt", "hellO") // same size, different content
        f("d.txt", "unique-longer")
        f("empty1", ""); f("empty2", "")
        val big1 = File(root, "big1").apply { writeBytes(ByteArray(200_000) { (it % 7).toByte() }) }
        val big2 = File(root, "big2").apply { writeBytes(ByteArray(200_000) { (it % 7).toByte() }) }
        File(root, "big3").writeBytes(ByteArray(200_000) { (it % 7).toByte() }.also { it[199_999] = 99 })

        val groups = DuplicateFinder.scan(root)
        assertEquals(2, groups.size)
        assertEquals(setOf(big1, big2), groups[0].files.toSet()) // largest wasted first
        assertEquals(setOf(a, b), groups[1].files.toSet())
        assertTrue(groups.none { c in it.files })
        assertEquals(5L, groups[1].wasted)

        val sel = DuplicateFinder.defaultSelection(groups)
        assertEquals(2, sel.size)
        assertTrue(a !in sel && b in sel)
        root.deleteRecursively()
    }
}
