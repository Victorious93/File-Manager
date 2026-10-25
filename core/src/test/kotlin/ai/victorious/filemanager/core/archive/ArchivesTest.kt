package ai.victorious.filemanager.core.archive

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
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
