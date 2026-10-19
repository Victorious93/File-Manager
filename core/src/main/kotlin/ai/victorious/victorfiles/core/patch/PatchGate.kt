package ai.victorious.victorfiles.core.patch

import java.io.File
import java.security.MessageDigest
import java.security.cert.X509Certificate
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * One recorded, local authorization: the user confirmed they own, or are legally authorized to
 * modify, the APK identified by [packageName] + [certSha256]. There is no server and no bypass:
 * a patch that targets a package+cert with no matching record is refused.
 */
@Serializable
data class PatchAuthorization(
    val packageName: String,
    /** Uppercase, colon-separated SHA-256 of the current signing certificate, or "UNSIGNED". */
    val certSha256: String,
    val confirmedAtEpochMs: Long,
    /** Free-text note the user typed (e.g. "my app" / ticket id). Never a credential. */
    val note: String = "",
)

/** Thrown when a patch is attempted without a stored authorization for that package+cert. */
class PatchNotAuthorizedException(val packageName: String, val certSha256: String) :
    SecurityException(
        "Not authorized to patch $packageName (cert $certSha256). " +
            "The user must first confirm ownership/authorization via PatchGate.authorize(); there is no bypass.",
    )

/**
 * Local, file-backed authorization store. The gate exists so static patching can only run against
 * APKs the user has explicitly claimed the right to modify. It is intentionally dumb: it records
 * and checks consent, it does not weaken any protection and offers no "skip" path.
 */
class PatchGate(private val storeFile: File) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /** Clear, non-legalese wording the UI must show verbatim before calling [authorize]. */
    companion object {
        const val CONFIRMATION_TEXT: String =
            "I confirm that I own this app, or I am legally authorized by its owner to modify it. " +
                "I will only patch APKs I have the right to change, for lawful purposes such as my own " +
                "development, testing, accessibility, security research, or interoperability. I will not " +
                "use this to bypass licensing, piracy protection, or anyone else's rights."

        /** Uppercase colon-separated SHA-256 of a DER-encoded certificate. */
        fun certSha256(cert: X509Certificate): String = sha256Hex(cert.encoded)

        /** Accepts lowercase/uppercase, with or without colons; returns canonical uppercase colon form. */
        fun normalize(fingerprint: String): String {
            val hex = fingerprint.filter { it.isLetterOrDigit() }.uppercase()
            require(hex == "UNSIGNED" || (hex.length == 64 && hex.all { it in "0123456789ABCDEF" })) {
                "Not a SHA-256 fingerprint: $fingerprint"
            }
            if (hex == "UNSIGNED") return "UNSIGNED"
            return hex.chunked(2).joinToString(":")
        }

        private fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString(":") { "%02X".format(it) }
    }

    fun authorizations(): List<PatchAuthorization> {
        if (!storeFile.exists()) return emptyList()
        return runCatching { json.decodeFromString<List<PatchAuthorization>>(storeFile.readText()) }
            .getOrDefault(emptyList())
    }

    fun isAuthorized(packageName: String, certSha256: String): Boolean {
        val cert = normalize(certSha256)
        return authorizations().any { it.packageName == packageName && it.certSha256 == cert }
    }

    /** Records the user's confirmation for [packageName]+[certSha256]. Idempotent (refreshes timestamp). */
    fun authorize(packageName: String, certSha256: String, note: String = "", nowMs: Long = System.currentTimeMillis()): PatchAuthorization {
        require(packageName.isNotBlank()) { "packageName required" }
        val cert = normalize(certSha256)
        val record = PatchAuthorization(packageName, cert, nowMs, note.take(500))
        val next = authorizations().filterNot { it.packageName == packageName && it.certSha256 == cert } + record
        save(next)
        return record
    }

    /** Removes an authorization. Returns true if one was present. */
    fun revoke(packageName: String, certSha256: String): Boolean {
        val cert = normalize(certSha256)
        val all = authorizations()
        val next = all.filterNot { it.packageName == packageName && it.certSha256 == cert }
        if (next.size == all.size) return false
        save(next)
        return true
    }

    /** Throws [PatchNotAuthorizedException] unless a matching authorization exists. No bypass. */
    fun requireAuthorized(packageName: String, certSha256: String) {
        val cert = normalize(certSha256)
        if (!isAuthorized(packageName, cert)) throw PatchNotAuthorizedException(packageName, cert)
    }

    private fun save(records: List<PatchAuthorization>) {
        storeFile.absoluteFile.parentFile?.mkdirs()
        val tmp = File(storeFile.path + ".tmp")
        tmp.writeText(json.encodeToString(records))
        if (!tmp.renameTo(storeFile)) { storeFile.writeText(tmp.readText()); tmp.delete() }
    }
}
