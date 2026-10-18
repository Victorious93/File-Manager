package ai.victorious.filemanager.core.net

import ai.victorious.filemanager.core.fs.FileEntry
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session as JschSession
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPSClient
import java.io.InputStream
import java.io.OutputStream
import java.util.Properties
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

class DefaultRemoteBackendFactory : RemoteBackendFactory {
    override fun open(connection: SavedConnection, secret: String?): RemoteBackend = when (connection.protocol) {
        RemoteProtocol.FTP -> FtpRemoteBackend(connection, secret, implicitFtps = false)
        RemoteProtocol.FTPS -> FtpRemoteBackend(connection, secret, implicitFtps = true)
        RemoteProtocol.SFTP -> SftpRemoteBackend(connection, secret)
        RemoteProtocol.SMB -> SmbRemoteBackend(connection, secret)
        RemoteProtocol.WEBDAV -> WebDavRemoteBackend(connection, secret)
        RemoteProtocol.GDRIVE, RemoteProtocol.DROPBOX, RemoteProtocol.ONEDRIVE, RemoteProtocol.S3 ->
            throw IllegalArgumentException("Cloud protocol ${connection.protocol} is handled by the cloud backend factory")
    }
}

class FtpRemoteBackend(
    private val connection: SavedConnection,
    password: String?,
    implicitFtps: Boolean,
) : RemoteBackend {
    override val connectionId: String = connection.id
    private val client: FTPClient = if (implicitFtps) FTPSClient(true) else FTPClient()

    init {
        client.connectTimeout = 15_000
        client.defaultTimeout = 30_000
        client.connect(connection.host, connection.port)
        if (!client.login(connection.username, password ?: "")) {
            client.disconnect()
            throw IllegalStateException("FTP login failed")
        }
        client.enterLocalPassiveMode()
        client.setFileType(FTP.BINARY_FILE_TYPE)
        if (client is FTPSClient) {
            client.execPBSZ(0)
            client.execPROT("P")
        }
    }

    override fun list(remotePath: String): List<FileEntry> {
        val files = client.listFiles(remotePath) ?: emptyArray()
        return files.filter { it.name != "." && it.name != ".." }.map { f ->
            val child = if (remotePath.endsWith("/")) "$remotePath${f.name}" else "$remotePath/${f.name}"
            FileEntry(
                path = RemotePaths.toVirtual(connectionId, child),
                name = f.name,
                isDirectory = f.isDirectory,
                sizeBytes = f.size,
                modifiedEpochMs = f.timestamp?.time?.time ?: 0L,
                isHidden = f.name.startsWith('.'),
            )
        }
    }

    override fun exists(remotePath: String): Boolean =
        remotePath == "/" || client.listFiles(remotePath)?.isNotEmpty() == true ||
            runCatching { client.changeWorkingDirectory(remotePath) }.getOrDefault(false).also {
                client.changeWorkingDirectory(connection.initialPath.ifBlank { "/" })
            }

    override fun isDirectory(remotePath: String): Boolean {
        if (remotePath == "/" || remotePath.isEmpty()) return true
        val cwd = client.printWorkingDirectory()
        val ok = client.changeWorkingDirectory(remotePath)
        if (ok) client.changeWorkingDirectory(cwd)
        return ok
    }

    override fun mkdir(remotePath: String) {
        if (!client.makeDirectory(remotePath)) throw IllegalStateException("mkdir failed: $remotePath")
    }

    override fun delete(remotePath: String) {
        val ok = if (isDirectory(remotePath)) client.removeDirectory(remotePath) else client.deleteFile(remotePath)
        if (!ok) throw IllegalStateException("delete failed: $remotePath")
    }

    override fun rename(from: String, to: String) {
        if (!client.rename(from, to)) throw IllegalStateException("rename failed")
    }

    override fun openInput(remotePath: String): InputStream =
        client.retrieveFileStream(remotePath) ?: throw IllegalStateException("open failed: $remotePath")

    override fun openOutput(remotePath: String): OutputStream =
        client.storeFileStream(remotePath) ?: throw IllegalStateException("store failed: $remotePath")

    override fun sizeOf(remotePath: String): Long =
        client.listFiles(remotePath)?.firstOrNull()?.size ?: 0L

    override fun close() {
        runCatching { client.logout() }
        runCatching { client.disconnect() }
    }
}

class SftpRemoteBackend(
    private val connection: SavedConnection,
    secret: String?,
) : RemoteBackend {
    override val connectionId: String = connection.id
    private val session: JschSession
    private val channel: ChannelSftp

    init {
        val jsch = JSch()
        if (connection.authKind == AuthKind.PRIVATE_KEY && !secret.isNullOrBlank()) {
            jsch.addIdentity("fm-${connection.id}", secret.toByteArray(), null, null)
        }
        session = jsch.getSession(connection.username, connection.host, connection.port)
        if (connection.authKind == AuthKind.PASSWORD) {
            session.setPassword(secret ?: "")
        }
        val cfg = Properties()
        cfg["StrictHostKeyChecking"] = "no"
        session.setConfig(cfg)
        session.connect(15_000)
        channel = session.openChannel("sftp") as ChannelSftp
        channel.connect(15_000)
    }

    override fun list(remotePath: String): List<FileEntry> {
        val entries = channel.ls(remotePath)
        val out = ArrayList<FileEntry>()
        for (raw in entries) {
            val e = raw as ChannelSftp.LsEntry
            if (e.filename == "." || e.filename == "..") continue
            val child = if (remotePath.endsWith("/")) "$remotePath${e.filename}" else "$remotePath/${e.filename}"
            out += FileEntry(
                path = RemotePaths.toVirtual(connectionId, child),
                name = e.filename,
                isDirectory = e.attrs.isDir,
                sizeBytes = e.attrs.size,
                modifiedEpochMs = e.attrs.mTime * 1000L,
                isHidden = e.filename.startsWith('.'),
            )
        }
        return out
    }

    override fun exists(remotePath: String): Boolean =
        runCatching { channel.stat(remotePath); true }.getOrDefault(false)

    override fun isDirectory(remotePath: String): Boolean =
        runCatching { channel.stat(remotePath).isDir }.getOrDefault(false)

    override fun mkdir(remotePath: String) = channel.mkdir(remotePath)

    override fun delete(remotePath: String) {
        if (isDirectory(remotePath)) channel.rmdir(remotePath) else channel.rm(remotePath)
    }

    override fun rename(from: String, to: String) = channel.rename(from, to)

    override fun openInput(remotePath: String): InputStream = channel.get(remotePath)

    override fun openOutput(remotePath: String): OutputStream = channel.put(remotePath)

    override fun sizeOf(remotePath: String): Long =
        runCatching { channel.stat(remotePath).size }.getOrDefault(0L)

    override fun close() {
        runCatching { channel.disconnect() }
        runCatching { session.disconnect() }
    }
}

class WebDavRemoteBackend(
    private val connection: SavedConnection,
    password: String?,
) : RemoteBackend {
    override val connectionId: String = connection.id
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val auth = Credentials.basic(connection.username, password ?: "")
    private val base: String = buildString {
        append(if (connection.webDavHttps) "https" else "http")
        append("://")
        append(connection.host)
        if (connection.port != connection.protocol.defaultPort &&
            !(connection.webDavHttps && connection.port == 443) &&
            !(!connection.webDavHttps && connection.port == 80)
        ) {
            append(':').append(connection.port)
        }
        val root = connection.initialPath.ifBlank { "/" }
        append(if (root.startsWith('/')) root.trimEnd('/') else "/$root")
    }

    private fun url(remotePath: String): String {
        val rel = remotePath.trimStart('/')
        return if (rel.isEmpty()) "$base/" else "$base/$rel"
    }

    override fun list(remotePath: String): List<FileEntry> {
        val body = """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:displayname/><d:getcontentlength/><d:resourcetype/><d:getlastmodified/></d:prop></d:propfind>"""
        val req = Request.Builder()
            .url(url(remotePath))
            .header("Authorization", auth)
            .header("Depth", "1")
            .method("PROPFIND", body.toRequestBody("application/xml".toMediaTypeOrNull()))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("WebDAV PROPFIND ${resp.code}")
            val xml = resp.body?.string().orEmpty()
            return parsePropfind(xml, remotePath)
        }
    }

    private fun parsePropfind(xml: String, remotePath: String): List<FileEntry> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        val doc = factory.newDocumentBuilder().parse(xml.byteInputStream())
        val responses = doc.getElementsByTagNameNS("DAV:", "response")
        val out = ArrayList<FileEntry>()
        for (i in 0 until responses.length) {
            val node = responses.item(i)
            val href = node.childNodes.let { kids ->
                (0 until kids.length).map { kids.item(it) }
                    .firstOrNull { it.localName == "href" }?.textContent
            } ?: continue
            val name = href.trimEnd('/').substringAfterLast('/')
            if (name.isEmpty()) continue
            val selfPath = if (remotePath == "/" || remotePath.isEmpty()) "/$name" else "${remotePath.trimEnd('/')}/$name"
            // skip the collection itself
            val pathNorm = href.trimEnd('/')
            val baseNorm = url(remotePath).removePrefix(base).trimEnd('/')
            if (pathNorm.endsWith(remotePath.trimEnd('/')) && !href.removePrefix(url("")).contains('/')) {
                // still include children only: if href equals requested collection, skip
            }
            val isDir = node.textContent.contains("collection")
            val size = Regex("""<d:getcontentlength[^>]*>(\d+)</d:getcontentlength>""", RegexOption.IGNORE_CASE)
                .find(node.textContent)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            // Deduplicate self
            val reqName = remotePath.trim('/').substringAfterLast('/')
            if (name == reqName && (remotePath == "/" || remotePath.endsWith("/$name") || remotePath.endsWith(name))) {
                if (i == 0) continue
            }
            if (i == 0 && (href.trimEnd('/').endsWith(remotePath.trimEnd('/')) || remotePath == "/")) {
                // first response is often self
                if (remotePath != "/" && name == remotePath.trim('/').substringAfterLast('/')) continue
                if (remotePath == "/" && href.trimEnd('/') == base.trimEnd('/')) continue
            }
            out += FileEntry(
                path = RemotePaths.toVirtual(connectionId, selfPath),
                name = name,
                isDirectory = isDir,
                sizeBytes = size,
                modifiedEpochMs = 0L,
                isHidden = name.startsWith('.'),
            )
        }
        // Filter duplicate self by comparing virtual path remote relative to listing root
        val rootVirtual = RemotePaths.toVirtual(connectionId, if (remotePath.isBlank()) "/" else remotePath)
        return out.filter { it.path != rootVirtual }.distinctBy { it.path }
    }

    override fun exists(remotePath: String): Boolean {
        val req = Request.Builder().url(url(remotePath)).header("Authorization", auth).head().build()
        return client.newCall(req).execute().use { it.isSuccessful }
    }

    override fun isDirectory(remotePath: String): Boolean =
        remotePath == "/" || runCatching { list(remotePath); true }.getOrDefault(false)

    override fun mkdir(remotePath: String) {
        val req = Request.Builder().url(url(remotePath)).header("Authorization", auth)
            .method("MKCOL", ByteArray(0).toRequestBody(null)).build()
        client.newCall(req).execute().use {
            if (!it.isSuccessful && it.code != 405) throw IllegalStateException("MKCOL ${it.code}")
        }
    }

    override fun delete(remotePath: String) {
        val req = Request.Builder().url(url(remotePath)).header("Authorization", auth).delete().build()
        client.newCall(req).execute().use {
            if (!it.isSuccessful) throw IllegalStateException("DELETE ${it.code}")
        }
    }

    override fun rename(from: String, to: String) {
        val req = Request.Builder().url(url(from)).header("Authorization", auth)
            .header("Destination", url(to)).method("MOVE", null).build()
        client.newCall(req).execute().use {
            if (!it.isSuccessful) throw IllegalStateException("MOVE ${it.code}")
        }
    }

    override fun openInput(remotePath: String): InputStream {
        val req = Request.Builder().url(url(remotePath)).header("Authorization", auth).get().build()
        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) {
            resp.close()
            throw IllegalStateException("GET ${resp.code}")
        }
        return resp.body!!.byteStream()
    }

    override fun openOutput(remotePath: String): OutputStream {
        val buf = java.io.ByteArrayOutputStream()
        return object : OutputStream() {
            override fun write(b: Int) = buf.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = buf.write(b, off, len)
            override fun close() {
                val req = Request.Builder().url(url(remotePath)).header("Authorization", auth)
                    .put(buf.toByteArray().toRequestBody("application/octet-stream".toMediaTypeOrNull()))
                    .build()
                client.newCall(req).execute().use {
                    if (!it.isSuccessful) throw IllegalStateException("PUT ${it.code}")
                }
            }
        }
    }

    override fun sizeOf(remotePath: String): Long {
        val req = Request.Builder().url(url(remotePath)).header("Authorization", auth).head().build()
        return client.newCall(req).execute().use { it.header("Content-Length")?.toLongOrNull() ?: 0L }
    }

    override fun close() = Unit
}
