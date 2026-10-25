package ai.victorious.filemanager.core.archive

import java.io.File
import java.io.OutputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArchivesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun tree(): List<File> {
        val dir = tmp.newFolder("photos")
        File(dir, "a.txt").writeText("alpha")
        File(dir, "sub").mkdirs()
        File(dir, "sub/b.txt").writeText("beta")
        val single = tmp.newFile("readme.md").apply { writeText("hi") }
        return listOf(dir, single)
    }

    @Test fun createListExtractZip() {
        val out = File(tmp.root, "out.zip")
        assertEquals(3, Archives.createZip(tree(), out))
        assertEquals(ArchiveFormat.ZIP, Archives.format(out))
        val names = Archives.list(out).filter { !it.isDirectory }.map { it.name }.toSet()
        assertEquals(setOf("photos/a.txt", "photos/sub/b.txt", "readme.md"), names)
        val dest = File(tmp.root, "x")
        assertEquals(3, Archives.extract(out, dest))
        assertEquals("beta", File(dest, "photos/sub/b.txt").readText())
    }

    @Test fun zipSlipIsBlocked() {
        val evil = File(tmp.root, "evil.zip")
        ZipOutputStream(evil.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("../escape.txt")); z.write("x".toByteArray()); z.closeEntry()
        }
        val dest = File(tmp.root, "dest")
        assertTrue(runCatching { Archives.extract(evil, dest) }.isFailure)
        assertFalse(File(tmp.root, "escape.txt").exists())
    }

    @Test fun sevenZipListAndExtract() {
        val src = tmp.newFile("s.txt").apply { writeText("seven") }
        val archive = File(tmp.root, "a.7z")
        SevenZOutputFile(archive).use { out ->
            out.putArchiveEntry(out.createArchiveEntry(src, "dir/s.txt"))
            out.write(src.readBytes())
            out.closeArchiveEntry()
        }
        assertEquals(ArchiveFormat.SEVEN_Z, Archives.format(archive))
        assertEquals(listOf("dir/s.txt"), Archives.list(archive).map { it.name })
        val dest = File(tmp.root, "7")
        assertEquals(1, Archives.extract(archive, dest))
        assertEquals("seven", File(dest, "dir/s.txt").readText())
    }

    @Test fun tarGzExtract() {
        val archive = File(tmp.root, "a.tar.gz")
        TarArchiveOutputStream(GzipCompressorOutputStream(archive.outputStream())).use { tar ->
            val data = "tarred".toByteArray()
            tar.putArchiveEntry(TarArchiveEntry("t.txt").apply { size = data.size.toLong() })
            tar.write(data); tar.closeArchiveEntry()
        }
        assertEquals(ArchiveFormat.TAR_GZ, Archives.format(archive))
        val dest = File(tmp.root, "t")
        assertEquals(1, Archives.extract(archive, dest))
        assertEquals("tarred", File(dest, "t.txt").readText())
    }

    private fun tarball(name: String, wrap: (OutputStream) -> OutputStream): File {
        val archive = File(tmp.root, name)
        TarArchiveOutputStream(wrap(archive.outputStream())).use { tar ->
            for ((entry, text) in listOf("dir/one.txt" to "first", "two.txt" to "second")) {
                val data = text.toByteArray()
                tar.putArchiveEntry(TarArchiveEntry(entry).apply { size = data.size.toLong() })
                tar.write(data); tar.closeArchiveEntry()
            }
        }
        return archive
    }

    @Test fun tarBz2ListAndExtract() {
        val archive = tarball("a.tar.bz2", ::BZip2CompressorOutputStream)
        assertEquals(ArchiveFormat.TAR_BZ2, Archives.format(archive))
        assertEquals(setOf("dir/one.txt", "two.txt"), Archives.list(archive).map { it.name }.toSet())
        val dest = File(tmp.root, "tb")
        assertEquals(2, Archives.extract(archive, dest))
        assertEquals("first", File(dest, "dir/one.txt").readText())
        assertEquals("second", File(dest, "two.txt").readText())
    }

    @Test fun tarXzListAndExtract() {
        val archive = tarball("a.tar.xz", ::XZCompressorOutputStream)
        assertEquals(ArchiveFormat.TAR_XZ, Archives.format(archive))
        assertEquals(setOf("dir/one.txt", "two.txt"), Archives.list(archive).map { it.name }.toSet())
        val dest = File(tmp.root, "tx")
        assertEquals(2, Archives.extract(archive, dest))
        assertEquals("first", File(dest, "dir/one.txt").readText())
    }

    @Test fun compressedTarballIsRecognisedWhateverItIsCalled() {
        // Detection looks inside the stream, not at the extension.
        assertEquals(ArchiveFormat.TAR_GZ, Archives.format(tarball("backup.bin", ::GzipCompressorOutputStream)))
        assertEquals(ArchiveFormat.TAR_XZ, Archives.format(tarball("backup2.bin", ::XZCompressorOutputStream)))
    }

    @Test fun plainGzipIsASingleFileNotACorruptTarball() {
        // Used to be treated as TAR_GZ and fail with "Corrupted TAR archive".
        val archive = File(tmp.root, "notes.txt.gz")
        GZIPOutputStream(archive.outputStream()).use { it.write("hello gzip\n".repeat(100).toByteArray()) }
        assertEquals(ArchiveFormat.GZ, Archives.format(archive))
        val items = Archives.list(archive)
        assertEquals(listOf("notes.txt"), items.map { it.name })
        assertEquals(-1L, items.single().size) // unknown without decompressing
        assertEquals(archive.length(), items.single().compressedSize)

        val dest = File(tmp.root, "g")
        assertEquals(1, Archives.extract(archive, dest))
        assertEquals("hello gzip\n".repeat(100), File(dest, "notes.txt").readText())
    }

    @Test fun singleFileBz2AndXz() {
        val text = "some log line\n".repeat(200)
        val bz = File(tmp.root, "app.log.bz2").also { f -> BZip2CompressorOutputStream(f.outputStream()).use { it.write(text.toByteArray()) } }
        val xz = File(tmp.root, "dump.sql.xz").also { f -> XZCompressorOutputStream(f.outputStream()).use { it.write(text.toByteArray()) } }
        assertEquals(ArchiveFormat.BZ2, Archives.format(bz))
        assertEquals(ArchiveFormat.XZ, Archives.format(xz))
        assertEquals(1, Archives.extract(bz, File(tmp.root, "b")))
        assertEquals(1, Archives.extract(xz, File(tmp.root, "x")))
        assertEquals(text, File(tmp.root, "b/app.log").readText())
        assertEquals(text, File(tmp.root, "x/dump.sql").readText())
    }

    @Test fun multiMemberGzipIsReadToTheEnd() {
        // pigz / bgzip / `cat a.gz b.gz` produce several members; `gzip -d` reads them all.
        val archive = File(tmp.root, "both.txt.gz")
        archive.outputStream().use { out ->
            for (part in listOf("AAA-", "BBB")) {
                val m = java.io.ByteArrayOutputStream()
                GZIPOutputStream(m).use { it.write(part.toByteArray()) }
                out.write(m.toByteArray())
            }
        }
        Archives.extract(archive, File(tmp.root, "m"))
        assertEquals("AAA-BBB", File(tmp.root, "m/both.txt").readText())
    }

    @Test fun truncatedStreamFailsAndLeavesNoPartialFile() {
        val rnd = java.util.Random(7)
        val payload = ByteArray(300_000).also { rnd.nextBytes(it) } // incompressible, so cutting it really truncates
        val full = File(tmp.root, "full.bin.gz").also { f -> GZIPOutputStream(f.outputStream()).use { it.write(payload) } }
        val cut = File(tmp.root, "cut.bin.gz").also { it.writeBytes(full.readBytes().copyOf((full.length() / 2).toInt())) }
        val dest = File(tmp.root, "t")
        assertTrue(runCatching { Archives.extract(cut, dest) }.isFailure)
        assertFalse("a half-decompressed file must not be left behind", File(dest, "cut.bin").exists())
    }

    @Test fun decompressedNameStripsOnlyTheCompressionSuffix() {
        assertEquals("a.txt", Archives.decompressedName("a.txt.gz"))
        assertEquals("A.TXT", Archives.decompressedName("A.TXT.GZ"))
        assertEquals("dump.sql", Archives.decompressedName("dump.sql.bz2"))
        assertEquals("disk.img", Archives.decompressedName("disk.img.xz"))
        assertEquals("noext.out", Archives.decompressedName("noext"))
        assertEquals(".gz.out", Archives.decompressedName(".gz")) // never an empty / dot-only name
    }

    private fun rarFixture(name: String): File {
        val f = File(tmp.root, name)
        javaClass.getResourceAsStream("/rar/$name")!!.use { input -> f.outputStream().use { input.copyTo(it) } }
        return f
    }

    @Test fun rar4AndRar5ListAndExtract() {
        for (name in listOf("rar4-solid.rar", "rar5-solid.rar")) {
            val rar = rarFixture(name)
            assertEquals(ArchiveFormat.RAR, Archives.format(rar))
            val items = Archives.list(rar)
            assertTrue("$name lists entries", items.any { !it.isDirectory })
            val out = File(tmp.root, "out-$name")
            val n = Archives.extract(rar, out)
            assertEquals(items.count { !it.isDirectory }, n)
            val written = out.walkTopDown().filter { it.isFile }.toList()
            assertEquals(n, written.size)
            for (item in items.filter { !it.isDirectory }) {
                assertEquals("$name/${item.name} size", item.size, File(out, item.name).length())
            }
        }
    }

    @Test fun rarParentDirTraversalIsBlocked() {
        val rar = rarFixture("parent-dir.rar")
        val out = File(tmp.root, "trav/out").apply { mkdirs() }
        val r = runCatching { Archives.extract(rar, out) }
        // Either the entry is rejected or it lands inside out/ — never outside it.
        val outside = File(tmp.root, "trav").walkTopDown().filter { it.isFile && !it.canonicalPath.startsWith(out.canonicalPath) }.toList()
        assertTrue("files escaped: $outside (${r.exceptionOrNull()?.message})", outside.isEmpty())
    }

    @Test fun rar5PasswordRequiredWrongAndRight() {
        val rar = rarFixture("rar5-password-junrar.rar")
        assertTrue(Archives.isRarPasswordProtected(rar))
        val noPw = runCatching { Archives.extractRar(rar, File(tmp.root, "pw0")) }
        assertTrue("no password must fail: ${noPw.exceptionOrNull()}", noPw.isFailure)
        val wrong = runCatching { Archives.extractRar(rar, File(tmp.root, "pw1"), "nope") }
        assertTrue("wrong password must fail", wrong.isFailure)
        val ok = Archives.extractRar(rar, File(tmp.root, "pw2"), "junrar")
        assertTrue(ok >= 1)
    }

    @Test fun truncatedRarFailsCleanly() {
        val rar = File(tmp.root, "a.rar").apply { writeBytes("Rar!\u001A\u0007\u0000".toByteArray()) }
        assertEquals(ArchiveFormat.RAR, Archives.format(rar))
        assertTrue(runCatching { Archives.list(rar) }.exceptionOrNull() is java.io.IOException)
    }

    @Test fun uniqueZipName() {
        val dir = tmp.newFolder("d")
        File(dir, "p.zip").writeText("")
        assertEquals("p (2).zip", Archives.uniqueZipName(dir, "p").name)
    }
}
