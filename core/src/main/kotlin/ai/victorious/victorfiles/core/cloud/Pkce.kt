package ai.victorious.victorfiles.core.cloud

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** RFC 7636 PKCE (S256). Verifier/challenge generation for OAuth authorization-code + PKCE. */
object Pkce {
    data class Pair(val verifier: String, val challenge: String, val method: String = "S256")

    private val urlEncoder = Base64.getUrlEncoder().withoutPadding()

    fun generate(random: SecureRandom = SecureRandom()): Pair {
        val bytes = ByteArray(64)
        random.nextBytes(bytes)
        val verifier = urlEncoder.encodeToString(bytes) // 43..128 unreserved chars
        return Pair(verifier, challengeOf(verifier))
    }

    fun challengeOf(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return urlEncoder.encodeToString(digest)
    }
}
