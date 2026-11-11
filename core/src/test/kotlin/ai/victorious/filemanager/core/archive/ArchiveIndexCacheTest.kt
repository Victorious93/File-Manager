package ai.victorious.filemanager.core.archive

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArchiveIndexCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    @Before @After fun reset() = ArchiveIndexCache.clear()

    private fun zip(name: String, vararg entries: String): File {
        val f = File(tmp.root, name)
        ZipOutputStream(f.outputStream()).use { z -> for (n in entries) { z.putNextEntry(ZipEntry(n)); z.write(1); z.closeEntry() } }
        return f
    }

    private class CountingLoader {
        var reads = 0
        fun load(f: File): List<ArchiveItem> { reads++; return Archives.list(f) }
    }

    @Test fun secondListingOfAnUnchangedArchiveIsServedFromCache() {
        val f = zip("a.zip", "x", "y")
        val loader = CountingLoader()
        val first = ArchiveIndexCache.list(f, loader::load)
        val second = ArchiveIndexCache.list(f, loader::load)
        assertEquals(1, loader.reads)
        assertSame(first, second)
    }

    @Test fun changedFileIsReread() {
        val f = zip("a.zip", "x")
        val loader = CountingLoader()
        ArchiveIndexCache.list(f, loader::load)
        zip("a.zip", "x", "y", "z")
        f.setLastModified(f.lastModified() + 5_000)
        assertEquals(3, ArchiveIndexCache.list(f, loader::load).size)
        assertEquals(2, loader.reads)
    }

    @Test fun explicitInvalidateForcesAReread() {
        val f = zip("a.zip", "x")
        val loader = CountingLoader()
        ArchiveIndexCache.list(f, loader::load)
        ArchiveIndexCache.invalidate(f)
        ArchiveIndexCache.list(f, loader::load)
        assertEquals(2, loader.reads)
    }

    @Test fun zipEditorInvalidatesTheCachedListing() {
        val f = zip("a.zip", "keep.txt", "drop.txt")
        assertEquals(2, ArchiveIndexCache.list(f).size)
        ZipEditor.apply(f, listOf(ZipEdit.Delete("drop.txt")))
        assertEquals(listOf("keep.txt"), ArchiveIndexCache.list(f).map { it.name })
    }

    @Test fun failuresAreNotCached() {
        val f = zip("a.zip", "x")
        var calls = 0
        try { ArchiveIndexCache.list(f) { calls++; error("boom") } } catch (_: IllegalStateException) {}
        ArchiveIndexCache.list(f) { calls++; Archives.list(it) }
        assertEquals(2, calls)
    }

    @Test fun cacheIsBoundedByArchiveCount() {
        repeat(20) { ArchiveIndexCache.list(zip("a$it.zip", "x")) }
        assertEquals(8, ArchiveIndexCache.cachedArchives())
    }

    @Test fun listCachedMatchesList() {
        val f = zip("a.zip", "a/b.txt", "c.txt")
        assertEquals(Archives.list(f), Archives.listCached(f))
    }
}
