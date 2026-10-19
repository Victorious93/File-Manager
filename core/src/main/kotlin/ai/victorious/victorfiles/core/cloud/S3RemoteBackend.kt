package ai.victorious.victorfiles.core.cloud

import ai.victorious.victorfiles.core.fs.FileEntry
import ai.victorious.victorfiles.core.net.RemoteBackend
import ai.victorious.victorfiles.core.net.RemotePaths
import ai.victorious.victorfiles.core.net.SavedConnection
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Parsed S3 ListObjectsV2 result. */
data class S3Listing(
    val prefixes: List<String>,
    val objects: List<S3Object>,
    val truncated: Boolean,
    val continuationToken: String?,
)

data class S3Object(val key: String, val size: Long, val lastModifiedMs: Long)

/** Minimal, dependency-free parser for S3 ListObjectsV2 XML (delimiter=/). */
object S3Xml {
    fun parseList(xml: String): S3Listing {
        val prefixes = Regex("<CommonPrefixes>\\s*<Prefix>(.*?)</Prefix>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).map { decode(it.groupValues[1]) }.toList()
        val objects = Regex("<Contents>(.*?)</Contents>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).map { m ->
                val body = m.groupValues[1]
                S3Object(
                    key = decode(tag(body, "Key")),
                    size = tag(body, "Size").toLongOrNull() ?: 0L,
                    lastModifiedMs = parseIso(tag(body, "LastModified")),
                )
            }.toList()
        val truncated = tag(xml, "IsTruncated").equals("true", ignoreCase = true)
        val token = Regex("<NextContinuationToken>(.*?)</NextContinuationToken>").find(xml)?.groupValues?.get(1)?.let { decode(it) }
        return S3Listing(prefixes, objects, truncated, token)
    }

    private fun tag(xml: String, name: String): String =
        Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.trim().orEmpty()

    private fun decode(s: String) = s
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'")

    private fun parseIso(s: String): Long = runCatching {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        fmt.parse(s.substringBefore('.').removeSuffix("Z"))?.time ?: 0L
    }.getOrDefault(0L)
}

/**
 * S3-compatible object-store backend (AWS S3, Cloudflare R2, Backblaze B2 via S3 API, MinIO).
 * Signs every request with [SigV4]. Credentials come from the encrypted secret; never logged.
 */
class S3RemoteBackend(
    private val connection: SavedConnection,
    private val credentials: SigV4.Credentials,
    private val http: OkHttpClient = OkHttpClient(),
) : RemoteBackend {
    override val connectionId: String = connection.id

    private val region = connection.region.ifBlank { "us-east-1" }
    private val bucket = connection.bucket
    private val host: String = connection.endpoint.ifBlank { "s3.$region.amazonaws.com" }
        .removePrefix("https://").removePrefix("http://").trimEnd('/')
    private val pathStyle = connection.s3PathStyle || connection.endpoint.isNotBlank()
    private val scheme = if (connection.endpoint.startsWith("http://")) "http" else "https"

    private fun amzDate(): String = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

    private fun urlFor(key: String, query: Map<String, String>): String {
        val encodedKey = key.split('/').joinToString("/") { SigV4.uriEncode(it, true) }
        val base = if (pathStyle) "$scheme://$host/$bucket" else "$scheme://$bucket.$host"
        val q = if (query.isEmpty()) "" else "?" + query.entries
            .sortedBy { it.key }.joinToString("&") { "${SigV4.uriEncode(it.key, true)}=${SigV4.uriEncode(it.value, true)}" }
        return "$base/$encodedKey$q"
    }

    private fun canonicalUri(key: String): String {
        val encodedKey = key.split('/').joinToString("/") { SigV4.uriEncode(it, true) }
        return if (pathStyle) "/$bucket/$encodedKey" else "/$encodedKey"
    }

    private fun signed(method: String, key: String, query: Map<String, String>, payloadHash: String, extra: Map<String, String> = emptyMap()): Request.Builder {
        val date = amzDate()
        val headers = linkedMapOf("host" to hostHeader(), "x-amz-content-sha256" to payloadHash, "x-amz-date" to date)
        credentials.sessionToken?.let { headers["x-amz-security-token"] = it }
        headers.putAll(extra)
        val s = SigV4.sign(method, canonicalUri(key), query, headers, payloadHash, credentials, region, "s3", date)
        val b = Request.Builder().url(urlFor(key, query))
        headers.forEach { (k, v) -> b.header(k, v) }
        b.header("Authorization", s.authorization)
        return b
    }

    private fun hostHeader(): String = if (pathStyle) host else "$bucket.$host"

    override fun list(remotePath: String): List<FileEntry> {
        val prefix = CloudPaths.s3Key(remotePath, directory = true)
        val entries = ArrayList<FileEntry>()
        var token: String? = null
        do {
            val q = linkedMapOf("list-type" to "2", "delimiter" to "/")
            if (prefix.isNotEmpty()) q["prefix"] = prefix
            if (token != null) q["continuation-token"] = token
            val req = signed("GET", "", q, SigV4.sha256Hex("")).get().build()
            val xml = execString(req)
            val listing = S3Xml.parseList(xml)
            for (p in listing.prefixes) {
                val name = p.removeSuffix("/").substringAfterLast('/')
                entries.add(entry(name, CloudPaths.join(remotePath, name), true, 0, 0))
            }
            for (o in listing.objects) {
                if (o.key == prefix) continue // the folder marker itself
                val name = o.key.substringAfterLast('/')
                if (name.isEmpty()) continue
                entries.add(entry(name, CloudPaths.join(remotePath, name), false, o.size, o.lastModifiedMs))
            }
            token = if (listing.truncated) listing.continuationToken else null
        } while (token != null)
        return entries.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    private fun entry(name: String, path: String, dir: Boolean, size: Long, mod: Long) = FileEntry(
        path = RemotePaths.toVirtual(connectionId, path),
        name = name, isDirectory = dir, sizeBytes = size, modifiedEpochMs = mod, isHidden = name.startsWith('.'),
    )

    override fun exists(remotePath: String): Boolean {
        if (CloudPaths.segments(remotePath).isEmpty()) return true
        return runCatching { headSize(CloudPaths.s3Key(remotePath, false)) >= 0 }.getOrDefault(false) ||
            isDirectory(remotePath)
    }

    override fun isDirectory(remotePath: String): Boolean {
        if (CloudPaths.segments(remotePath).isEmpty()) return true
        val prefix = CloudPaths.s3Key(remotePath, true)
        val q = linkedMapOf("list-type" to "2", "delimiter" to "/", "prefix" to prefix, "max-keys" to "1")
        val req = signed("GET", "", q, SigV4.sha256Hex("")).get().build()
        val xml = execString(req)
        val l = S3Xml.parseList(xml)
        return l.prefixes.isNotEmpty() || l.objects.isNotEmpty()
    }

    override fun mkdir(remotePath: String) {
        val key = CloudPaths.s3Key(remotePath, true)
        val body = RequestBody.create(null, ByteArray(0))
        val req = signed("PUT", key, emptyMap(), SigV4.sha256Hex("")).put(body).build()
        execOk(req)
    }

    override fun delete(remotePath: String) {
        val key = CloudPaths.s3Key(remotePath, false)
        val req = signed("DELETE", key, emptyMap(), SigV4.sha256Hex("")).delete().build()
        execOk(req)
    }

    override fun rename(from: String, to: String) {
        val srcKey = CloudPaths.s3Key(from, false)
        val dstKey = CloudPaths.s3Key(to, false)
        val copySource = "/$bucket/" + srcKey.split('/').joinToString("/") { SigV4.uriEncode(it, true) }
        val req = signed("PUT", dstKey, emptyMap(), SigV4.sha256Hex(""), mapOf("x-amz-copy-source" to copySource))
            .put(RequestBody.create(null, ByteArray(0))).build()
        execOk(req)
        delete(from)
    }

    override fun openInput(remotePath: String): InputStream {
        val key = CloudPaths.s3Key(remotePath, false)
        val req = signed("GET", key, emptyMap(), SigV4.sha256Hex("")).get().build()
        val resp = http.newCall(req).execute()
        if (!resp.isSuccessful) { resp.close(); throw CloudException("S3 GET failed: HTTP ${resp.code}") }
        return resp.body!!.byteStream()
    }

    override fun openOutput(remotePath: String): OutputStream {
        val key = CloudPaths.s3Key(remotePath, false)
        val tmp = File.createTempFile("s3up", ".tmp")
        return object : OutputStream() {
            private val out = tmp.outputStream()
            override fun write(b: Int) = out.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            override fun close() {
                out.close()
                try {
                    val hash = SigV4.hex(java.security.MessageDigest.getInstance("SHA-256").digest(tmp.readBytes()))
                    val body = tmp.asRequestBody("application/octet-stream".toMediaTypeOrNull())
                    val req = signed("PUT", key, emptyMap(), hash).put(body).build()
                    execOk(req)
                } finally { tmp.delete() }
            }
        }
    }

    override fun sizeOf(remotePath: String): Long =
        runCatching { headSize(CloudPaths.s3Key(remotePath, false)).coerceAtLeast(0) }.getOrDefault(0)

    private fun headSize(key: String): Long {
        val req = signed("HEAD", key, emptyMap(), SigV4.sha256Hex("")).head().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return -1
            return resp.header("Content-Length")?.toLongOrNull() ?: 0
        }
    }

    private fun execString(req: Request): String {
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw CloudException("S3 request failed: HTTP ${resp.code}")
            return text
        }
    }

    private fun execOk(req: Request) {
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw CloudException("S3 ${req.method} failed: HTTP ${resp.code}")
        }
    }

    override fun close() = Unit
}
