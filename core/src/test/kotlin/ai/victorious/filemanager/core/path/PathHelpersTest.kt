package ai.victorious.filemanager.core.path

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PathHelpersTest {
    @Test
    fun normalizeCollapsesDotsAndSeps() {
        assertEquals("/home/user", PathHelpers.normalize("/home/user/./docs/../"))
        assertEquals("/home/user/docs", PathHelpers.normalize("/home/user/./docs"))
        assertEquals("/a/b", PathHelpers.normalize("/a//b/"))
        assertEquals("/", PathHelpers.normalize("/"))
    }

    @Test
    fun parentAndName() {
        assertEquals("/home", PathHelpers.parentOf("/home/user"))
        assertEquals("user", PathHelpers.fileName("/home/user"))
        assertEquals("/", PathHelpers.parentOf("/home"))
        assertNull(PathHelpers.parentOf("/"))
    }

    @Test
    fun joinAndValidateNames() {
        assertEquals("/home/user/file.txt", PathHelpers.join("/home/user", "file.txt"))
        assertTrue(PathHelpers.isValidFileName("ok.txt"))
        assertFalse(PathHelpers.isValidFileName("a/b"))
        assertFalse(PathHelpers.isValidFileName(".."))
        assertFalse(PathHelpers.isValidFileName(""))
    }

    @Test
    fun uniqueChildNameAddsNumericSuffix() {
        val existing = setOf("photo.jpg", "photo (1).jpg")
        assertEquals("photo (2).jpg", PathHelpers.uniqueChildName(existing, "photo.jpg"))
        assertEquals("notes", PathHelpers.uniqueChildName(existing, "notes"))
    }

    @Test
    fun hiddenDotfilesKeepWholeNameAsBase() {
        assertEquals(".gitignore" to "", PathHelpers.splitBaseExt(".gitignore"))
        assertEquals("archive.tar" to ".gz", PathHelpers.splitBaseExt("archive.tar.gz"))
    }

    @Test
    fun selfOrDescendant() {
        assertTrue(PathHelpers.isSelfOrDescendant("/a/b/c", "/a/b"))
        assertTrue(PathHelpers.isSelfOrDescendant("/a/b", "/a/b"))
        assertFalse(PathHelpers.isSelfOrDescendant("/a/b", "/a/be"))
        assertFalse(PathHelpers.isSelfOrDescendant("/a", "/a/b"))
    }
}
