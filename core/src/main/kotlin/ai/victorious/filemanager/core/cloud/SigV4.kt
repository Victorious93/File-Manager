package ai.victorious.filemanager.core.cloud

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AWS Signature Version 4 signer (header-based `Authorization`) for S3-compatible object stores
 * (AWS S3, Cloudflare R2, Backblaze B2 via S3 API, MinIO). Pure JVM, no AWS SDK — verified against
 * the AWS published SigV4 test-suite vectors in SigV4Test.
 *
 * Credentials and the signing key never touch any log; callers pass them in and get back only a
 * header string and the (public) signature hex.
 */
object SigV4 {
    const val ALGORITHM = "AWS4-HMAC-SHA256"
    const val UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD"

    data class Credentials(
        val accessKeyId: String,
        val secretAccessKey: String,
        val sessionToken: String? = null,
    )

    data class Signed(
        val authorization: String,
        val signature: String,
        val signedHeaders: String,
        val credentialScope: String,
    )

    fun sha256Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

    private fun hmac(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /**
     * RFC 3986 encoding as AWS requires: unreserved chars pass through, everything else is
     * percent-encoded. Slashes are kept only when [encodeSlash] is false (used for the path).
     */
    fun uriEncode(input: String, encodeSlash: Boolean): String {
        val sb = StringBuilder()
        for (b in input.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            when {
                ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '_' || ch == '.' || ch == '~' -> sb.append(ch)
                ch == '/' && !encodeSlash -> sb.append('/')
                else -> sb.append('%').append("%02X".format(c))
            }
        }
        return sb.toString()
    }

    private fun canonicalQuery(params: Map<String, String>): String =
        params.entries
            .map { uriEncode(it.key, true) to uriEncode(it.value, true) }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .joinToString("&") { "${it.first}=${it.second}" }

    fun deriveSigningKey(secret: String, dateStamp: String, region: String, service: String): ByteArray {
        val kDate = hmac(("AWS4$secret").toByteArray(Charsets.UTF_8), dateStamp)
        val kRegion = hmac(kDate, region)
        val kService = hmac(kRegion, service)
        return hmac(kService, "aws4_request")
    }

    /**
     * Signs a request. [canonicalUri] is the raw, already-correct path (e.g. `/` or `/my%20key`);
     * [headers] must contain `host` and `x-amz-date`; [payloadHash] is a hex SHA-256 of the body or
     * [UNSIGNED_PAYLOAD]. Returns the `Authorization` header value and components.
     */
    fun sign(
        method: String,
        canonicalUri: String,
        queryParams: Map<String, String>,
        headers: Map<String, String>,
        payloadHash: String,
        credentials: Credentials,
        region: String,
        service: String,
        amzDate: String,
    ): Signed {
        val dateStamp = amzDate.substring(0, 8)
        val sortedHeaders = headers.entries
            .map { it.key.lowercase() to it.value.trim() }
            .sortedBy { it.first }
        val canonicalHeaders = sortedHeaders.joinToString("") { "${it.first}:${it.second}\n" }
        val signedHeaders = sortedHeaders.joinToString(";") { it.first }

        val canonicalRequest = buildString {
            append(method).append('\n')
            append(canonicalUri).append('\n')
            append(canonicalQuery(queryParams)).append('\n')
            append(canonicalHeaders).append('\n')
            append(signedHeaders).append('\n')
            append(payloadHash)
        }

        val credentialScope = "$dateStamp/$region/$service/aws4_request"
        val stringToSign = buildString {
            append(ALGORITHM).append('\n')
            append(amzDate).append('\n')
            append(credentialScope).append('\n')
            append(sha256Hex(canonicalRequest))
        }

        val signingKey = deriveSigningKey(credentials.secretAccessKey, dateStamp, region, service)
        val signature = hex(hmac(signingKey, stringToSign))
        val authorization =
            "$ALGORITHM Credential=${credentials.accessKeyId}/$credentialScope, " +
                "SignedHeaders=$signedHeaders, Signature=$signature"
        return Signed(authorization, signature, signedHeaders, credentialScope)
    }
}
