package ai.victorious.filemanager.core.net

import kotlinx.serialization.Serializable

@Serializable
enum class RemoteProtocol {
    FTP,
    FTPS,
    SFTP,
    SMB,
    WEBDAV,
    GDRIVE,
    DROPBOX,
    ONEDRIVE,
    S3,
    ;

    val defaultPort: Int
        get() = when (this) {
            FTP -> 21
            FTPS -> 990
            SFTP -> 22
            SMB -> 445
            WEBDAV -> 443
            GDRIVE, DROPBOX, ONEDRIVE, S3 -> 443
        }

    val isCloud: Boolean get() = this == GDRIVE || this == DROPBOX || this == ONEDRIVE
    val isObjectStore: Boolean get() = this == S3
}

@Serializable
enum class AuthKind { PASSWORD, PRIVATE_KEY }

@Serializable
data class SavedConnection(
    val id: String,
    val name: String,
    val protocol: RemoteProtocol,
    val host: String,
    val port: Int,
    val username: String = "",
    val initialPath: String = "/",
    val authKind: AuthKind = AuthKind.PASSWORD,
    /** Display-only hint (e.g. key file name). Never a secret. */
    val keyLabel: String = "",
    /** SMB share name when protocol is SMB. */
    val share: String = "",
    /** Use HTTPS for WebDAV when true. */
    val webDavHttps: Boolean = true,
    /** S3: region (e.g. us-east-1 / auto for R2). */
    val region: String = "",
    /** S3: bucket name. */
    val bucket: String = "",
    /** S3: custom endpoint host for R2/MinIO/B2 (empty = AWS s3.<region>.amazonaws.com). */
    val endpoint: String = "",
    /** S3: force path-style addressing (MinIO/older endpoints). */
    val s3PathStyle: Boolean = false,
)

data class ConnectionDraft(
    val name: String,
    val protocol: RemoteProtocol,
    val host: String,
    val port: Int? = null,
    val username: String = "",
    val password: String? = null,
    val privateKeyPem: String? = null,
    val initialPath: String = "/",
    val authKind: AuthKind = AuthKind.PASSWORD,
    val keyLabel: String = "",
    val share: String = "",
    val webDavHttps: Boolean = true,
    val region: String = "",
    val bucket: String = "",
    val endpoint: String = "",
    val s3PathStyle: Boolean = false,
    /** S3 secret access key (stored encrypted, never logged). */
    val s3SecretKey: String? = null,
)

/**
 * Parse a connection URI like `sftp://user@host:22/path` or `ftp://host`.
 * Password in the URI (user:pass@host) is accepted for import but callers must
 * not log the raw URI — use [CredentialRedactor].
 */
object ConnectionUriParser {
    private val URI_RE = Regex(
        """^(?<scheme>ftp|ftps|sftp|smb|webdav|davs|dav)://(?:(?<user>[^:@/]+)(?::(?<pass>[^@/]*))?@)?(?<host>\[[^\]]+\]|[^:/]+)(?::(?<port>\d+))?(?<path>/.*)?$""",
        RegexOption.IGNORE_CASE,
    )

    data class Parsed(
        val protocol: RemoteProtocol,
        val host: String,
        val port: Int,
        val username: String,
        val password: String?,
        val path: String,
        val webDavHttps: Boolean,
    )

    fun parse(uri: String): Parsed {
        val m = URI_RE.matchEntire(uri.trim())
            ?: throw IllegalArgumentException("Unrecognized connection URI")
        val scheme = m.groups["scheme"]!!.value.lowercase()
        val (protocol, https) = when (scheme) {
            "ftp" -> RemoteProtocol.FTP to false
            "ftps" -> RemoteProtocol.FTPS to false
            "sftp" -> RemoteProtocol.SFTP to false
            "smb" -> RemoteProtocol.SMB to false
            "webdav", "dav" -> RemoteProtocol.WEBDAV to false
            "davs" -> RemoteProtocol.WEBDAV to true
            else -> throw IllegalArgumentException("Unsupported scheme: $scheme")
        }
        val host = m.groups["host"]!!.value.trim('[', ']')
        val port = m.groups["port"]?.value?.toIntOrNull() ?: protocol.defaultPort
        // Userinfo and path are percent-encoded in a URI ("p@ss" can only appear as "p%40ss").
        val user = percentDecode(m.groups["user"]?.value.orEmpty())
        val pass = m.groups["pass"]?.value?.let(::percentDecode)
        val path = percentDecode(m.groups["path"]?.value ?: "/")
        return Parsed(protocol, host, port, user, pass, path, https)
    }

    /** %XX (UTF-8) only: unlike URLDecoder, '+' stays '+', and a '%' not followed by two hex digits stays literal. */
    internal fun percentDecode(s: String): String {
        if ('%' !in s) return s
        val bytes = s.toByteArray(Charsets.UTF_8)
        val out = java.io.ByteArrayOutputStream(bytes.size)
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt()
            val hi = if (b == '%'.code && i + 2 < bytes.size) Character.digit(bytes[i + 1].toInt().toChar(), 16) else -1
            val lo = if (hi >= 0) Character.digit(bytes[i + 2].toInt().toChar(), 16) else -1
            if (hi >= 0 && lo >= 0) { out.write(hi * 16 + lo); i += 3 } else { out.write(b); i++ }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}
