package ai.victorious.filemanager.core.archive

import ai.victorious.filemanager.core.apk.ApkBuilder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ZipEditorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun makeZip(vararg entries: Pair<String, ByteArray>): File {
        val f = tmp.newFile("t.zip")
        ZipOutputStream(f.outputStream()).use { z ->
            for ((n, b) in entries) {
                z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry()
            }
        }
        return f
    }

    private fun read(f: File, name: String) = ZipFile(f).use { z -> z.getEntry(name)?.let { e -> z.getInputStream(e).readBytes() } }
    private fun names(f: File) = ZipFile(f).use { z -> z.entries().asSequence().map { it.name }.toList() }

    @Test
    fun replaceDeleteRenameAddKeepOthersIntact() {
        val z = makeZip("a.txt" to "A".toByteArray(), "b.txt" to "B".toByteArray(), "c.txt" to "C".toByteArray())
        ZipEditor.apply(z, listOf(
            ZipEdit.Put("a.txt", "A2".toByteArray()),
            ZipEdit.Delete("b.txt"),
            ZipEdit.Rename("c.txt", "dir/c2.txt"),
            ZipEdit.Put("new.txt", "N".toByteArray()),
        ))
        assertEquals(listOf("a.txt", "dir/c2.txt", "new.txt"), names(z))
        assertEquals("A2", String(read(z, "a.txt")!!))
        assertEquals("C", String(read(z, "dir/c2.txt")!!))
        assertEquals("N", String(read(z, "new.txt")!!))
        assertNull(read(z, "b.txt"))
        assertFalse(File(z.parentFile, ".t.zip.edit.tmp").exists())
    }

    @Test
    fun v1SignatureFilesDroppedByDefaultKeptWhenAsked() {
        val z = makeZip("META-INF/CERT.RSA" to byteArrayOf(1), "META-INF/MANIFEST.MF" to byteArrayOf(2), "x" to byteArrayOf(3))
        val copy = tmp.newFile("c.zip").also { z.copyTo(it, overwrite = true) }
        ZipEditor.apply(z, listOf(ZipEdit.Put("x", byteArrayOf(9))))
        assertEquals(listOf("x"), names(z))
        ZipEditor.apply(copy, listOf(ZipEdit.Put("x", byteArrayOf(9))), dropSignatures = false)
        assertEquals(3, names(copy).size)
    }

    @Test
    fun resourcesArscStaysStoredAndAligned() {
        val arsc = ByteArray(1000) { it.toByte() }
        val z = makeZip("AndroidManifest.xml" to byteArrayOf(1, 2, 3), "resources.arsc" to arsc, "classes.dex" to byteArrayOf(5))
        ZipEditor.apply(z, listOf(ZipEdit.Put("AndroidManifest.xml", ByteArray(37))))
        assertArrayEquals(arsc, read(z, "resources.arsc"))
        val off = ApkBuilder.dataOffsets(z).first { it.name == "resources.arsc" }
        assertEquals(ZipEntry.STORED, off.method)
        assertEquals(0L, off.dataOffset % 4)
        assertTrue(ApkBuilder.misaligned(z).isEmpty())
    }

    @Test
    fun failureLeavesOriginalUntouched() {
        val z = makeZip("a" to byteArrayOf(1))
        val before = z.readBytes()
        for (bad in listOf(
            listOf(ZipEdit.Delete("missing")),
            listOf(ZipEdit.Rename("a", "../evil")),
            listOf(ZipEdit.Put("/abs", byteArrayOf(1))),
            listOf(ZipEdit.Rename("a", "a2"), ZipEdit.Put("a2", byteArrayOf(2))),
        )) {
            assertTrue(runCatching { ZipEditor.apply(z, bad) }.isFailure)
            assertArrayEquals(before, z.readBytes())
        }
        assertEquals(listOf(".t.zip.edit.tmp").filter { File(z.parentFile, it).exists() }, emptyList<String>())
    }

    @Test
    fun renameOntoExistingIsRejectedUnlessThatEntryIsDeleted() {
        val z = makeZip("a" to byteArrayOf(1), "b" to byteArrayOf(2))
        assertTrue(runCatching { ZipEditor.apply(z, listOf(ZipEdit.Rename("a", "b"))) }.isFailure)
        ZipEditor.apply(z, listOf(ZipEdit.Delete("b"), ZipEdit.Rename("a", "b")))
        assertEquals(listOf("b"), names(z))
        assertEquals(1, read(z, "b")!![0].toInt())
    }
}
