package ai.victorious.filemanager.core.search

import ai.victorious.filemanager.core.fs.FileIoException
import ai.victorious.filemanager.core.fs.FileIo
import ai.victorious.filemanager.core.bookmarks.BookmarkStore
import ai.victorious.filemanager.core.fs.NioFileIo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileSearchAndBookmarksTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun searchMatchesNamesCaseInsensitiveAndSkipsTrash() {
        val root = tmp.newFolder("home")
        File(root, "Readme.md").writeText("x")
        File(root, "photos").mkdir()
        File(root, "photos/IMG_readme.JPG").writeText("y")
        File(root, ".victorious-trash").mkdir()
        File(root, ".victorious-trash/readme.bak").writeText("z")
        val search = FileSearch(NioFileIo())
        val hits = search.search(root.absolutePath, "readme")
        val names = hits.map { it.entry.name }
        assertTrue(names.contains("Readme.md"))
        assertTrue(names.contains("IMG_readme.JPG"))
        assertFalse(names.any { it.equals("readme.bak", ignoreCase = true) })
    }

    @Test
    fun bookmarksRoundTripAndDedupePath() {
        val storeFile = File(tmp.root, "bookmarks.json").absolutePath
        val store = BookmarkStore(storeFile, ids = {
            var n = 0
            { "b-${++n}" }
        }.let { it() }, clock = { 42L })
        val first = store.add("/sdcard/Download", "Downloads")
        val again = store.add("/sdcard/Download")
        assertEquals(first.id, again.id)
        assertEquals(1, store.list().size)
        assertTrue(store.isBookmarked("/sdcard/Download"))
        store.remove(first.id)
        assertTrue(store.list().isEmpty())
        assertFalse(store.isBookmarked("/sdcard/Download"))
    }

    @Test
    fun tornBookmarkFileDoesNotBlockAddingAndIsKeptForRecovery() {
        val file = File(tmp.root, "bookmarks.json").apply { writeText("[{\"id\":\"1\",\"pa") } // crash mid-write
        val store = BookmarkStore(file.absolutePath, NioFileIo())

        assertTrue(store.list().isEmpty())
        store.add("/sdcard/Download")

        assertEquals(listOf("/sdcard/Download"), store.list().map { it.path })
        assertTrue("the unreadable file is set aside, not destroyed", tmp.root.list()!!.any { it.startsWith("bookmarks.json.corrupt") })
    }

    @Test
    fun failedBookmarkWriteLeavesThePreviousListIntact() {
        val path = File(tmp.root, "bookmarks.json").absolutePath
        val good = BookmarkStore(path, NioFileIo())
        good.add("/sdcard/Music")

        val nio = NioFileIo()
        val dying = object : FileIo by nio {
            override fun writeText(path: String, text: String) {
                nio.writeText(path, text.take(10)) // disk full part-way through
                throw FileIoException("disk full")
            }
        }
        assertTrue(runCatching { BookmarkStore(path, dying).add("/sdcard/Movies") }.isFailure)

        assertEquals(listOf("/sdcard/Music"), good.list().map { it.path })
    }
}
