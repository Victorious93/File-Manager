package ai.victorious.filemanager.core.search

import ai.victorious.filemanager.core.fs.NioFileIo
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileIndexFiltersTest {
    @get:Rule val tmp = TemporaryFolder()
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    private fun file(root: File, rel: String, bytes: Int, ageDays: Int) {
        val f = File(root, rel); f.parentFile.mkdirs(); f.writeBytes(ByteArray(bytes))
        f.setLastModified(now - ageDays * day)
    }

    private fun index(): FileIndex {
        val root = tmp.newFolder("r")
        file(root, "pics/a.jpg", 5_000, 1)
        file(root, "pics/b.PNG", 2_000_000, 40)
        file(root, "vid/c.mp4", 3_000_000, 2)
        file(root, "docs/d.pdf", 800, 400)
        file(root, "apps/e.apk", 10_000_000, 3)
        file(root, "docs/notes.txt", 10, 0)
        File(root, "emptydir").mkdirs()
        return FileIndex(NioFileIo(), clock = { now }).also { it.build(root.absolutePath) }
    }

    private fun names(idx: FileIndex, q: String) = idx.search(q).map { it.entry.name }.toSet()

    @Test fun categoriesByType() {
        val idx = index()
        assertEquals(setOf("a.jpg", "b.PNG"), names(idx, "type:image"))
        assertEquals(setOf("c.mp4"), names(idx, "type:videos"))
        assertEquals(setOf("e.apk"), names(idx, "type:apk"))
        assertEquals(setOf("d.pdf", "notes.txt"), names(idx, "type:doc"))
        assertEquals(setOf("pics", "vid", "docs", "apps", "emptydir"), names(idx, "type:folder"))
    }

    @Test fun sizeFiltersExcludeFolders() {
        val idx = index()
        assertEquals(setOf("b.PNG", "c.mp4", "e.apk"), names(idx, "size>1mb"))
        assertEquals(setOf("e.apk"), names(idx, "size>5m"))
        assertEquals(setOf("d.pdf", "notes.txt"), names(idx, "size<1k"))
        assertEquals(setOf("c.mp4"), names(idx, "size>1mb size<5mb type:video"))
    }

    @Test fun dateFilters() {
        val idx = index()
        assertEquals(setOf("a.jpg", "c.mp4", "e.apk", "notes.txt"), names(idx, "newer:7d"))
        assertEquals(setOf("d.pdf"), names(idx, "older:1y"))
        assertEquals(setOf("b.PNG"), names(idx, "older:30d newer:90d"))
    }

    @Test fun filtersCombineWithTerms() {
        val idx = index()
        assertEquals(setOf("b.PNG"), names(idx, "b type:image size>1m"))
        assertTrue(names(idx, "zzz type:image").isEmpty())
    }

    @Test fun malformedFiltersFallBackToPlainTerms() {
        val idx = index()
        assertTrue(names(idx, "size>abc").isEmpty())
        assertTrue(names(idx, "type:nonsense").isEmpty())
        assertEquals(null, FileIndex.parseSize("12x"))
        assertNull(FileIndex.parseAge("7"))
        assertEquals(10L * 1024 * 1024, FileIndex.parseSize("10MB"))
        assertEquals(7 * day, FileIndex.parseAge("7d"))
    }

    @Test fun recentIsNewestFirstFilesOnly() {
        val idx = index()
        assertEquals(listOf("notes.txt", "a.jpg", "c.mp4", "e.apk"), idx.recent(now - 7 * day).map { it.entry.name })
        assertEquals(2, idx.recent(limit = 2).size)
    }

    @Test fun existingQueriesStillWork() {
        val idx = index()
        assertEquals(setOf("a.jpg"), names(idx, "a *.jpg"))
        assertTrue(FileIndex.matches(FileIndex.parse("beach")!!, "beach.jpg", "beach.jpg"))
    }
}
