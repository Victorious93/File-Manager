package ai.victorious.victorfiles.core.cloud

import ai.victorious.victorfiles.core.fs.FileEntry
import ai.victorious.victorfiles.core.net.RemoteBackend
import ai.victorious.victorfiles.core.net.RemotePaths
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@Serializable
data class GraphFolder(val childCount: Int = 0)
@Serializable
data class GraphItem(
    val name: String = "",
    val size: Long = 0,
    @SerialName("lastModifiedDateTime") val modified: String = "",
    val folder: GraphFolder? = null,
)
@Serializable
data class GraphChildren(
    val value: List<GraphItem> = emptyList(),
    @SerialName("@odata.nextLink") val nextLink: String? = null,
)

object GraphParser {
    fun parseChildren(json: String): GraphChildren = cloudJson.decodeFromString(GraphChildren.serializer(), json)
    fun isoToMs(s: String): Long = runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s.substringBefore('.').removeSuffix("Z"))?.time ?: 0L
    }.getOrDefault(0L)
}

/** Microsoft Graph (OneDrive) backend, path-addressed via `/me/drive/root:/{path}:`. */
class OneDriveBackend(
    override val connectionId: String,
    private val token: () -> String,
    private val http: OkHttpClient = OkHttpClient(),
    private val base: String = "https://graph.microsoft.com/v1.0",
) : RemoteBackend {

    private fun encodePath(path: String): String =
        CloudPaths.segments(path).joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    private fun itemUrl(path: String, suffix: String): String {
        val segs = CloudPaths.segments(path)
        return if (segs.isEmpty()) "$base/me/drive/root$suffix"
        else "$base/me/drive/root:/${encodePath(path)}:$suffix"
    }

    private fun getJson(url: String): String {
        val req = Request.Builder().url(url).bearer(token()).get().build()
        http.execOrThrow(req, "Graph GET").use { return it.body?.string().orEmpty() }
    }

    override fun list(remotePath: String): List<FileEntry> {
        var url: String? = itemUrl(remotePath, "/children")
        val out = ArrayList<FileEntry>()
        while (url != null) {
            val page = GraphParser.parseChildren(getJson(url))
            for (i in page.value) {
                out.add(
                    FileEntry(
                        path = RemotePaths.toVirtual(connectionId, CloudPaths.join(remotePath, i.name)),
                        name = i.name,
                        isDirectory = i.folder != null,
                        sizeBytes = i.size,
                        modifiedEpochMs = GraphParser.isoToMs(i.modified),
                        isHidden = i.name.startsWith('.'),
                    ),
                )
            }
            url = page.nextLink
        }
        return out.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    override fun exists(remotePath: String): Boolean {
        if (CloudPaths.segments(remotePath).isEmpty()) return true
        return runCatching { getJson(itemUrl(remotePath, "")); true }.getOrDefault(false)
    }

    override fun isDirectory(remotePath: String): Boolean {
        if (CloudPaths.segments(remotePath).isEmpty()) return true
        val json = runCatching { getJson(itemUrl(remotePath, "")) }.getOrNull() ?: return false
        return json.contains("\"folder\"")
    }

    override fun mkdir(remotePath: String) {
        val parent = CloudPaths.parent(remotePath)
        val name = CloudPaths.name(remotePath)
        val url = itemUrl(parent, "/children")
        val body = """{"name":${q(name)},"folder":{},"@microsoft.graph.conflictBehavior":"replace"}"""
        val req = Request.Builder().url(url).bearer(token())
            .post(body.toRequestBody("application/json".toMediaTypeOrNull())).build()
        http.execOrThrow(req, "Graph mkdir").close()
    }

    override fun delete(remotePath: String) {
        val req = Request.Builder().url(itemUrl(remotePath, "")).bearer(token()).delete().build()
        http.execOrThrow(req, "Graph delete").close()
    }

    override fun rename(from: String, to: String) {
        val newParent = CloudPaths.parent(to)
        val newName = CloudPaths.name(to)
        val parentRef = if (CloudPaths.segments(newParent).isEmpty()) "/drive/root:" else "/drive/root:/${encodePath(newParent)}"
        val body = """{"name":${q(newName)},"parentReference":{"path":${q(parentRef)}}}"""
        val req = Request.Builder().url(itemUrl(from, "")).bearer(token())
            .patch(body.toRequestBody("application/json".toMediaTypeOrNull())).build()
        http.execOrThrow(req, "Graph move").close()
    }

    override fun openInput(remotePath: String): InputStream {
        val req = Request.Builder().url(itemUrl(remotePath, "/content")).bearer(token()).get().build()
        return http.execOrThrow(req, "Graph download").body!!.byteStream()
    }

    override fun openOutput(remotePath: String): OutputStream {
        // Simple upload (<= 4 MB). Larger files need an upload session (see docs/CLOUD.md).
        val buf = java.io.ByteArrayOutputStream()
        return object : OutputStream() {
            override fun write(b: Int) = buf.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = buf.write(b, off, len)
            override fun close() {
                val req = Request.Builder().url(itemUrl(remotePath, "/content")).bearer(token())
                    .put(buf.toByteArray().toRequestBody("application/octet-stream".toMediaTypeOrNull())).build()
                http.execOrThrow(req, "Graph upload").close()
            }
        }
    }

    override fun sizeOf(remotePath: String): Long {
        val json = runCatching { getJson(itemUrl(remotePath, "")) }.getOrNull() ?: return 0
        return Regex("\"size\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toLongOrNull() ?: 0
    }

    override fun close() = Unit

    private fun q(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
