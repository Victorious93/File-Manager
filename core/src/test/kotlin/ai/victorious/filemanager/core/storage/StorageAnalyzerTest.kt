package ai.victorious.filemanager.core.storage

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class StorageAnalyzerTest {
    @Test fun aggregatesRecursivelyAndSorts() {
        val root = Files.createTempDirectory("sa").toFile()
        File(root, "small.bin").writeBytes(ByteArray(10))
        File(root, "big/deep").mkdirs()
        File(root, "big/a").writeBytes(ByteArray(100))
        File(root, "big/deep/b").writeBytes(ByteArray(400))
        File(root, "mid").mkdirs(); File(root, "mid/c").writeBytes(ByteArray(50))
        File(root, "emptydir").mkdirs()
        val r = StorageAnalyzer.analyze(root)
        assertEquals(560L, r.total)
        assertEquals(listOf("big", "mid", "small.bin", "emptydir"), r.entries.map { it.file.name })
        assertEquals(2, r.entries[0].fileCount)
        assertEquals(500f / 560f, r.fraction(r.entries[0]), 0.0001f)
        root.deleteRecursively()
    }

    @Test fun humanSizes() {
        assertEquals("512 B", StorageAnalyzer.human(512))
        assertEquals("1.5 KB", StorageAnalyzer.human(1536))
        assertEquals("2.0 GB", StorageAnalyzer.human(2L shl 30))
    }
}
