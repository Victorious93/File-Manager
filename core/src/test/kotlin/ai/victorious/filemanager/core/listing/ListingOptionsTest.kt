package ai.victorious.filemanager.core.listing

import ai.victorious.filemanager.core.fs.FileEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class ListingOptionsTest {
    private fun f(name: String, size: Long = 0, mod: Long = 0, dir: Boolean = false, hidden: Boolean = name.startsWith(".")) =
        FileEntry("/x/$name", name, dir, size, mod, hidden)

    private fun names(o: ListingOptions, list: List<FileEntry>) = o.apply(list).map { it.name }

    @Test
    fun naturalNameSortAndFoldersFirst() {
        val list = listOf(f("file10"), f("file2"), f("Zeta", dir = true), f("alpha", dir = true), f("File1"))
        assertEquals(listOf("alpha", "Zeta", "File1", "file2", "file10"), names(ListingOptions(), list))
    }

    @Test
    fun descendingAndFoldersFirstOff() {
        val list = listOf(f("b"), f("a", dir = true), f("c"))
        assertEquals(listOf("c", "b", "a"), names(ListingOptions(ascending = false, foldersFirst = false), list))
        assertEquals(listOf("a", "c", "b"), names(ListingOptions(ascending = false), list))
    }

    @Test
    fun sortBySizeModifiedType() {
        val list = listOf(f("b.txt", size = 5, mod = 3), f("a.zip", size = 9, mod = 1), f("c.apk", size = 1, mod = 2))
        assertEquals(listOf("c.apk", "b.txt", "a.zip"), names(ListingOptions(SortKey.SIZE), list))
        assertEquals(listOf("a.zip", "c.apk", "b.txt"), names(ListingOptions(SortKey.MODIFIED), list))
        assertEquals(listOf("c.apk", "b.txt", "a.zip"), names(ListingOptions(SortKey.TYPE), list))
    }

    @Test
    fun hiddenAndFilter() {
        val list = listOf(f(".secret"), f("Photo.JPG"), f("notes.txt"))
        assertEquals(listOf("notes.txt", "Photo.JPG"), names(ListingOptions(showHidden = false), list))
        assertEquals(listOf("Photo.JPG"), names(ListingOptions(filter = "jpg"), list))
        assertEquals(emptyList<String>(), names(ListingOptions(showHidden = false, filter = "secret"), list))
    }

    @Test
    fun leadingZerosAndTies() {
        assertEquals(true, ListingOptions.naturalCompare("a02", "a10") < 0)
        assertEquals(true, ListingOptions.naturalCompare("a2", "a02") != 0)
    }

    @Test fun duplicatePaths_areCollapsed_soLazyListKeysStayUnique() {
        // LazyColumn/LazyVerticalGrid use the path as key and crash on duplicates
        // (some remote/archive listings report the same entry twice).
        val out = ListingOptions().apply(listOf(f("a.txt"), f("b.txt"), f("a.txt")))
        org.junit.Assert.assertEquals(listOf("a.txt", "b.txt"), out.map { it.name })
    }

    @Test fun tenThousandEntries_sortQuickly() {
        val many = (0 until 10_000).map { f("file$it.txt") }.shuffled(java.util.Random(1))
        val t0 = System.nanoTime()
        val out = ListingOptions().apply(many)
        val ms = (System.nanoTime() - t0) / 1_000_000
        org.junit.Assert.assertEquals("file0.txt", out.first().name)
        org.junit.Assert.assertEquals("file9999.txt", out.last().name)
        org.junit.Assert.assertTrue("sorting 10k took $ms ms", ms < 2_000)
    }
}
