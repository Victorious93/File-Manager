package ai.victorious.victorfiles.core.listing

import ai.victorious.victorfiles.core.fs.FileEntry
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
}
