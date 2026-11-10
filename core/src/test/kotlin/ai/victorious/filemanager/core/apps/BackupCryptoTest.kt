package ai.victorious.filemanager.core.apps

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlin.random.Random

class BackupCryptoTest {
    private val pass = "correct horse battery".toCharArray()
    private val iters = 1_000 // keeps the test fast; the app uses DEFAULT_ITERATIONS

    private fun enc(data: ByteArray, chunk: Int = 64): ByteArray =
        ByteArrayOutputStream().also { BackupCrypto.encrypt(ByteArrayInputStream(data), it, pass, iters, chunk) }.toByteArray()

    private fun dec(data: ByteArray, p: CharArray = pass): ByteArray =
        ByteArrayOutputStream().also { BackupCrypto.decrypt(ByteArrayInputStream(data), it, p) }.toByteArray()

    @Test fun roundTripsEmptySmallExactAndMultiChunkData() {
        for (n in listOf(0, 1, 63, 64, 128, 1000)) {
            val plain = Random(n).nextBytes(n)
            val c = enc(plain)
            assertTrue(BackupCrypto.isEncrypted(c))
            assertArrayEquals("size $n", plain, dec(c))
        }
    }

    @Test fun sameInputEncryptsDifferentlyEachTime() {
        val p = ByteArray(100) { 7 }
        assertFalse(enc(p).contentEquals(enc(p)))
    }

    @Test fun wrongPassphraseIsRejected() {
        val c = enc(ByteArray(300) { it.toByte() })
        assertTrue(runCatching { dec(c, "wrong passphrase".toCharArray()) }.exceptionOrNull() is BackupCrypto.WrongPassphraseOrDamaged)
    }

    @Test fun tamperingTruncationAndAppendingAreAllDetected() {
        val c = enc(ByteArray(300) { it.toByte() })
        fun bad(b: ByteArray) = assertTrue(runCatching { dec(b) }.isFailure)
        bad(c.copyOf().also { it[it.size - 5] = (it[it.size - 5].toInt() xor 1).toByte() })
        bad(c.copyOf().also { it[8] = (it[8].toInt() xor 1).toByte() })       // header (iterations) changed
        // Drop the whole last frame: the remaining last frame was not sealed as "last".
        val lastFrameLen = 4 + 16 + (300 % 64)
        bad(c.copyOf(c.size - lastFrameLen))
        bad(c.copyOf(c.size - 1))
        bad(c + c.copyOfRange(c.size - lastFrameLen, c.size))
        bad(c + byteArrayOf(0, 0))
        bad(ByteArray(5))
    }

    @Test fun shortPassphrasesAreRefused() {
        assertTrue(runCatching { BackupCrypto.encrypt(ByteArrayInputStream(ByteArray(1)), ByteArrayOutputStream(), "short".toCharArray(), iters) }.isFailure)
    }

    @Test fun fileHelpersReplaceThePlainFileAndCleanUpOnFailure() {
        val dir = Files.createTempDirectory("vfbe").toFile()
        try {
            val src = java.io.File(dir, "base.apk").apply { writeBytes(Random(1).nextBytes(5000)) }
            val original = src.readBytes()
            val e = BackupCrypto.encryptFile(src, pass, iters)
            assertEquals("base.apk.vfbe", e.name)
            assertFalse(src.exists())
            val out = java.io.File(dir, "restored.apk")
            BackupCrypto.decryptFile(e, out, pass)
            assertArrayEquals(original, out.readBytes())
            val out2 = java.io.File(dir, "bad.apk")
            assertTrue(runCatching { BackupCrypto.decryptFile(e, out2, "nope nope nope".toCharArray()) }.isFailure)
            assertFalse("partial output removed", out2.exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun manifestKeepsWorkingForOldPlainBackups() {
        val old = """{"version":1,"packageName":"a.b","label":"A","backedUpAtEpochMs":1,"apks":[{"name":"base.apk","role":"base"}]}"""
        assertEquals(null, AppBackup.readManifest(old).encryption)
        val m = AppBackup.readManifest(old).copy(encryption = BackupCrypto.FORMAT)
        assertEquals("VFBE1", AppBackup.readManifest(AppBackup.writeManifest(m)).encryption)
    }
}
