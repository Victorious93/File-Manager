package ai.victorious.filemanager.core.fs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** Copying folders that contain, or are, symbolic links. */
class NioFileIoSymlinkCopyTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val io = NioFileIo()
    private lateinit var root: File

    @Before
    fun setUp() {
        root = tmp.newFolder("w")
        // Creating links needs a privilege on some platforms; the behaviour under test is meaningless without them.
        val probe = File(root, "probe-target").apply { writeText("x") }
        val ok = runCatching { Files.createSymbolicLink(File(root, "probe-link").toPath(), probe.toPath()) }.isSuccess
        assumeTrue("symbolic links are not available here", ok)
        File(root, "probe-link").delete()
        probe.delete()
    }

    private fun link(at: File, target: String) {
        Files.createSymbolicLink(at.toPath(), Path.of(target))
    }

    private fun isLink(f: File) = Files.isSymbolicLink(f.toPath())

    /** real/{a.txt, sub/b.txt} */
    private fun makeFolder(name: String = "real"): File {
        val d = File(root, name).apply { mkdirs() }
        File(d, "a.txt").writeText("alpha")
        File(d, "sub").mkdirs()
        File(d, "sub/b.txt").writeText("beta")
        return d
    }

    @Test
    fun copyingALinkToAFolderCopiesTheFolderContents() {
        val real = makeFolder()
        link(File(root, "shortcut"), real.absolutePath)
        val out = File(root, "out")

        io.copy(File(root, "shortcut").absolutePath, out.absolutePath)

        assertTrue("the copy is a real folder, not another link", out.isDirectory && !isLink(out))
        assertEquals("alpha", File(out, "a.txt").readText())
        assertEquals("beta", File(out, "sub/b.txt").readText())
        // Independent of the original: changing the copy leaves the source alone.
        File(out, "a.txt").writeText("changed")
        assertEquals("alpha", File(real, "a.txt").readText())
    }

    @Test
    fun aLinkToAFolderInsideACopiedFolderIsFollowed() {
        makeFolder()
        val tree = File(root, "tree").apply { mkdirs() }
        File(tree, "plain.txt").writeText("p")
        link(File(tree, "shortcut"), "../real")
        val out = File(root, "out")

        io.copy(tree.absolutePath, out.absolutePath)

        assertEquals("p", File(out, "plain.txt").readText())
        assertTrue(File(out, "shortcut").isDirectory && !isLink(File(out, "shortcut")))
        assertEquals("alpha", File(out, "shortcut/a.txt").readText())
        assertEquals("beta", File(out, "shortcut/sub/b.txt").readText())
    }

    @Test(timeout = 10_000)
    fun aLinkBackUpToTheFolderBeingCopiedDoesNotLoop() {
        val tree = File(root, "tree").apply { mkdirs() }
        File(tree, "f.txt").writeText("f")
        File(tree, "sub").mkdirs()
        link(File(tree, "sub/up"), "..") // points at tree itself
        val out = File(root, "out")

        io.copy(tree.absolutePath, out.absolutePath)

        assertEquals("f", File(out, "f.txt").readText())
        assertTrue(File(out, "sub").isDirectory)
        // Files.walk does not follow links (File.walkTopDown does, and would count the looping link forever).
        val files = Files.walk(out.toPath()).use { w -> w.filter { Files.isRegularFile(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) }.count() }
        assertEquals("only the one real file was copied", 1L, files)
        assertTrue("the link back up was kept as a link, not followed", isLink(File(out, "sub/up")))
    }

    @Test(timeout = 10_000)
    fun aLinkToTheParentOfTheFolderBeingCopiedDoesNotCopyTheFolderIntoItself() {
        val tree = File(root, "tree").apply { mkdirs() }
        File(tree, "f.txt").writeText("f")
        link(File(tree, "up"), "..") // points at the folder that holds tree
        File(root, "sibling.txt").writeText("s")
        val out = File(tmp.newFolder("elsewhere"), "out")

        io.copy(tree.absolutePath, out.absolutePath)

        assertEquals("f", File(out, "f.txt").readText())
        assertEquals("s", File(out, "up/sibling.txt").readText())
        assertFalse("tree is not copied again inside its own copy", File(out, "up/tree").exists())
    }

    @Test
    fun aDanglingLinkDoesNotFailTheWholeCopy() {
        val tree = File(root, "tree").apply { mkdirs() }
        File(tree, "f.txt").writeText("f")
        link(File(tree, "dead"), "does-not-exist")
        val out = File(root, "out")

        io.copy(tree.absolutePath, out.absolutePath)

        assertEquals("f", File(out, "f.txt").readText())
        // A link with no content is kept as a link (where the destination can hold one), not turned into an error.
        assertTrue(isLink(File(out, "dead")))
        assertEquals("does-not-exist", Files.readSymbolicLink(File(out, "dead").toPath()).toString())
    }

    @Test
    fun aLinkToAFileStillCopiesTheFileContent() {
        val tree = File(root, "tree").apply { mkdirs() }
        File(tree, "real.txt").writeText("data")
        link(File(tree, "alias.txt"), "real.txt")
        val out = File(root, "out")

        io.copy(tree.absolutePath, out.absolutePath)

        assertEquals("data", File(out, "alias.txt").readText())
        assertFalse(isLink(File(out, "alias.txt")))
    }

    @Test
    fun movingALinkToAFolderAcrossAFailedRenameKeepsTheContentAndRemovesOnlyTheLink() {
        val real = makeFolder()
        val shortcut = File(root, "shortcut").also { link(it, real.absolutePath) }
        val out = File(root, "out")

        // copy + delete is what move() falls back to when a plain rename is not possible
        io.copy(shortcut.absolutePath, out.absolutePath)
        io.deleteRecursively(shortcut.absolutePath)

        assertFalse(isLink(shortcut) || shortcut.exists())
        assertEquals("the link's target is untouched", "alpha", File(real, "a.txt").readText())
        assertEquals("alpha", File(out, "a.txt").readText())
    }
}
