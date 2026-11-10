package ai.victorious.filemanager.core.mypc

import ai.victorious.filemanager.core.net.RemotePaths
import java.io.File
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import kotlin.random.Random
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ProcessShellFactory
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.AfterClass
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test

/**
 * Real sshlib sessions against an in-process Apache SSHD server: host-key pinning, key login with
 * a P-256 key (as Android Keystore would hold), SFTP browsing ops, and resumable verified transfers.
 * No key material is committed: every key is generated when the test runs.
 */
class MyPcSftpIntegrationTest {
    companion object {
        private lateinit var work: File
        private lateinit var server: SshServer
        private lateinit var hostPin: String
        private val clientKey: KeyPair = ecKey()
        private val strangerKey: KeyPair = ecKey()

        private fun ecKey(): KeyPair =
            KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

        @JvmStatic @BeforeClass fun start() {
            work = Files.createTempDirectory("fm-mypc-it").toFile()
            val sshd = SshServer.setUpDefaultServer()
            sshd.host = "127.0.0.1"
            sshd.port = 0
            sshd.keyPairProvider = SimpleGeneratorHostKeyProvider(File(work, "hostkey").toPath()).apply {
                algorithm = KeyUtils.EC_ALGORITHM
                keySize = 256
            }
            sshd.subsystemFactories = listOf(SftpSubsystemFactory())
            // Lets the backend run `sha256sum` like it would on Ubuntu.
            sshd.commandFactory = CommandFactory { channel, command ->
                ProcessShellFactory(command, "/bin/sh", "-c", command).createShell(channel)
            }
            sshd.setPublickeyAuthenticator { user, key, _ -> user == "don" && KeyUtils.compareKeys(clientKey.public, key) }
            sshd.setPasswordAuthenticator { _, _, _ -> false }
            sshd.start()
            server = sshd
            val hostPub = sshd.keyPairProvider.loadKeys(null).first().public
            hostPin = HostKeys.fingerprintOfLine(PublicKeyEntry.toString(hostPub))
        }

        @JvmStatic @AfterClass fun stop() {
            runCatching { server.stop(true) }
            work.deleteRecursively()
        }
    }

    private fun profile(pins: List<String> = listOf(hostPin), hosts: List<String> = listOf("127.0.0.1")) =
        PcProfile(MyPcIds.newProfileId(), "Test PC", "don", hosts, server.port, pins)

    private fun connector(key: KeyPair = clientKey, seen: MutableList<String>? = null) =
        MyPcConnector({ key }, { _, host -> seen?.add(host) })

    private fun remoteDir(name: String) = File(work, name).apply { mkdirs() }

    @Test fun pinnedKeyConnectsAndBrowses() {
        val root = remoteDir("browse")
        File(root, "hello.txt").writeText("hi")
        File(root, "Docs").mkdirs()
        val seen = mutableListOf<String>()
        connector(seen = seen).open(profile()).use { pc ->
            assertEquals(listOf("127.0.0.1"), seen)
            assertTrue(pc.home().startsWith("/"))
            val listing = pc.list(root.path)
            assertEquals(listOf("Docs", "hello.txt"), listing.map { it.name })
            assertTrue(listing[0].isDirectory)
            assertEquals(2L, listing[1].sizeBytes)
            assertEquals(RemotePaths.toVirtual(pc.connectionId, "${root.path}/hello.txt"), listing[1].path)

            pc.mkdir("${root.path}/New")
            assertTrue(pc.isDirectory("${root.path}/New"))
            pc.rename("${root.path}/hello.txt", "${root.path}/New/renamed.txt")
            assertFalse(pc.exists("${root.path}/hello.txt"))
            assertEquals("hi", File(root, "New/renamed.txt").readText())
            pc.delete("${root.path}/New/renamed.txt")
            pc.delete("${root.path}/New")
            assertFalse(File(root, "New").exists())
            assertTrue(runCatching { pc.rename("${root.path}/Docs", "${root.path}/Docs") }.isFailure)
        }
    }

    @Test fun changedHostKeyIsAHardStop() {
        val other = HostKeys.fingerprint(SshKeyFormat.blob(strangerKey.public as java.security.interfaces.ECPublicKey))
        // Two hosts: the stop must happen on the first one, never falling through to another address.
        val seen = mutableListOf<String>()
        try {
            connector(seen = seen).open(profile(pins = listOf(other), hosts = listOf("127.0.0.1", "localhost")))
            fail("must not connect")
        } catch (e: HostKeyMismatchException) {
            assertEquals("127.0.0.1", e.host)
            assertEquals(hostPin, e.presented)
            assertEquals(listOf(other), e.expected)
        }
        assertTrue(seen.isEmpty())
    }

    @Test fun unpinnedManualProfileAsksBeforeLogin() {
        try {
            connector().open(profile(pins = emptyList()))
            fail("must ask first")
        } catch (e: HostKeyUnpinnedException) {
            assertEquals(hostPin, e.presented)
        }
        // After the user confirms, the pin is saved and the next connect works.
        connector().open(profile(pins = listOf(hostPin))).use { assertTrue(it.isOpen) }
    }

    @Test fun refusedClientKeyIsReported() {
        try {
            connector(key = strangerKey).open(profile())
            fail("must be refused")
        } catch (e: KeyRejectedException) {
            assertEquals("don", e.user)
        }
    }

    @Test fun deadLanAddressFallsThroughToNextHost() {
        // 127.0.0.2 is loopback but nothing listens there, like a LAN address while away.
        val seen = mutableListOf<String>()
        connector(seen = seen).open(profile(hosts = listOf("127.0.0.2", "127.0.0.1"))).use { assertTrue(it.isOpen) }
        assertEquals(listOf("127.0.0.1"), seen)
        assertTrue(runCatching { connector().open(profile(hosts = listOf("127.0.0.2"))) }.exceptionOrNull() is PcUnreachableException)
    }

    @Test fun uploadAndDownloadResumeAndVerifyByHash() {
        val root = remoteDir("xfer")
        val phone = File(work, "phone").apply { mkdirs() }
        val data = Random(3).nextBytes(200_000)
        val local = File(phone, "video.bin").apply { writeBytes(data) }
        connector().open(profile()).use { pc ->
            assertNotNull("sha256sum available on the test PC", pc.sha256(local.path))
            // Upload with a partial already on the PC.
            File(root, "video.bin.partial").writeBytes(data.copyOfRange(0, 70_000))
            val up = ResumableTransfer.copyFile(LocalTransferSide(), local.path, pc, "${root.path}/video.bin")
            assertEquals(70_000L, up.resumedFrom)
            assertTrue(up.hashVerified)
            assertArrayEquals(data, File(root, "video.bin").readBytes())
            assertFalse(File(root, "video.bin.partial").exists())

            // Download with a partial already on the phone.
            val back = File(phone, "back.bin")
            File(phone, "back.bin.partial").writeBytes(data.copyOfRange(0, 150_000))
            val down = ResumableTransfer.copyFile(pc, "${root.path}/video.bin", LocalTransferSide(), back.path)
            assertEquals(150_000L, down.resumedFrom)
            assertTrue(down.hashVerified)
            assertArrayEquals(data, back.readBytes())

            // A stale remote partial with a longer tail is truncated, not kept.
            File(root, "two.bin.partial").writeBytes(data.copyOfRange(0, 1000) + ByteArray(5000) { 7 })
            ResumableTransfer.copyFile(LocalTransferSide(), local.path, pc, "${root.path}/two.bin")
            assertArrayEquals(data, File(root, "two.bin").readBytes())
        }
    }

    @Test fun treeMoveFromPcDeletesSourcesAfterVerify() {
        val root = remoteDir("tree")
        File(root, "album/sub").mkdirs()
        File(root, "album/a.jpg").writeBytes(Random(1).nextBytes(40_000))
        File(root, "album/sub/b.jpg").writeBytes(Random(2).nextBytes(10))
        val dest = File(work, "phone-tree")
        connector().open(profile()).use { pc ->
            val s = TreeTransfer(pc, LocalTransferSide(), move = true).run("${root.path}/album", "${dest.path}/album")
            assertEquals(2, s.files)
            assertEquals(2, s.hashVerifiedFiles)
        }
        assertEquals(40_000L, File(dest, "album/a.jpg").length())
        assertFalse(File(root, "album").exists())
    }

    @Test fun shellMetacharactersInNamesAreHashedSafely() {
        val root = remoteDir("odd")
        val f = File(root, "it's \$(touch pwned) ;x.txt").apply { writeText("odd") }
        connector().open(profile()).use { pc ->
            assertEquals(LocalTransferSide().sha256(f.path), pc.sha256(f.path))
        }
        assertFalse(File(work, "pwned").exists())
        assertFalse(File("pwned").exists())
    }
}
