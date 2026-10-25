package ai.victorious.filemanager.core.javadec

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DecompileCacheTest {
    @Test fun keyIsStableAndClassSensitive() {
        val a = DecompileCache.key("deadbeef", "com.x.Foo")
        assertEquals(a, DecompileCache.key("deadbeef", "com.x.Foo"))
        assertNotEquals(a, DecompileCache.key("deadbeef", "com.x.Bar"))
        assertNotEquals(a, DecompileCache.key("cafe", "com.x.Foo"))
    }

    @Test fun readWriteRoundTrip() {
        val dir = Files.createTempDirectory("dc").toFile()
        val key = DecompileCache.key("h", "com.x.Foo")
        assertNull(DecompileCache.read(dir, key))
        DecompileCache.write(dir, key, "class Foo {}", 1_000_000)
        assertEquals("class Foo {}", DecompileCache.read(dir, key))
    }

    @Test fun budgetEvictsOldest() {
        val dir = Files.createTempDirectory("dc").toFile()
        val big = "x".repeat(600)
        val k1 = DecompileCache.key("h", "A"); DecompileCache.write(dir, k1, big, 2_000)
        Thread.sleep(5)
        val k2 = DecompileCache.key("h", "B"); DecompileCache.write(dir, k2, big, 2_000)
        Thread.sleep(5)
        // Writing a third 600B file exceeds 2000B budget cap -> oldest (A) evicted.
        val k3 = DecompileCache.key("h", "C"); DecompileCache.write(dir, k3, big, 1_500)
        assertNull("oldest evicted", DecompileCache.read(dir, k1))
        assertNotNull(DecompileCache.read(dir, k3))
    }
}
