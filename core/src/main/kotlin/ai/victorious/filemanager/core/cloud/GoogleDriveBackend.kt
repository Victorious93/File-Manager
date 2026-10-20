package ai.victorious.filemanager.core.cloud

import ai.victorious.filemanager.core.fs.FileEntry
import ai.victorious.filemanager.core.net.RemoteBackend
import ai.victorious.filemanager.core.net.RemotePaths
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

const val GDRIVE_FOLDER_MIME = "application/vnd.google-apps.folder"

@Serializable
data class GDriveFile(
    val id: String = "",
    val name: String = "",
    val mimeType: String = "",
    val size: String? = null,
    val modifiedTime: String = "",
)
@Serializable
data class GDriveList(
    val files: List<GDriveFile> = emptyList(),
    val nextPageToken: String? = null,
)

object GDriveParser {
    fun parseList(json: String): GDriveList = cloudJson.decodeFromString(GDriveList.serializer(), json)
    fun isFolder(f: GDriveFile) = f.mimeType == GDRIVE_FOLDER_MIME
    fun isoToMs(s: String): Long = runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s.substringBefore('.').removeSuffix("Z"))?.time ?: 0L
    }.getOrDefault(0L)
}

/**
 * Google Drive v3 backend. Drive is id-addressed, so paths are resolved to file ids by walking
 * folders from `root` (ids cached per session). Access token via [token]; never logged.
 */
class GoogleDriveBackend(
    override val connectionId: String,
    private val token: () -> String,
    private val http: OkHttpClient = OkHttpClient(),
    private val base: String = "https://www.googleapis.com/drive/v3",
    private val uploadBase: String = "https://www.googleapis.com/upload/drive/v3",
) : RemoteBackend {
    private val idCache = HashMap<String, String?>().apply { put("/", "root") }

    private fun getJson(url: String): String {
        val req = Request.Builder().url(url).bearer(token()).get().build()
        http.execOrThrow(req, "Drive GET").use { return it.body?.string().orEmpty() }
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun childId(parentId: String, name: String): String? {
        val q = "'$parentId' in parents and name='${name.replace("\\", "\\\\").replace("'", "\\'")}' and trashed=false"
        val url = "$base/files?q=${enc(q)}&fields=${enc("files(id,mimeType)")}&pageSize=1"
        val list = GDriveParser.parseList(getJson(url))
        return list.files.firstOrNull()?.id
    }

    /** Resolves a `/`-rooted path to a Drive file id, or null if missing. */
    private fun resolveId(path: String): String? {
        val norm = "/" + CloudPaths.segments(path).joinToString("/")
        idCache[norm]?.let { return it }
        var parentId = "root"
        var acc = ""
        for (seg in CloudPaths.segments(path)) {
            acc = "$acc/$seg"
            val cached = idCache[acc]
            val id = if (idCache.containsKey(acc)) cached else childId(parentId, seg)?.also { idCache[acc] = it }
            if (id == null) { idCache[acc] = null; return null }
            parentId = id
        }
        return parentId
    }

    private fun requireId(path: String): String =
        resolveId(path) ?: throw CloudException("Not found on Drive: $path")

    override fun list(remotePath: String): List<FileEntry> {
        val id = requireId(remotePath)
        val out = ArrayList<FileEntry>()
        var pageToken: String? = null
        do {
            val q = "'$id' in parents and trashed=false"
            var url = "$base/files?q=${enc(q)}&fields=${enc("nextPageToken,files(id,name,mimeType,size,modifiedTime)")}&pageSize=1000"
            if (pageToken != null) url += "&pageToken=${enc(pageToken)}"
            val list = GDriveParser.parseList(getJson(url))
            for (f in list.files) {
                val childPath = CloudPaths.join(remotePath, f.name)
                idCache["/" + CloudPaths.segments(childPath).joinToString("/")] = f.id
                out.add(
                    FileEntry(
                        path = RemotePaths.toVirtual(connectionId, childPath),
                        name = f.name,
                        isDirectory = GDriveParser.isFolder(f),
                        sizeBytes = f.size?.toLongOrNull() ?: 0L,
                        modifiedEpochMs = GDriveParser.isoToMs(f.modifiedTime),
                        isHidden = f.name.startsWith('.'),
                    ),
                )
            }
            pageToken = list.nextPageToken
        } while (pageToken != null)
        return out.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    override fun exists(remotePath: String): Boolean =
        CloudPaths.segments(remotePath).isEmpty() || resolveId(remotePath) != null

    override fun isDirectory(remotePath: String): Boolean {
        if (CloudPaths.segments(remotePath).isEmpty()) return true
        val id = resolveId(remotePath) ?: return false
        val json = getJson("$base/files/$id?fields=mimeType")
        return json.contains(GDRIVE_FOLDER_MIME)
    }

    override fun mkdir(remotePath: String) {
        val parentId = requireId(CloudPaths.parent(remotePath))
        val name = CloudPaths.name(remotePath)
        val body = """{"name":${q(name)},"mimeType":"$GDRIVE_FOLDER_MIME","parents":["$parentId"]}"""
        val req = Request.Builder().url("$base/files?fields=id").bearer(token())
            .post(body.toRequestBody("application/json".toMediaTypeOrNull())).build()
        http.execOrThrow(req, "Drive mkdir").use {
            val id = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(it.body?.string().orEmpty())?.groupValues?.get(1)
            if (id != null) idCache["/" + CloudPaths.segments(remotePath).joinToString("/")] = id
        }
    }

    override fun delete(remotePath: String) {
        val id = requireId(remotePath)
        val req = Request.Builder().url("$base/files/$id").bearer(token()).delete().build()
        http.execOrThrow(req, "Drive delete").close()
        idCache.remove("/" + CloudPaths.segments(remotePath).joinToString("/"))
    }

    override fun rename(from: String, to: String) {
        val id = requireId(from)
        val newName = CloudPaths.name(to)
        val body = """{"name":${q(newName)}}"""
        val req = Request.Builder().url("$base/files/$id").bearer(token())
            .patch(body.toRequestBody("application/json".toMediaTypeOrNull())).build()
        http.execOrThrow(req, "Drive rename").close()
        idCache.clear(); idCache["/"] = "root"
    }

    override fun openInput(remotePath: String): InputStream {
        val id = requireId(remotePath)
        val req = Request.Builder().url("$base/files/$id?alt=media").bearer(token()).get().build()
        return http.execOrThrow(req, "Drive download").body!!.byteStream()
    }

    override fun openOutput(remotePath: String): OutputStream {
        val parentId = requireId(CloudPaths.parent(remotePath))
        val name = CloudPaths.name(remotePath)
        val buf = java.io.ByteArrayOutputStream()
        return object : OutputStream() {
            override fun write(b: Int) = buf.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = buf.write(b, off, len)
            override fun close() {
                val meta = """{"name":${q(name)},"parents":["$parentId"]}"""
                val body = MultipartBody.Builder().setType("multipart/related".toMediaTypeOrNull()!!)
                    .addPart(meta.toRequestBody("application/json; charset=UTF-8".toMediaTypeOrNull()))
                    .addPart(buf.toByteArray().toRequestBody("application/octet-stream".toMediaTypeOrNull()))
                    .build()
                val req = Request.Builder().url("$uploadBase/files?uploadType=multipart&fields=id").bearer(token())
                    .post(body).build()
                http.execOrThrow(req, "Drive upload").close()
                idCache.clear(); idCache["/"] = "root"
            }
        }
    }

    override fun sizeOf(remotePath: String): Long {
        val id = resolveId(remotePath) ?: return 0
        val json = getJson("$base/files/$id?fields=size")
        return Regex("\"size\"\\s*:\\s*\"(\\d+)\"").find(json)?.groupValues?.get(1)?.toLongOrNull() ?: 0
    }

    override fun close() = Unit

    private fun q(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
