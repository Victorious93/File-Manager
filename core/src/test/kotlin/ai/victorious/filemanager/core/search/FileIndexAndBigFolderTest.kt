package ai.victorious.filemanager.core.search

import ai.victorious.filemanager.core.fs.NioFileIo
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileIndexAndBigFolderTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun indexSearchTermsExtensionsAndPaths() {
        val root = tmp.newFolder("r")
        File(root, "Photos/2024").mkdirs()
        File(root, "Photos/2024/beach-day.jpg").writeText("x")
        File(root, "Photos/2024/beach-notes.txt").writeText("x")
        File(root, "Docs").mkdirs()
        File(root, "Docs/Beach Plan.pdf").writeText("x")
        File(root, ".victorious-trash/items").mkdirs()
        File(root, ".victorious-trash/items/beach-old.jpg").writeText("x")
        val idx = FileIndex(NioFileIo())
        assertTrue(idx.needsBuild(root.absolutePath))
        val snap = idx.build(root.absolutePath)
        assertEquals(6, snap.size) // trash skipped
        assertEquals(setOf("beach-day.jpg", "beach-notes.txt", "Beach Plan.pdf"), idx.search("beach").map { it.entry.name }.toSet())
        assertEquals(listOf("beach-day.jpg"), idx.search("beach *.jpg").map { it.entry.name })
        assertEquals(listOf("Beach Plan.pdf"), idx.search("beach plan").map { it.entry.name })
        assertEquals(listOf("beach-notes.txt"), idx.search("notes /photos").map { it.entry.name })
        assertTrue(idx.search("   ").isEmpty())
        idx.invalidate()
        assertTrue(idx.needsBuild(root.absolutePath))
    }

    /**
     * Big-folder benchmark: 50,000 files in one folder. Prints timings (see CI log) and keeps a
     * generous ceiling so slow CI machines do not flake; the point is to catch regressions to
     * O(n) stats-per-file or O(n log n) re-lowercasing.
     */
    @Test fun list50kFilesAndIndexSearchAreFast() {
        val dir = tmp.newFolder("big")
        val t0 = System.nanoTime()
        for (i in 0 until 50_000) File(dir, "file_%05d_%s.dat".format(i, if (i % 7 == 0) "photo" else "doc")).createNewFile()
        val createMs = (System.nanoTime() - t0) / 1_000_000
        val io = NioFileIo()
        io.list(dir.absolutePath) // warm-up (JIT + dentry cache)
        val runs = (1..3).map {
            val s = System.nanoTime(); val l = io.list(dir.absolutePath); assertEquals(50_000, l.size)
            (System.nanoTime() - s) / 1_000_000
        }.sorted()
        val listed = io.list(dir.absolutePath)
        assertEquals("file_00000_photo.dat", listed.first().name)
        assertEquals("file_49999_doc.dat", listed.last().name)

        val idx = FileIndex(NioFileIo())
        val b = System.nanoTime(); idx.build(tmp.root.absolutePath); val buildMs = (System.nanoTime() - b) / 1_000_000
        val q = System.nanoTime(); val hits = idx.search("photo", maxResults = 100_000); val searchMs = (System.nanoTime() - q) / 1_000_000
        assertEquals((0 until 50_000).count { it % 7 == 0 }, hits.size)
        println("BENCH big-folder: create=${createMs}ms list50k(median of 3)=${runs[1]}ms index-build=${buildMs}ms index-search=${searchMs}ms")
        assertTrue("list of 50k took ${runs[1]}ms", runs[1] < 8_000)
        assertTrue("index search took ${searchMs}ms", searchMs < 2_000)
    }
}
