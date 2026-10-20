package ai.victorious.victorfiles.core.props

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class FilePropertiesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun knownDigests() {
        val f = tmp.newFile("abc.txt").apply { writeText("abc") }
        val h = FileProperties.checksums(f)
        assertEquals("900150983cd24fb0d6963f7d28e17f72", h[HashAlgorithm.MD5])
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", h[HashAlgorithm.SHA1])
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", h[HashAlgorithm.SHA256])
    }

    @Test
    fun emptyFileDigest() {
        val f = tmp.newFile("empty")
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", FileProperties.checksums(f, setOf(HashAlgorithm.MD5))[HashAlgorithm.MD5])
    }

    @Test
    fun folderStatsCountsRecursivelyAndIgnoresSymlinkLoops() {
        val root = tmp.newFolder("root")
        File(root, "a.bin").writeBytes(ByteArray(10))
        File(root, "sub/deep").mkdirs()
        File(root, "sub/deep/b.bin").writeBytes(ByteArray(5))
        runCatching { Files.createSymbolicLink(File(root, "loop").toPath(), root.toPath()) }
        val s = FileProperties.folderStats(root)
        assertEquals(15L, s.bytes)
        assertEquals(2L, s.folders)
        assertTrue(s.files in 2L..3L) // symlink (if the FS allowed one) counts as a file
    }

    @Test
    fun matchesIgnoresCaseSpacesAndColons() {
        assertTrue(FileProperties.matches("90:01 50 98", "90015098"))
        assertTrue(FileProperties.matches("ABCDEF", "abcdef"))
        assertFalse(FileProperties.matches("", ""))
        assertFalse(FileProperties.matches("abc", "abd"))
    }
}
