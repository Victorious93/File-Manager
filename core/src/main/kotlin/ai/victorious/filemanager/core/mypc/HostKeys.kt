package ai.victorious.filemanager.core.mypc

import java.security.MessageDigest
import java.util.Base64

/**
 * Host-key pins in the victorpc v1 form (VictorSuite docs/specs/VICTORPC_PAIRING.md §1):
 * `<keytype>:SHA256:<standard base64 of SHA-256(key blob), padding stripped>`, the same text
 * OpenSSH prints after `SHA256:`. A list of pins is joined with `;` and compared by exact string.
 */
object HostKeys {
    private val KEY_TYPE = Regex("[a-z0-9][a-z0-9@._+-]{0,63}")
    private val B64_NO_PAD = Regex("[A-Za-z0-9+/]{43}")

    /** Formats a pin for a raw SSH public-key blob whose type name is [keyType] (e.g. `ssh-ed25519`). */
    fun fingerprint(keyType: String, blob: ByteArray): String {
        require(KEY_TYPE.matches(keyType)) { "Bad key type" }
        val digest = MessageDigest.getInstance("SHA-256").digest(blob)
        return "$keyType:SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    /**
     * Formats a pin from the blob alone, reading the key type from the blob's first SSH string.
     * The type inside the blob is authoritative (an RSA key is `ssh-rsa` even when the server
     * signs with `rsa-sha2-512`).
     */
    fun fingerprint(blob: ByteArray): String = fingerprint(keyTypeOf(blob), blob)

    /** Formats a pin from an OpenSSH public-key line (`<type> <base64> [comment]`). */
    fun fingerprintOfLine(line: String): String {
        val parts = line.trim().split(Regex("\\s+"))
        require(parts.size >= 2) { "Not a public key line" }
        val blob = Base64.getDecoder().decode(parts[1])
        val type = keyTypeOf(blob)
        require(type == parts[0]) { "Key type does not match its blob" }
        return fingerprint(type, blob)
    }

    /** Reads the first SSH `string` of a key blob: its key type name. */
    fun keyTypeOf(blob: ByteArray): String {
        require(blob.size >= 4) { "Key blob too short" }
        val len = ((blob[0].toInt() and 0xff) shl 24) or ((blob[1].toInt() and 0xff) shl 16) or
            ((blob[2].toInt() and 0xff) shl 8) or (blob[3].toInt() and 0xff)
        require(len in 1..64 && 4 + len <= blob.size) { "Bad key blob" }
        val type = String(blob, 4, len, Charsets.US_ASCII)
        require(KEY_TYPE.matches(type)) { "Bad key type" }
        return type
    }

    /** True when [pin] is a well-formed `<keytype>:SHA256:<43 base64 chars>` string. */
    fun isValidPin(pin: String): Boolean {
        val i = pin.indexOf(":SHA256:")
        if (i <= 0) return false
        return KEY_TYPE.matches(pin.substring(0, i)) && B64_NO_PAD.matches(pin.substring(i + 8))
    }

    /**
     * Parses a `;`-joined pin list. Spaces and newlines around entries are trimmed (people paste
     * them); anything malformed is rejected rather than silently dropped.
     */
    fun parseList(text: String): List<String> {
        val pins = text.split(';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        val bad = pins.firstOrNull { !isValidPin(it) }
        require(bad == null) { "Not a host key pin: $bad (expected <keytype>:SHA256:<base64>)" }
        return pins.distinct()
    }

    fun formatList(pins: List<String>): String = pins.joinToString(";")

    /** Decides whether a presented host key may be used. Pure; see [HostKeyVerdict]. */
    fun check(pins: List<String>, presentedKeyType: String, presentedBlob: ByteArray): HostKeyVerdict {
        val presented = fingerprint(presentedBlob)
        // The algorithm name the server negotiated must belong to the blob's key type family.
        if (!algorithmMatchesKey(presentedKeyType, keyTypeOf(presentedBlob))) {
            return HostKeyVerdict.Mismatch(presented, pins)
        }
        return when {
            pins.isEmpty() -> HostKeyVerdict.Unpinned(presented)
            presented in pins -> HostKeyVerdict.Match(presented)
            else -> HostKeyVerdict.Mismatch(presented, pins)
        }
    }

    /** `rsa-sha2-256/512` are signature algorithms for `ssh-rsa` keys; everything else must be equal. */
    internal fun algorithmMatchesKey(algorithm: String, keyType: String): Boolean = when (keyType) {
        "ssh-rsa" -> algorithm == "ssh-rsa" || algorithm == "rsa-sha2-256" || algorithm == "rsa-sha2-512"
        else -> algorithm == keyType
    }
}

sealed interface HostKeyVerdict {
    val presented: String

    /** The key is one of the pins: go ahead. */
    data class Match(override val presented: String) : HostKeyVerdict

    /**
     * The profile has pins and this key is not one of them. Hard stop: never connect, never
     * fall through to another host, never offer "accept anyway". The fix is to re-pair.
     */
    data class Mismatch(override val presented: String, val expected: List<String>) : HostKeyVerdict

    /**
     * Manual profile with nothing pinned yet. The only place trust-on-first-use is allowed: the
     * user must compare [presented] with the PC's own `ssh-keygen -lf` output and confirm before
     * it is pinned (spec §2.1, manual mode). Nothing is sent to the server before that.
     */
    data class Unpinned(override val presented: String) : HostKeyVerdict
}
