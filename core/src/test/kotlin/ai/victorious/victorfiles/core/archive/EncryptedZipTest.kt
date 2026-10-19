package ai.victorious.victorfiles.core.archive

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EncryptedZipTest {
    @Test fun roundTripAesAndWrongPassword() {
        val dir = Files.createTempDirectory("ez").toFile()
        val src = File(dir, "src").apply { mkdirs() }
        File(src, "a.txt").writeText("secret")
        File(src, "n/b.txt").apply { parentFile.mkdirs(); writeText("nested") }
        val zip = File(dir, "out.zip")
        assertEquals(2, EncryptedZip.create(listOf(src), zip, "pw".toCharArray()))
        assertTrue(EncryptedZip.isEncrypted(zip))
        try { EncryptedZip.extract(zip, File(dir, "bad"), "nope".toCharArray()); throw AssertionError("expected failure") }
        catch (e: WrongPasswordException) { }
        val out = File(dir, "x")
        assertEquals(2, EncryptedZip.extract(zip, out, "pw".toCharArray()))
        assertEquals("nested", File(out, "src/n/b.txt").readText())
        dir.deleteRecursively()
    }

    @Test fun plainZipNotEncrypted() {
        val dir = Files.createTempDirectory("ez2").toFile()
        val f = File(dir, "a.txt").apply { writeText("x") }
        val zip = File(dir, "p.zip")
        EncryptedZip.create(listOf(f), zip, null)
        assertFalse(EncryptedZip.isEncrypted(zip))
        assertEquals(1, EncryptedZip.extract(zip, File(dir, "o"), null))
        dir.deleteRecursively()
    }
}
