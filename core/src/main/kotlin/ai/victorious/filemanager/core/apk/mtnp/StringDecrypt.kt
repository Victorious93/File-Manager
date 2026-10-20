package ai.victorious.filemanager.core.apk.mtnp

import java.nio.charset.Charset
import java.util.Base64

/**
 * Interactive string-decrypt helpers. Detects common XOR / Base64 obfuscation patterns and
 * previews plaintext under a **user-selected** method — nothing is applied to the DEX until the
 * caller writes edits via [DexStringEdit].
 */
enum class DecryptMethod { BASE64, BASE64_UTF8_XOR, XOR_SINGLE_KEY, XOR_ROLLING, HEX_UTF8 }

data class DecryptCandidate(
    val original: String,
    val method: DecryptMethod,
    val preview: String,
    val keyHint: String,
    val confidence: Double,
)

object StringDecrypt {
    data class XorKey(val bytes: ByteArray, val label: String) {
        override fun equals(other: Any?) = other is XorKey && bytes.contentEquals(other.bytes) && label == other.label
        override fun hashCode() = bytes.contentHashCode() xor label.hashCode()
    }

    fun isBase64(s: String): Boolean =
        s.length >= 8 && s.length % 4 == 0 && Regex("^[A-Za-z0-9+/]+={0,2}$").matches(s)

    fun isHex(s: String): Boolean = s.length >= 8 && s.length % 2 == 0 && Regex("^[0-9a-fA-F]+$").matches(s)

    fun decodeBase64(s: String): ByteArray? = runCatching { Base64.getDecoder().decode(s) }.getOrNull()

    fun xor(data: ByteArray, key: ByteArray): ByteArray {
        require(key.isNotEmpty())
        return ByteArray(data.size) { i -> (data[i].toInt() xor key[i % key.size].toInt()).toByte() }
    }

    fun asPrintable(data: ByteArray, charset: Charset = Charsets.UTF_8): String? {
        val t = runCatching { String(data, charset) }.getOrNull() ?: return null
        if (t.isEmpty()) return null
        val printable = t.count { it.code in 32..126 || it in "\t\n\r" }
        return if (printable >= t.length * 0.85) t else null
    }

    /** Try Base64 → UTF-8; Base64 → XOR with 1-byte keys 1..255; raw XOR; hex. */
    fun candidates(encoded: String, extraKeys: List<XorKey> = emptyList()): List<DecryptCandidate> {
        val out = mutableListOf<DecryptCandidate>()
        if (isBase64(encoded)) {
            val raw = decodeBase64(encoded)
            if (raw != null) {
                asPrintable(raw)?.let {
                    out += DecryptCandidate(encoded, DecryptMethod.BASE64, it, "—", 0.9)
                }
                for (k in 1..255) {
                    val plain = asPrintable(xor(raw, byteArrayOf(k.toByte()))) ?: continue
                    if (plain == encoded) continue
                    out += DecryptCandidate(encoded, DecryptMethod.BASE64_UTF8_XOR, plain, "0x%02X".format(k), 0.55)
                }
                for (key in extraKeys) {
                    asPrintable(xor(raw, key.bytes))?.let {
                        out += DecryptCandidate(encoded, DecryptMethod.BASE64_UTF8_XOR, it, key.label, 0.7)
                    }
                }
            }
        }
        if (isHex(encoded)) {
            val raw = encoded.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            asPrintable(raw)?.let {
                out += DecryptCandidate(encoded, DecryptMethod.HEX_UTF8, it, "—", 0.85)
            }
        }
        val rawBytes = encoded.toByteArray(Charsets.ISO_8859_1)
        for (k in listOf(0x20, 0x42, 0x55, 0xAA, 0xFF, 0x13, 0x37).map { it.toByte() }) {
            asPrintable(xor(rawBytes, byteArrayOf(k)))?.let { plain ->
                if (plain != encoded && plain.any { it.isLetter() }) {
                    out += DecryptCandidate(encoded, DecryptMethod.XOR_SINGLE_KEY, plain, "0x%02X".format(k.toInt() and 0xff), 0.4)
                }
            }
        }
        for (key in extraKeys) {
            asPrintable(xor(rawBytes, key.bytes))?.let {
                out += DecryptCandidate(encoded, DecryptMethod.XOR_ROLLING, it, key.label, 0.65)
            }
        }
        return out.distinctBy { it.method to it.preview to it.keyHint }
            .sortedByDescending { it.confidence }
            .take(24)
    }

    /** Apply a chosen method; returns null if decoding fails. */
    fun apply(encoded: String, method: DecryptMethod, keyHex: String? = null): String? {
        val key = keyHex?.removePrefix("0x")?.removePrefix("0X")?.let { hx ->
            if (hx.length == 2) byteArrayOf(hx.toInt(16).toByte())
            else hx.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
        return when (method) {
            DecryptMethod.BASE64 -> decodeBase64(encoded)?.let { asPrintable(it) }
            DecryptMethod.HEX_UTF8 -> {
                if (!isHex(encoded)) null
                else asPrintable(encoded.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
            }
            DecryptMethod.BASE64_UTF8_XOR -> {
                val raw = decodeBase64(encoded) ?: return null
                val k = key ?: return null
                asPrintable(xor(raw, k))
            }
            DecryptMethod.XOR_SINGLE_KEY, DecryptMethod.XOR_ROLLING -> {
                val k = key ?: return null
                asPrintable(xor(encoded.toByteArray(Charsets.ISO_8859_1), k))
            }
        }
    }

    /** Scan DEX string pool for encoded-looking literals and attach top candidates. */
    fun scanDexStrings(strings: Collection<String>, limit: Int = 50): List<Pair<String, List<DecryptCandidate>>> =
        strings.asSequence()
            .filter { ai.victorious.filemanager.core.deobf.StringDecryptionHints.looksEncoded(it) || isBase64(it) || isHex(it) }
            .map { it to candidates(it) }
            .filter { it.second.isNotEmpty() }
            .take(limit)
            .toList()
}
