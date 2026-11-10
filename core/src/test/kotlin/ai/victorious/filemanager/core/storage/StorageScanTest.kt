package ai.victorious.filemanager.core.storage

import ai.victorious.filemanager.core.search.FileCategory
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StorageScanTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun tree(): File {
        val root = tmp.newFolder("root")
        File(root, "small.bin").writeBytes(ByteArray(10))
        File(root, "big/deep").mkdirs()
        File(root, "big/a.jpg").writeBytes(ByteArray(100))
        File(root, "big/deep/b.mp4").writeBytes(ByteArray(400))
        File(root, "mid").mkdirs(); File(root, "mid/c.pdf").writeBytes(ByteArray(50))
        File(root, "emptydir").mkdirs()
        return root
    }

    @Test fun oneWalkGivesEveryFolderSize() {
        val root = tree()
        val t = StorageScan.scan(root)
        assertEquals(560L, t.root.bytes)
        assertEquals(4, t.root.files)
        assertEquals(listOf("big", "mid", "emptydir"), t.root.children.map { it.name })
        assertEquals(500L, t.node(File(root, "big"))!!.bytes)
        assertEquals(400L, t.node(File(root, "big/deep"))!!.bytes)
        assertEquals(5, t.folders)
        assertEquals(0, t.skipped)
        // Same numbers as the per-folder analyzer, without walking again.
        val b = StorageScan.breakdown(t, root)!!
        val old = StorageAnalyzer.analyze(root)
        assertEquals(old.total, b.total)
        assertEquals(old.entries.map { it.file.name to it.bytes }, b.entries.map { it.file.name to it.bytes })
        assertEquals(listOf("deep", "a.jpg"), StorageScan.breakdown(t, File(root, "big"))!!.entries.map { it.file.name })
        assertNull(StorageScan.breakdown(t, tmp.newFolder("elsewhere")))
    }

    @Test fun largestFilesAndTypes() {
        val root = tree()
        val t = StorageScan.scan(root, topFiles = 2)
        assertEquals(listOf("b.mp4", "a.jpg"), t.largest.map { File(it.path).name })
        assertEquals(400L, t.byCategory[FileCategory.VIDEO]!!.bytes)
        assertEquals(100L, t.byCategory[FileCategory.IMAGE]!!.bytes)
        assertEquals(50L, t.byCategory[FileCategory.DOCUMENT]!!.bytes)
        assertEquals(10L, t.byCategory[null]!!.bytes)
        assertEquals(FileCategory.VIDEO, t.categoriesBySize().first().first)
    }

    @Test fun symlinksAreNotFollowedOrCounted() {
        val root = tree()
        val outside = tmp.newFolder("outside").also { File(it, "huge").writeBytes(ByteArray(10_000)) }
        runCatching {
            Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
            Files.createSymbolicLink(File(root, "loop").toPath(), root.toPath())
        }
        val t = StorageScan.scan(root)
        assertEquals(560L, t.root.bytes)
    }

    @Test fun progressAndCancel() {
        val root = tree()
        repeat(3000) { File(root, "mid/f$it").writeBytes(ByteArray(1)) }
        var reports = 0
        val t = StorageScan.scan(root, onProgress = { reports++ })
        assertTrue(reports >= 1)
        assertEquals(3004, t.root.files)
        var calls = 0
        try {
            StorageScan.scan(root, isCancelled = { ++calls > 2 })
            fail("expected cancel")
        } catch (e: CancellationException) { /* expected */ }
        assertNotNull(t.node(File(root, "mid")))
    }
}
