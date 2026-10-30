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
    /** Upload already spooled to disk by the HTTP server (streamed, not held in memory). */
    val bodyFile: java.io.File? = null,
    /** Who is asking (the socket's remote address); wrong-PIN attempts are limited per client. */
    val clientId: String? = null,
)

data class WifiHttpResponse(
    val status: Int,
    val mime: String,
    val body: ByteArray,
    val headers: Map<String, String> = emptyMap(),
    /** When set, the server streams this file instead of [body] (downloads of any size). */
    val file: java.io.File? = null,
)

/**
 * Pure routing + PIN auth for the Wi-Fi transfer server (unit-testable).
 */
class WifiTransferRouter(
    private val io: FileIo,
    private val rootPath: String,
    private val pin: String?,
    private val limiter: PinAttemptLimiter = PinAttemptLimiter(),
) {
    fun handle(req: WifiHttpRequest): WifiHttpResponse {
        if (!pin.isNullOrEmpty()) {
            val client = req.clientId ?: "unknown"
            // Refuse a locked-out client before looking at its PIN, or the lock would still reveal a right guess.
            val wait = limiter.retryAfterSeconds(client)
            if (wait > 0) {
                return WifiHttpResponse(
                    429, "text/plain; charset=utf-8",
                    "Too many wrong PINs. Try again in $wait s.".toByteArray(),
                    mapOf("Retry-After" to wait.toString()),
                )
            }
            val provided = req.pinHeader ?: req.query["pin"]
            if (provided == null || !java.security.MessageDigest.isEqual(provided.toByteArray(), pin.toByteArray())) {
                // Opening the page without a PIN is not a guess; only a wrong one counts.
                if (provided != null) limiter.recordFailure(client)
                return text(401, "PIN required or invalid")
            }
            limiter.recordSuccess(client)
        }
        // req.path is the already-decoded URI path (NanoHTTPD decodes it, and strips the query), so a
        // '%' or '?' here is part of a file name: decoding or splitting again would corrupt it.
        val rel = req.path.trimStart('/')
        // Browsers cannot send the PIN header, so links/forms carry ?pin= forward; without it
        // every click after the QR-code landing page answered 401.
        val q = if (!pin.isNullOrEmpty()) "?pin=" + java.net.URLEncoder.encode(pin, "UTF-8") else ""
        return when (req.method.uppercase()) {
            "GET" -> if (rel.isEmpty() || rel == "index.html") downloadOrList("", q) else downloadOrList(rel, q)
            "POST" -> upload(rel, req)
            else -> text(405, "Method not allowed")
        }
    }

    /** Maps a decoded URL path under the shared root; null for anything that is not plain child names. */
    private fun resolve(rel: String): String? {
        var cur = rootPath
        for (part in rel.split('/').filter { it.isNotEmpty() }) {
            if (!PathHelpers.isValidFileName(part)) return null
            cur = PathHelpers.join(cur, part)
        }
        return cur
    }

    /** Percent-encodes each segment of a decoded relative path for use in an href / form action. */
    private fun encodePath(rel: String): String = rel.split('/').filter { it.isNotEmpty() }
        .joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    private fun downloadOrList(rel: String, q: String = ""): WifiHttpResponse {
        val path = resolve(rel) ?: return text(400, "Bad path")
        if (!io.exists(path)) return text(404, "Not found")
        if (io.isDirectory(path)) {
            val links = io.list(path).joinToString("\n") { e ->
                val href = encodePath(if (rel.isEmpty()) e.name else "$rel/${e.name}")
                val mark = if (e.isDirectory) "/" else ""
                val cls = if (e.isDirectory) " class=\"dir\"" else ""
                """<li><a$cls href="/$href$q">${escape(e.name)}$mark</a></li>"""
            }
            val html = """<!DOCTYPE html>
<html><head><meta charset="utf-8"/><meta name="viewport" content="width=device-width,initial-scale=1"/>
<title>VictorFiles — ${escape(if (rel.isEmpty()) "/" else "/$rel")}</title>
<meta name="color-scheme" content="dark"/>
<style>
:root{color-scheme:dark}
body{font-family:system-ui,sans-serif;margin:1.5rem;background:#0B0F1A;color:#E6F1FF}
a{color:#00E5FF;text-decoration:none} a:hover{text-decoration:underline}
h1{font-size:1.25rem} ul{list-style:none;padding:0}
li{padding:.4rem .6rem;border-bottom:1px solid #242E45}
.dir{font-weight:600;color:#FF2BD6} .meta{color:#9AA8C2;font-size:.85rem}
form{margin-top:1.5rem;padding:1rem;background:#121829;border:1px solid #242E45;border-radius:8px}
button,input[type=submit]{background:#00E5FF;color:#0B0F1A;border:0;padding:.5rem 1rem;border-radius:6px}
</style></head><body>
<h1>Index of ${escape(if (rel.isEmpty()) "/" else "/$rel")}</h1>
<ul>$links</ul>
<form method="POST" enctype="multipart/form-data" action="/${encodePath(rel)}$q">
<label>Upload into this folder<br/><input type="file" name="file"/></label>
<button type="submit">Upload</button></form>
<p class="meta">LAN only · PIN protected when enabled · VictorFiles</p>
</body></html>"""
            return WifiHttpResponse(200, "text/html; charset=utf-8", html.toByteArray())
        }
        return WifiHttpResponse(
            200,
            "application/octet-stream",
            ByteArray(0),
            mapOf("Content-Disposition" to "attachment; filename=\"${PathHelpers.fileName(path).replace("\"", "")}\""),
            file = java.io.File(path),
        )
    }

    private fun upload(rel: String, req: WifiHttpRequest): WifiHttpResponse {
        val dir = resolve(rel) ?: return text(400, "Bad path")
        if (!io.isDirectory(dir)) return text(400, "Not a directory")
        val name = req.bodyFileName?.takeIf { PathHelpers.isValidFileName(it) }
            ?: return text(400, "Missing file name")
        val dest = PathHelpers.join(dir, PathHelpers.uniqueChildName(io.childNames(dir), name))
        val spooled = req.bodyFile
        when {
            spooled != null -> spooled.inputStream().use { input -> java.io.File(dest).outputStream().use { input.copyTo(it) } }
            req.bodyBytes != null -> java.io.File(dest).outputStream().use { it.write(req.bodyBytes) }
            else -> return text(400, "Empty body")
        }
        return text(201, "Uploaded ${PathHelpers.fileName(dest)}")
    }

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
        var bodyFile: java.io.File? = null
        var fileName: String? = null
        if (session.method == Method.POST) {
            session.parseBody(files)
            val tmp = files["file"] ?: files.values.firstOrNull()
            if (tmp != null) {
                bodyFile = java.io.File(tmp)
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
            bodyFileName = fileName,
            bodyFile = bodyFile,
            clientId = session.headers["remote-addr"],
        )
        val res = try {
            router.handle(req)
        } catch (t: Throwable) {
            WifiHttpResponse(500, "text/plain", CredentialRedactor.safeMessage(t).toByteArray())
        }
        val status = Response.Status.lookup(res.status) ?: Response.Status.INTERNAL_ERROR
        val f = res.file
        val r = if (f != null) {
            newFixedLengthResponse(status, res.mime, java.io.FileInputStream(f), f.length())
        } else {
            newFixedLengthResponse(status, res.mime, ByteArrayInputStream(res.body), res.body.size.toLong())
        }
        res.headers.forEach { (k, v) -> r.addHeader(k, v) }
        return r
    }

    companion object {
        /**
         * LAN-only bind address: a private (site-local) IPv4 on a Wi-Fi / hotspot / ethernet
         * interface. Never a mobile-data (rmnet/ccmni), VPN (tun) or carrier-NAT address, so the
         * server is not exposed beyond the local network. Null when not on a LAN.
         */
        fun lanIpv4(): String? {
            val candidates = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { nif.name to it } }
            return pickLanAddress(candidates)?.hostAddress
        }

        private val LAN_PREFIXES = listOf("wlan", "swlan", "ap", "softap", "wifi", "eth", "en", "rndis", "usb")
        private val NON_LAN_PREFIXES = listOf("rmnet", "ccmni", "pdp", "tun", "ppp", "wwan", "clat", "v4-rmnet", "dummy", "ipsec")

        fun pickLanAddress(candidates: List<Pair<String, Inet4Address>>): Inet4Address? {
            val usable = candidates.filter { (name, addr) ->
                val n = name.lowercase()
                !addr.isLoopbackAddress && addr.isSiteLocalAddress && NON_LAN_PREFIXES.none { n.startsWith(it) }
            }
            return usable.firstOrNull { (name, _) -> LAN_PREFIXES.any { name.lowercase().startsWith(it) } }?.second
                ?: usable.firstOrNull()?.second
        }

        fun randomPin(digits: Int = 4): String {
            val rnd = SecureRandom()
            val bound = Math.pow(10.0, digits.toDouble()).toInt()
            return rnd.nextInt(bound).toString().padStart(digits, '0')
        }
    }
}
