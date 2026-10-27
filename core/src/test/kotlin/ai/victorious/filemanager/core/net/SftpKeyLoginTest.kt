package ai.victorious.filemanager.core.net

import java.io.File
import java.nio.file.Files
import java.security.PublicKey
import org.apache.sshd.common.config.keys.AuthorizedKeyEntry
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Real SFTP logins with private keys against an in-process Apache SSHD server.
 * Keys are generated at test time with `ssh-keygen` so no key material is committed.
 */
class SftpKeyLoginTest {
    companion object {
        private lateinit var work: File
        private lateinit var root: File
        private var server: SshServer? = null
        private val authorized = mutableListOf<PublicKey>()
        private var keygen: String? = null

        @JvmStatic @BeforeClass fun start() {
            keygen = listOf("/usr/bin/ssh-keygen", "/usr/local/bin/ssh-keygen", "/opt/homebrew/bin/ssh-keygen")
                .firstOrNull { File(it).canExecute() }
            work = Files.createTempDirectory("fm-sftpkey").toFile()
            root = File(work, "root").apply { mkdirs() }
            File(root, "hello.txt").writeText("hi from sftp")
            val sshd = SshServer.setUpDefaultServer()
            sshd.host = "127.0.0.1"
            sshd.port = 0
            sshd.keyPairProvider = SimpleGeneratorHostKeyProvider(File(work, "hostkey").toPath())
            sshd.subsystemFactories = listOf(SftpSubsystemFactory())
            sshd.fileSystemFactory = VirtualFileSystemFactory(root.toPath())
            sshd.setPublickeyAuthenticator { user, key, _ ->
                user == "tester" && synchronized(authorized) { authorized.any { KeyUtils.compareKeys(it, key) } }
            }
            sshd.setPasswordAuthenticator { _, _, _ -> false }
            sshd.start()
            server = sshd
        }

        @JvmStatic @AfterClass fun stop() {
            runCatching { server?.stop(true) }
            work.deleteRecursively()
        }
    }

    private fun requireKeygen(): String {
        val k = keygen
        if (k == null && System.getenv("CI") != null) fail("ssh-keygen missing on CI")
        assumeTrue("ssh-keygen not installed", k != null)
        return k!!
    }

    /** Generates a key, authorises its public half, and returns the private key text. */
    private fun genKey(type: String, bits: Int?, pass: String, pem: Boolean = false, authorize: Boolean = true): String {
        val kg = requireKeygen()
        val f = File(work, "k-${System.nanoTime()}")
        val cmd = mutableListOf(kg, "-q", "-t", type, "-N", pass, "-C", "test", "-f", f.absolutePath)
        if (bits != null) cmd += listOf("-b", bits.toString())
        if (pem) cmd += listOf("-m", "PEM")
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertEquals("ssh-keygen failed: $out", 0, p.waitFor())
        if (authorize) {
            val pub = AuthorizedKeyEntry.parseAuthorizedKeyEntry(File(f.path + ".pub").readText().trim())
                .resolvePublicKey(null, emptyMap(), null)
            synchronized(authorized) { authorized += pub }
        }
        return f.readText()
    }

    private fun login(key: String, pass: String?): List<String> {
        val conn = SavedConnection(
            id = "k", name = "k", protocol = RemoteProtocol.SFTP, host = "127.0.0.1",
            port = server!!.port, username = "tester", authKind = AuthKind.PRIVATE_KEY,
        )
        SftpRemoteBackend(conn, SftpKeys.encodeSecret(key, pass)).use { b ->
            b.openInput("/hello.txt").use { assertEquals("hi from sftp", it.readBytes().decodeToString()) }
            return b.list("/").map { it.name }
        }
    }

    @Test fun ed25519OpenSsh_noPassphrase() {
        val key = genKey("ed25519", null, "")
        assertEquals(SftpKeys.Format.OPENSSH, SftpKeys.detectFormat(key))
        val info = SftpKeys.inspect(key)
        assertEquals("ssh-ed25519", info.algorithm)
        assertFalse(info.encrypted)
        assertTrue(login(key, null).contains("hello.txt"))
    }

    @Test fun ed25519OpenSsh_withPassphrase() {
        val key = genKey("ed25519", null, "correct horse")
        assertTrue(SftpKeys.inspect(key, "correct horse").encrypted)
        assertTrue(login(key, "correct horse").contains("hello.txt"))
    }

    @Test fun rsaOpenSsh_withPassphrase() {
        val key = genKey("rsa", 2048, "s3cret")
        assertEquals(SftpKeys.Format.OPENSSH, SftpKeys.detectFormat(key))
        assertTrue(login(key, "s3cret").contains("hello.txt"))
    }

    @Test fun rsaPem_plainAndEncrypted() {
        val plain = genKey("rsa", 2048, "", pem = true)
        assertEquals(SftpKeys.Format.PEM_RSA, SftpKeys.detectFormat(plain))
        assertTrue(login(plain, null).contains("hello.txt"))
        val enc = genKey("rsa", 2048, "pem-pass", pem = true)
        assertTrue(SftpKeys.inspect(enc, "pem-pass").encrypted)
        assertTrue(login(enc, "pem-pass").contains("hello.txt"))
    }

    @Test fun ecdsaPem() {
        val key = genKey("ecdsa", 256, "", pem = true)
        assertEquals(SftpKeys.Format.PEM_EC, SftpKeys.detectFormat(key))
        assertTrue(login(key, null).contains("hello.txt"))
    }

    @Test fun legacyBarePemSecretStillWorks() {
        val key = genKey("ed25519", null, "")
        val conn = SavedConnection(
            id = "l", name = "l", protocol = RemoteProtocol.SFTP, host = "127.0.0.1",
            port = server!!.port, username = "tester", authKind = AuthKind.PRIVATE_KEY,
        )
        SftpRemoteBackend(conn, key).use { assertTrue(it.exists("/hello.txt")) }
    }

    @Test fun wrongOrMissingPassphraseIsRejected() {
        val key = genKey("ed25519", null, "right", authorize = false)
        val missing = runCatching { SftpKeys.inspect(key, null) }.exceptionOrNull()
        assertTrue(missing is SftpKeys.KeyException && missing.message!!.contains("passphrase"))
        val wrong = runCatching { SftpKeys.inspect(key, "wrong") }.exceptionOrNull()
        assertTrue(wrong is SftpKeys.KeyException && wrong.message!!.contains("Wrong passphrase"))
    }

    @Test fun unauthorisedKeyCannotLogIn() {
        val key = genKey("ed25519", null, "", authorize = false)
        assertTrue(runCatching { login(key, null) }.isFailure)
    }
}

class SftpKeysCodecTest {
    @Test fun secretRoundTrip() {
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\r\nabc\r\n-----END OPENSSH PRIVATE KEY-----\r\n"
        val d = SftpKeys.decodeSecret(SftpKeys.encodeSecret(pem, "pä ss\nword"))
        assertEquals("pä ss\nword", d.passphrase)
        assertEquals("-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----\n", d.keyText)
        assertEquals(null, SftpKeys.decodeSecret(SftpKeys.encodeSecret(pem, "")).passphrase)
        assertEquals(SftpKeys.Decoded("raw", null), SftpKeys.decodeSecret("raw"))
    }

    @Test fun detectsFormats() {
        assertEquals(SftpKeys.Format.PKCS8, SftpKeys.detectFormat("-----BEGIN PRIVATE KEY-----"))
        assertEquals(SftpKeys.Format.PKCS8_ENCRYPTED, SftpKeys.detectFormat("  -----BEGIN ENCRYPTED PRIVATE KEY-----"))
        assertEquals(SftpKeys.Format.PEM_DSA, SftpKeys.detectFormat("-----BEGIN DSA PRIVATE KEY-----"))
        assertEquals(SftpKeys.Format.PUTTY, SftpKeys.detectFormat("PuTTY-User-Key-File-3: ssh-ed25519"))
        assertEquals(SftpKeys.Format.UNKNOWN, SftpKeys.detectFormat("hello"))
    }

    @Test fun publicKeyGivesHelpfulError() {
        val e = runCatching { SftpKeys.inspect("ssh-ed25519 AAAAC3Nz test") }.exceptionOrNull()
        assertTrue(e is SftpKeys.KeyException && e.message!!.contains("public key"))
    }
}
