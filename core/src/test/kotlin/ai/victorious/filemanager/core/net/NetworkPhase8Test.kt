package ai.victorious.filemanager.core.net

import ai.victorious.filemanager.core.fs.NioFileIo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NetworkPhase8Test {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun remotePaths_roundTrip() {
        val v = RemotePaths.toVirtual("abc123", "/docs/a.txt")
        assertEquals("/remote/abc123/docs/a.txt", v)
        assertEquals("abc123", RemotePaths.connectionId(v))
        assertEquals("/docs/a.txt", RemotePaths.remoteRelative(v))
        assertTrue(RemotePaths.isRemote(v))
        assertTrue(RemotePaths.isRemote("/remote"))
        assertFalse(RemotePaths.isRemote("/sdcard"))
    }

    @Test
    fun connectionUri_parser() {
        val p = ConnectionUriParser.parse("sftp://alice@example.com:2222/home/alice")
        assertEquals(RemoteProtocol.SFTP, p.protocol)
        assertEquals("example.com", p.host)
        assertEquals(2222, p.port)
        assertEquals("alice", p.username)
        assertEquals("/home/alice", p.path)

        val ftp = ConnectionUriParser.parse("ftp://files.local")
        assertEquals(21, ftp.port)
        assertEquals(RemoteProtocol.FTP, ftp.protocol)

        val dav = ConnectionUriParser.parse("davs://cloud.example/remote.php/dav")
        assertEquals(RemoteProtocol.WEBDAV, dav.protocol)
        assertTrue(dav.webDavHttps)
    }

    @Test
    fun credentialRedactor_stripsSecrets() {
        val raw = "sftp://bob:hunter2@host/path password=hunter2 token:abc"
        val safe = CredentialRedactor.redact(raw)
        assertFalse(safe.contains("hunter2"))
        assertFalse(safe.contains("abc"))
        assertTrue(safe.contains("***"))
    }

    @Test
    fun compositeFileIo_remoteListCopy() {
        val localRoot = tmp.newFolder("local")
        val local = NioFileIo()
        val mem = InMemoryRemoteBackend("c1")
        mem.seedDir("/docs")
        mem.seedFile("/docs/hello.txt", "hi".toByteArray())
        val conn = SavedConnection(
            id = "c1",
            name = "Mem",
            protocol = RemoteProtocol.SFTP,
            host = "localhost",
            port = 22,
        )
        val io = CompositeFileIo(local, { if (it == "c1") mem else null }, { listOf(conn) })
        val roots = io.list(RemotePaths.ROOT)
        assertEquals(1, roots.size)
        assertEquals("Mem", roots[0].name)

        val listing = io.list(RemotePaths.connectionRoot("c1") + "/docs")
        assertEquals(1, listing.size)
        assertEquals("hello.txt", listing[0].name)

        val dest = java.io.File(localRoot, "hello.txt").absolutePath
        io.copy(listing[0].path, dest)
        assertEquals("hi", java.io.File(dest).readText())
    }

    @Test
    fun wifiRouter_pinAndDownload() {
        val dir = tmp.newFolder("wifi")
        java.io.File(dir, "a.txt").writeText("data")
        val io = NioFileIo()
        val router = WifiTransferRouter(io, dir.absolutePath, pin = "1234")
        val denied = router.handle(
            WifiHttpRequest("GET", "/a.txt", emptyMap(), pinHeader = null),
        )
        assertEquals(401, denied.status)

        val ok = router.handle(
            WifiHttpRequest("GET", "/a.txt", emptyMap(), pinHeader = "1234"),
        )
        assertEquals(200, ok.status)
        assertEquals("data", ok.body.toString(Charsets.UTF_8))

        val up = router.handle(
            WifiHttpRequest(
                "POST",
                "/",
                emptyMap(),
                pinHeader = "1234",
                bodyBytes = "new".toByteArray(),
                bodyFileName = "b.txt",
            ),
        )
        assertEquals(201, up.status)
        assertEquals("new", java.io.File(dir, "b.txt").readText())
    }
}
