package ai.victorious.filemanager.core.net

import ai.victorious.filemanager.core.fs.FileIo
import ai.victorious.filemanager.core.path.PathHelpers
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

data class WifiTransferConfig(
    val rootPath: String,
    val port: Int = 8765,
    val pin: String? = null,
    /** Bind address; null = first non-loopback IPv4 (LAN). Never 0.0.0.0 in production UI — we bind LAN IP. */
    val bindHost: String? = null,
)

data class WifiHttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val pinHeader: String?,
    val bodyBytes: ByteArray? = null,
    val bodyFileName: String? = null,
)

data class WifiHttpResponse(
    val status: Int,
    val mime: String,
    val body: ByteArray,
    val headers: Map<String, String> = emptyMap(),
)

/**
 * Pure routing + PIN auth for the Wi-Fi transfer server (unit-testable).
 */
class WifiTransferRouter(
    private val io: FileIo,
    private val rootPath: String,
    private val pin: String?,
) {
    fun handle(req: WifiHttpRequest): WifiHttpResponse {
        if (!pin.isNullOrEmpty()) {
            val provided = req.pinHeader ?: req.query["pin"]
            if (provided != pin) {
                return text(401, "PIN required or invalid")
            }
        }
        val rel = req.path.trimStart('/').substringBefore('?')
        return when (req.method.uppercase()) {
            "GET" -> if (rel.isEmpty() || rel == "index.html") indexHtml() else downloadOrList(rel)
            "POST" -> upload(rel, req)
            else -> text(405, "Method not allowed")
        }
    }

    private fun downloadOrList(rel: String): WifiHttpResponse {
        val path = try {
            var cur = rootPath
            for (part in rel.split('/').filter { it.isNotEmpty() }) {
                if (!PathHelpers.isValidFileName(part)) return text(400, "Bad path")
                cur = PathHelpers.join(cur, part)
            }
            cur
        } catch (t: Throwable) {
            return text(400, CredentialRedactor.safeMessage(t))
        }
        if (!io.exists(path)) return text(404, "Not found")
        if (io.isDirectory(path)) {
            val links = io.list(path).joinToString("\n") { e ->
                val href = (if (rel.isEmpty()) e.name else "$rel/${e.name}").replace(" ", "%20")
                val mark = if (e.isDirectory) "/" else ""
                """<li><a href="/$href">${escape(e.name)}$mark</a></li>"""
            }
            val html = """<!DOCTYPE html><html><body><h1>Index of /$rel</h1><ul>$links</ul>
<form method="POST" enctype="multipart/form-data" action="/$rel">
<input type="file" name="file"/><button type="submit">Upload</button></form></body></html>"""
            return WifiHttpResponse(200, "text/html; charset=utf-8", html.toByteArray())
        }
        val bytes = java.io.File(path).readBytes()
        return WifiHttpResponse(
            200,
            "application/octet-stream",
            bytes,
            mapOf("Content-Disposition" to "attachment; filename=\"${PathHelpers.fileName(path)}\""),
        )
    }

    private fun upload(rel: String, req: WifiHttpRequest): WifiHttpResponse {
        val dir = try {
            var cur = rootPath
            for (part in rel.split('/').filter { it.isNotEmpty() }) {
                cur = PathHelpers.join(cur, part)
            }
            cur
        } catch (t: Throwable) {
            return text(400, CredentialRedactor.safeMessage(t))
        }
        if (!io.isDirectory(dir)) return text(400, "Not a directory")
        val name = req.bodyFileName?.takeIf { PathHelpers.isValidFileName(it) }
            ?: return text(400, "Missing file name")
        val data = req.bodyBytes ?: return text(400, "Empty body")
        val dest = PathHelpers.join(dir, PathHelpers.uniqueChildName(io.childNames(dir), name))
        java.io.File(dest).outputStream().use { it.write(data) }
        return text(201, "Uploaded ${PathHelpers.fileName(dest)}")
    }

    private fun indexHtml(): WifiHttpResponse = downloadOrList("")

    private fun text(code: Int, msg: String) =
        WifiHttpResponse(code, "text/plain; charset=utf-8", msg.toByteArray())

    private fun escape(s: String) = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}

class WifiTransferServer(
    private val io: FileIo,
    private val config: WifiTransferConfig,
) : NanoHTTPD(config.bindHost ?: lanIpv4() ?: "127.0.0.1", config.port) {
    private val router = WifiTransferRouter(io, config.rootPath, config.pin)
    private val running = AtomicBoolean(false)

    val url: String get() = "http://${hostname}:$listeningPort/"
    val bindAddress: String get() = hostname ?: "127.0.0.1"

    fun startServer() {
        start(SOCKET_READ_TIMEOUT, false)
        running.set(true)
    }

    fun stopServer() {
        stop()
        running.set(false)
    }

    fun isRunning(): Boolean = running.get()

    override fun serve(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        var body: ByteArray? = null
        var fileName: String? = null
        if (session.method == Method.POST) {
            session.parseBody(files)
            val tmp = files["file"] ?: files.values.firstOrNull()
            if (tmp != null) {
                body = java.io.File(tmp).readBytes()
                fileName = session.parameters["file"]?.firstOrNull()
                    ?: session.headers["content-disposition"]?.let { cd ->
                        Regex("""filename="([^"]+)"""").find(cd)?.groupValues?.get(1)
                    }
            }
        }
        val req = WifiHttpRequest(
            method = session.method.name,
            path = session.uri,
            query = session.parms.mapValues { it.value ?: "" },
            pinHeader = session.headers["x-wifi-pin"] ?: session.headers["x-pin"],
            bodyBytes = body,
            bodyFileName = fileName,
        )
        val res = try {
            router.handle(req)
        } catch (t: Throwable) {
            WifiHttpResponse(500, "text/plain", CredentialRedactor.safeMessage(t).toByteArray())
        }
        val r = newFixedLengthResponse(
            Response.Status.lookup(res.status) ?: Response.Status.INTERNAL_ERROR,
            res.mime,
            ByteArrayInputStream(res.body),
            res.body.size.toLong(),
        )
        res.headers.forEach { (k, v) -> r.addHeader(k, v) }
        return r
    }

    companion object {
        fun lanIpv4(): String? {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            for (nif in interfaces) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
            return null
        }

        fun randomPin(digits: Int = 4): String {
            val rnd = SecureRandom()
            val bound = Math.pow(10.0, digits.toDouble()).toInt()
            return rnd.nextInt(bound).toString().padStart(digits, '0')
        }
    }
}
