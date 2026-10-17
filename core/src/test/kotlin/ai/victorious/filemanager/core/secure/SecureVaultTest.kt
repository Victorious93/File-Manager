package ai.victorious.filemanager.core.secure

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import javax.crypto.KeyGenerator

class SecureVaultTest {
    @get:Rule val tmp = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun roundTripsEmptySmallAndMultiChunk() {
        for (size in listOf(0, 1, 1024, VaultCrypto.DEFAULT_CHUNK, VaultCrypto.DEFAULT_CHUNK * 3 + 17)) {
            val data = ByteArray(size) { (it * 31).toByte() }
            val enc = VaultCrypto.encryptBytes(key, data)
            assertArrayEquals("size $size", data, VaultCrypto.decryptBytes(key, enc))
        }
    }

    @Test
    fun detectsWrongKeyTamperAndTruncation() {
        val enc = VaultCrypto.encryptBytes(key, ByteArray(200_000) { it.toByte() })
        expectIo { VaultCrypto.decryptBytes(otherKey, enc) }
        val tampered = enc.copyOf().also { it[100] = (it[100].toInt() xor 1).toByte() }
        expectIo { VaultCrypto.decryptBytes(key, tampered) }
        expectIo { VaultCrypto.decryptBytes(key, enc.copyOf(enc.size - 40)) }
        expectIo { VaultCrypto.decryptBytes(key, "plain".toByteArray()) }
    }

    private fun expectIo(block: () -> Unit) {
        try { block(); fail("expected IOException") } catch (_: IOException) {}
    }

    @Test
    fun vaultMoveInMoveOutHidesNames() {
        val root = tmp.newFolder("vault")
        val vault = SecureVault(root, { key })
        val src = File(tmp.newFolder("src"), "secret-notes.txt").apply { writeText("top secret") }
        val e = vault.add(src)
        assertEquals("secret-notes.txt", vault.list().single().originalName)
        assertTrue(File(root, ".nomedia").exists())
        // Nothing on disk reveals the name or content.
        root.walkTopDown().filter { it.isFile }.forEach {
            assertFalse(it.name.contains("secret"))
            assertFalse(String(it.readBytes(), Charsets.ISO_8859_1).contains("top secret"))
            assertFalse(String(it.readBytes(), Charsets.ISO_8859_1).contains("secret-notes"))
        }
        val outDir = tmp.newFolder("out")
        val out = vault.export(e.id, outDir, remove = true)
        assertEquals("top secret", out.readText())
        assertTrue(vault.list().isEmpty())
    }

    @Test
    fun lockTimeoutLogic() {
        var now = 0L
        var timeout = 60_000L
        val lock = VaultLock({ timeout }, { now })
        assertFalse(lock.isUnlocked)
        lock.unlock()
        now = 30_000; assertTrue(lock.isUnlocked)
        lock.touch()
        now = 80_000; assertTrue(lock.isUnlocked)   // 50s since last activity
        now = 91_000; assertFalse(lock.isUnlocked)  // idle > 60s

        lock.unlock()
        lock.onBackground()
        now += 10_000; lock.onForeground(); assertTrue(lock.isUnlocked)
        lock.onBackground()
        now += 61_000; lock.onForeground(); assertFalse(lock.isUnlocked)

        timeout = 0
        lock.unlock(); assertTrue(lock.isUnlocked)
        lock.onBackground(); assertFalse(lock.isUnlocked)
    }

    @Test
    fun lockHardCapsSessionEvenWhileActive() {
        // Regression: continuous use kept the idle timer fresh past the Keystore auth window,
        // so the UI said "unlocked" while every operation failed with UserNotAuthenticated.
        var now = 0L
        val lock = VaultLock(timeoutMs = { 60_000L }, now = { now }, maxSessionMs = { 290_000L })
        lock.unlock()
        while (now < 280_000) { now += 20_000; lock.touch(); assertTrue(lock.isUnlocked) }
        now = 290_000
        assertFalse(lock.isUnlocked)
        lock.unlock(); assertTrue(lock.isUnlocked)  // re-auth starts a fresh window
    }
}
