package ai.victorious.filemanager.core.archive

import java.io.File
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArchiveWriterTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun tree(): List<File> {
        val dir = tmp.newFolder("photos")
        File(dir, "a.txt").writeText("alpha ".repeat(500))
        File(dir, "sub").mkdirs()
        File(dir, "sub/b.bin").writeBytes(Random(7).nextBytes(40_000))
        File(dir, "empty").mkdirs()
        File(dir, "deep/x/y/z").mkdirs()
        File(dir, "deep/x/y/z/" + "long-name-".repeat(12) + ".txt").writeText("long path")
        val single = tmp.newFile("readme.md").apply { writeText("hi") }
        return listOf(dir, single)
    }

    private fun assertSameTree(src: File, extracted: File) {
        src.walkTopDown().forEach { f ->
            val rel = f.relativeTo(src.parentFile).path
            val other = File(extracted, rel)
            assertTrue("missing $rel", other.exists())
            if (f.isFile) assertTrue("content $rel", f.readBytes().contentEquals(other.readBytes()))
            else assertTrue("dir $rel", other.isDirectory)
        }
    }

    @Test fun everyFormatRoundTripsThroughTheReader() {
        val expectedFormat = mapOf(
            CreateFormat.ZIP to ArchiveFormat.ZIP,
            CreateFormat.SEVEN_Z to ArchiveFormat.SEVEN_Z,
            CreateFormat.TAR to ArchiveFormat.TAR,
            CreateFormat.TAR_GZ to ArchiveFormat.TAR_GZ,
            CreateFormat.TAR_BZ2 to ArchiveFormat.TAR_BZ2,
            CreateFormat.TAR_XZ to ArchiveFormat.TAR_XZ,
        )
        val sources = tree()
        for (format in CreateFormat.entries) {
            val out = ArchiveWriter.uniqueName(tmp.root, "bundle-${format.name}", format)
            val r = ArchiveWriter.create(sources, out, format)
            assertEquals(format.name, 4, r.files)
            assertTrue(format.name, r.folders >= 6) // photos, sub, empty, deep, x, y, z
            assertEquals(format.name, expectedFormat[format], Archives.format(out))
            val names = Archives.list(out).filter { !it.isDirectory }.map { it.name.removePrefix("./") }.toSet()
            assertTrue(format.name, "photos/sub/b.bin" in names && "readme.md" in names)
            val dest = tmp.newFolder("x-${format.name}")
            Archives.extract(out, dest)
            assertSameTree(sources[0], dest)
            assertEquals("hi", File(dest, "readme.md").readText())
            assertTrue("empty folder kept in ${format.name}", File(dest, "photos/empty").isDirectory)
            assertFalse("no temp file left", File(tmp.root, ".${out.name}.part").exists())
        }
    }

    @Test fun levelsChangeSizeAndStoreIsUncompressed() {
        val src = tmp.newFile("big.txt").apply { writeText("repeat me ".repeat(20_000)) }
        val stored = ArchiveWriter.create(listOf(src), File(tmp.root, "s.zip"), CreateFormat.ZIP, CompressionLevel.STORE).output
        val best = ArchiveWriter.create(listOf(src), File(tmp.root, "b.zip"), CreateFormat.ZIP, CompressionLevel.BEST).output
        assertTrue(stored.length() > src.length())
        assertTrue(best.length() < src.length() / 20)
        val xz = ArchiveWriter.create(listOf(src), File(tmp.root, "b.tar.xz"), CreateFormat.TAR_XZ, CompressionLevel.FAST).output
        assertTrue(xz.length() < src.length() / 20)
        val seven = ArchiveWriter.create(listOf(src), File(tmp.root, "s.7z"), CreateFormat.SEVEN_Z, CompressionLevel.STORE).output
        assertTrue(seven.length() >= src.length())
        // Encoder memory stays phone-sized: never above preset 6 (~94 MB).
        assertTrue(CompressionLevel.entries.all { ArchiveWriter.xzPreset(it) in 0..6 })
    }

    @Test fun outputInsideSourceIsSkipped() {
        val dir = tmp.newFolder("work")
        File(dir, "a.txt").writeText("a")
        val out = File(dir, "work.tar.gz")
        val r = ArchiveWriter.create(listOf(dir), out, CreateFormat.TAR_GZ)
        assertEquals(1, r.files)
        assertFalse(Archives.list(out).any { it.name.endsWith("work.tar.gz") })
    }

    @Test fun duplicateNamesKeepFirstAndNamesAreUnique() {
        val a = File(tmp.newFolder("one"), "same.txt").apply { writeText("first") }
        val b = File(tmp.newFolder("two"), "same.txt").apply { writeText("second") }
        val out = File(tmp.root, "d.7z")
        assertEquals(1, ArchiveWriter.create(listOf(a, b), out, CreateFormat.SEVEN_Z).files)
        val dest = tmp.newFolder("d")
        Archives.extract(out, dest)
        assertEquals("first", File(dest, "same.txt").readText())
        File(tmp.root, "n.tar.bz2").writeText("taken")
        assertEquals("n (2).tar.bz2", ArchiveWriter.uniqueName(tmp.root, "n", CreateFormat.TAR_BZ2).name)
        assertEquals("archive.zip", ArchiveWriter.uniqueName(tmp.root, "  ", CreateFormat.ZIP).name)
        assertEquals("photos", ArchiveWriter.suggestedBase(listOf(tmp.newFolder("photos"))))
        assertEquals("archive", ArchiveWriter.suggestedBase(listOf(a, b)))
    }

    @Test fun replacesExistingOutputAndRejectsMissingSources() {
        val src = tmp.newFile("f.txt").apply { writeText("new") }
        val out = File(tmp.root, "f.tar").apply { writeText("old junk") }
        ArchiveWriter.create(listOf(src), out, CreateFormat.TAR)
        assertEquals(ArchiveFormat.TAR, Archives.format(out))
        assertEquals(listOf("f.txt"), Archives.list(out).map { it.name })
        val ex = runCatching { ArchiveWriter.create(listOf(File(tmp.root, "nope")), File(tmp.root, "x.zip"), CreateFormat.ZIP) }.exceptionOrNull()
        assertTrue(ex is IllegalArgumentException)
        assertTrue(runCatching { ArchiveWriter.create(emptyList(), File(tmp.root, "y.zip"), CreateFormat.ZIP) }.isFailure)
    }
}
