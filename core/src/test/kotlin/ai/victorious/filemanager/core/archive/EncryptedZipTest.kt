package ai.victorious.filemanager.core.archive

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

    @Test fun verifyPasswordWritesNothingAndDistinguishesPasswords() {
        for (aes in listOf(true, false)) {
            val dir = Files.createTempDirectory("ez3").toFile()
            val a = File(dir, "big.txt").apply { writeText("x".repeat(50_000)) }
            val b = File(dir, "small.txt").apply { writeText("tiny") }
            val zip = File(dir, "v.zip")
            EncryptedZip.create(listOf(a, b), zip, "pw".toCharArray(), aes = aes)
            val before = dir.list()!!.toSet()
            assertTrue("aes=$aes right", EncryptedZip.verifyPassword(zip, "pw".toCharArray()))
            assertFalse("aes=$aes wrong", EncryptedZip.verifyPassword(zip, "nope".toCharArray()))
            assertEquals(before, dir.list()!!.toSet())
            dir.deleteRecursively()
        }
    }
}

