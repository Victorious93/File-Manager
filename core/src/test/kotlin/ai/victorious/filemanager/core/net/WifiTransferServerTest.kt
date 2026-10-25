package ai.victorious.filemanager.core.net

import ai.victorious.filemanager.core.fs.NioFileIo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL

/**
 * Drives a real [WifiTransferServer] over loopback. The router tests call [WifiTransferRouter]
 * directly with hand-written paths; these go through NanoHTTPD, which percent-decodes the URI
 * before the router sees it, so they cover what a browser actually hits.
 */
class WifiTransferServerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun withServer(pin: String? = null, block: (base: String, port: Int, root: File) -> Unit) {
        val root = tmp.newFolder("share")
        val server = WifiTransferServer(NioFileIo(), WifiTransferConfig(root.absolutePath, port = 0, pin = pin, bindHost = "127.0.0.1"))
        server.startServer()
        try {
            block("http://127.0.0.1:${server.listeningPort}", server.listeningPort, root)
        } finally {
            server.stopServer()
        }
    }

    private fun get(url: String, headers: Map<String, String> = emptyMap()): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 5_000
        c.readTimeout = 5_000
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        return try {
            val code = c.responseCode
            val body = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes().decodeToString() } ?: ""
            code to body
        } finally {
            c.disconnect()
        }
    }

    private fun upload(url: String, fileName: String, content: String): Int {
        val boundary = "----fmtest"
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 5_000
        c.readTimeout = 5_000
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        val body = "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n" +
            "Content-Type: text/plain\r\n\r\n$content\r\n--$boundary--\r\n"
        c.outputStream.use { it.write(body.toByteArray()) }
        return try {
            c.responseCode
        } finally {
            c.disconnect()
        }
    }

    /** Sends [target] verbatim (no client-side URL normalisation) and returns the whole response. */
    private fun rawGet(port: Int, target: String): String =
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 5_000
            s.getOutputStream().apply { write("GET $target HTTP/1.0\r\n\r\n".toByteArray()); flush() }
            s.getInputStream().readBytes().decodeToString()
        }

    @Test
    fun everyLinkOnTheListingPageCanBeFollowed() = withServer { base, _, root ->
        // '%' used to be percent-decoded twice (NanoHTTPD, then the router), so these 400/404'd.
        val names = listOf("plain.txt", "100%.txt", "a%41.txt", "sp ace.txt", "plus+sign.txt", "hash#tag.txt", "q?mark.txt")
        names.forEach { File(root, it).writeText("body of $it") }

        val (code, html) = get("$base/")
        assertEquals(200, code)
        val hrefs = Regex("href=\"([^\"]+)\"").findAll(html).map { it.groupValues[1] }.toList()
        assertEquals(html, names.size, hrefs.size)

        val bodies = hrefs.map { href ->
            val (c, b) = get(base + href)
            assertEquals("GET $href -> $c $b", 200, c)
            b
        }
        assertEquals(names.map { "body of $it" }.toSet(), bodies.toSet())
    }

    @Test
    fun subfolderLinksAndTheUploadFormWorkForAwkwardFolderNames() = withServer(pin = "4321") { base, _, root ->
        listOf("C#", "50% off", "who?").forEach { File(root, it).mkdir() }

        val (_, top) = get("$base/?pin=4321")
        val folderHrefs = Regex("href=\"([^\"]+)\"").findAll(top).map { it.groupValues[1] }.toList()
        assertEquals(top, 3, folderHrefs.size)

        for (href in folderHrefs) {
            val (code, page) = get(base + href)
            assertEquals("GET $href", 200, code)
            // The form must post back to the very folder it is shown for.
            val action = Regex("action=\"([^\"]+)\"").find(page)!!.groupValues[1]
            assertEquals("form action for $href", href, action)
            assertEquals("POST $action", 201, upload(base + action, "up.txt", "hello"))
        }
        listOf("C#", "50% off", "who?").forEach { assertEquals("hello", File(File(root, it), "up.txt").readText()) }
    }

    @Test
    fun traversalAttemptsNeverEscapeTheSharedRoot() = withServer { _, port, root ->
        File(root.parentFile, "secret.txt").writeText("TOPSECRET")
        File(root, "sub").mkdir()
        val attempts = listOf(
            "/../secret.txt",
            "/%2E%2E/secret.txt",
            "/%2e%2e%2fsecret.txt",
            "/sub/..%2f..%2fsecret.txt",
            "/..%5csecret.txt",
            "/sub/%2e%2e/%2e%2e/secret.txt",
        )
        for (path in attempts) {
            val raw = rawGet(port, path)
            assertFalse("$path leaked the file: $raw", raw.contains("TOPSECRET"))
        }
        val upload = upload("http://127.0.0.1:$port/%2e%2e", "evil.txt", "x")
        assertEquals(400, upload)
        assertFalse(File(root.parentFile, "evil.txt").exists())
    }

    @Test
    fun pinIsRequiredOnEveryRequestAndAcceptedAsHeaderOrQuery() = withServer(pin = "4321") { base, _, root ->
        File(root, "a.txt").writeText("data")
        assertEquals(401, get("$base/a.txt").first)
        assertEquals(401, get("$base/a.txt?pin=0000").first)
        assertEquals(401, upload("$base/", "x.txt", "x"))
        assertEquals("data", get("$base/a.txt?pin=4321").second)
        assertEquals("data", get("$base/a.txt", mapOf("X-Wifi-Pin" to "4321")).second)
        assertTrue(File(root, "x.txt").exists().not())
    }
}
