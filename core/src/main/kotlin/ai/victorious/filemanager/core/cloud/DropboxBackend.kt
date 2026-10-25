package ai.victorious.filemanager.core.cloud

import ai.victorious.filemanager.core.fs.FileEntry
import ai.victorious.filemanager.core.net.RemoteBackend
import ai.victorious.filemanager.core.net.RemotePaths
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
data class DbxEntry(
    @SerialName(".tag") val tag: String = "",
    val name: String = "",
    @SerialName("path_display") val pathDisplay: String = "",
    val size: Long = 0,
    @SerialName("server_modified") val serverModified: String = "",
)

@Serializable
data class DbxList(
    val entries: List<DbxEntry> = emptyList(),
    val cursor: String = "",
    @SerialName("has_more") val hasMore: Boolean = false,
)

object DropboxParser {
    fun parseList(json: String): DbxList = cloudJson.decodeFromString(DbxList.serializer(), json)
    fun isoToMs(s: String): Long = runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s)?.time ?: 0L
    }.getOrDefault(0L)
}

/** Dropbox API v2 backend (path-addressed). Access token via [token]; never logged. */
class DropboxBackend(
    override val connectionId: String,
    private val token: () -> String,
    private val http: OkHttpClient = OkHttpClient(),
    private val apiBase: String = "https://api.dropboxapi.com",
    private val contentBase: String = "https://content.dropboxapi.com",
) : RemoteBackend {
    private val jsonMedia = "application/json".toMediaTypeOrNull()

    private fun rpc(path: String, body: String): String {
        val req = Request.Builder().url("$apiBase$path").bearer(token())
            .post(body.toRequestBody(jsonMedia)).build()
        http.execOrThrow(req, "Dropbox $path").use { return it.body?.string().orEmpty() }
    }

    override fun list(remotePath: String): List<FileEntry> {
        val p = CloudPaths.dropboxPath(remotePath)
        var json = rpc("/2/files/list_folder", """{"path":${q(p)},"recursive":false}""")
        var list = DropboxParser.parseList(json)
        val all = ArrayList(list.entries)
        while (list.hasMore) {
            json = rpc("/2/files/list_folder/continue", """{"cursor":${q(list.cursor)}}""")
            list = DropboxParser.parseList(json)
            all.addAll(list.entries)
        }
        return all.map { e ->
            FileEntry(
                path = RemotePaths.toVirtual(connectionId, CloudPaths.join(remotePath, e.name)),
                name = e.name,
                isDirectory = e.tag == "folder",
                sizeBytes = e.size,
                modifiedEpochMs = DropboxParser.isoToMs(e.serverModified),
                isHidden = e.name.startsWith('.'),
            )
        }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    override fun exists(remotePath: String): Boolean {
        if (CloudPaths.segments(remotePath).isEmpty()) return true
        return runCatching {
            rpc("/2/files/get_metadata", """{"path":${q(CloudPaths.dropboxPath(remotePath))}}"""); true
        }.getOrDefault(false)
    }

    override fun isDirectory(remotePath: String): Boolean {
        if (CloudPaths.segments(remotePath).isEmpty()) return true
        val json = runCatching { rpc("/2/files/get_metadata", """{"path":${q(CloudPaths.dropboxPath(remotePath))}}""") }
            .getOrNull() ?: return false
        return json.contains("\"folder\"")
    }

    override fun mkdir(remotePath: String) {
        rpc("/2/files/create_folder_v2", """{"path":${q(CloudPaths.dropboxPath(remotePath))}}""")
    }

    override fun delete(remotePath: String) {
        rpc("/2/files/delete_v2", """{"path":${q(CloudPaths.dropboxPath(remotePath))}}""")
    }

    override fun rename(from: String, to: String) {
        rpc("/2/files/move_v2", """{"from_path":${q(CloudPaths.dropboxPath(from))},"to_path":${q(CloudPaths.dropboxPath(to))}}""")
    }

    override fun openInput(remotePath: String): InputStream {
        val arg = """{"path":${q(CloudPaths.dropboxPath(remotePath))}}"""
        val req = Request.Builder().url("$contentBase/2/files/download").bearer(token())
            .header("Dropbox-API-Arg", arg)
            .post(ByteArray(0).toRequestBody(null)).build()
        return http.execOrThrow(req, "Dropbox download").body!!.byteStream()
    }

    override fun openOutput(remotePath: String): OutputStream {
        val buf = java.io.ByteArrayOutputStream()
        return object : OutputStream() {
            override fun write(b: Int) = buf.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = buf.write(b, off, len)
            override fun close() {
                val arg = """{"path":${q(CloudPaths.dropboxPath(remotePath))},"mode":"overwrite","mute":true}"""
                val req = Request.Builder().url("$contentBase/2/files/upload").bearer(token())
                    .header("Dropbox-API-Arg", arg)
                    .post(buf.toByteArray().toRequestBody("application/octet-stream".toMediaTypeOrNull())).build()
                http.execOrThrow(req, "Dropbox upload").close()
            }
        }
    }

    override fun sizeOf(remotePath: String): Long {
        val json = runCatching { rpc("/2/files/get_metadata", """{"path":${q(CloudPaths.dropboxPath(remotePath))}}""") }
            .getOrNull() ?: return 0
        return Regex("\"size\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toLongOrNull() ?: 0
    }

    override fun close() = Unit

    private fun q(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
