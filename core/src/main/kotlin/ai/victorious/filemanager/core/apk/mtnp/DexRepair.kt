package ai.victorious.filemanager.core.apk.mtnp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.Adler32

/**
 * DEX header repair (Dalvik Executable format). Fixes checksum (Adler-32), SHA-1 signature,
 * file_size, and basic map_list sanity. Written from scratch against the public DEX spec —
 * no MT Manager / NP Manager source.
 *
 * Header layout (little-endian): magic[8], checksum[4], signature[20], file_size[4],
 * header_size[4], endian_tag[4], … map_off at offset 0x34.
 */
object DexRepair {
    const val HEADER_SIZE = 0x70
    const val ENDIAN_LE = 0x12345678
    const val MAGIC = "dex\n"

    data class Report(
        val magicOk: Boolean,
        val endianOk: Boolean,
        val headerSize: Int,
        val declaredFileSize: Int,
        val actualSize: Int,
        val checksumOk: Boolean,
        val signatureOk: Boolean,
        val mapOff: Int,
        val mapOk: Boolean,
        val mapIssues: List<String>,
        val fixesApplied: List<String>,
    ) {
        val healthy: Boolean get() = magicOk && endianOk && checksumOk && signatureOk &&
            declaredFileSize == actualSize && mapOk && headerSize == HEADER_SIZE
    }

    fun inspect(bytes: ByteArray): Report {
        require(bytes.size >= HEADER_SIZE) { "DEX too small (${bytes.size} bytes)" }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = bytes.copyOfRange(0, 4).toString(Charsets.ISO_8859_1)
        val checksum = buf.getInt(8)
        val signature = bytes.copyOfRange(12, 32)
        val fileSize = buf.getInt(32)
        val headerSize = buf.getInt(36)
        val endian = buf.getInt(40)
        val mapOff = buf.getInt(0x34)
        val expectedChecksum = adler32(bytes, 12)
        val expectedSig = sha1(bytes, 32)
        val mapIssues = mutableListOf<String>()
        val mapOk = validateMap(bytes, mapOff, mapIssues)
        return Report(
            magicOk = magic == MAGIC,
            endianOk = endian == ENDIAN_LE,
            headerSize = headerSize,
            declaredFileSize = fileSize,
            actualSize = bytes.size,
            checksumOk = checksum == expectedChecksum,
            signatureOk = signature.contentEquals(expectedSig),
            mapOff = mapOff,
            mapOk = mapOk,
            mapIssues = mapIssues,
            fixesApplied = emptyList(),
        )
    }

    /**
     * Returns a repaired copy: sets file_size to actual length, then recomputes signature and
     * checksum. Optionally clamps [map_off] into range when it points past EOF (sets to 0 and
     * records the issue — a full map rebuild needs a rewrite via DexPool).
     */
    fun repair(bytes: ByteArray, clampBadMapOff: Boolean = true): Pair<ByteArray, Report> {
        val out = bytes.copyOf()
        val fixes = mutableListOf<String>()
        val before = inspect(out)
        if (!before.magicOk) throw IllegalArgumentException("Not a DEX file (bad magic)")
        if (!before.endianOk) throw IllegalArgumentException("Unsupported endianness")
        val buf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        if (before.headerSize != HEADER_SIZE) {
            buf.putInt(36, HEADER_SIZE)
            fixes += "header_size ${before.headerSize} → $HEADER_SIZE"
        }
        if (before.declaredFileSize != out.size) {
            buf.putInt(32, out.size)
            fixes += "file_size ${before.declaredFileSize} → ${out.size}"
        }
        var mapOff = buf.getInt(0x34)
        if (clampBadMapOff && (mapOff < HEADER_SIZE || mapOff + 4 > out.size)) {
            buf.putInt(0x34, 0)
            mapOff = 0
            fixes += "map_off was out of range; set to 0 (rewrite DEX via merge/split to rebuild map)"
        }
        // Signature covers everything after the signature field (offset 32).
        val sig = sha1(out, 32)
        System.arraycopy(sig, 0, out, 12, 20)
        if (!before.signatureOk) fixes += "signature (SHA-1) recomputed"
        // Checksum covers everything after the checksum field (offset 12).
        buf.putInt(8, adler32(out, 12))
        if (!before.checksumOk) fixes += "checksum (Adler-32) recomputed"
        val after = inspect(out).copy(fixesApplied = fixes)
        return out to after
    }

    private fun validateMap(bytes: ByteArray, mapOff: Int, issues: MutableList<String>): Boolean {
        if (mapOff == 0) return true // empty/absent map is allowed in some truncated dumps
        if (mapOff < HEADER_SIZE || mapOff + 4 > bytes.size) {
            issues += "map_off $mapOff out of range (file ${bytes.size})"
            return false
        }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val count = buf.getInt(mapOff)
        if (count < 0 || count > 100_000) {
            issues += "map size $count looks invalid"
            return false
        }
        val need = mapOff.toLong() + 4 + count.toLong() * 12
        if (need > bytes.size) {
            issues += "map list overflows file (need $need bytes)"
            return false
        }
        return true
    }

    fun adler32(bytes: ByteArray, from: Int): Int {
        val a = Adler32()
        a.update(bytes, from, bytes.size - from)
        return a.value.toInt()
    }

    fun sha1(bytes: ByteArray, from: Int): ByteArray =
        MessageDigest.getInstance("SHA-1").apply { update(bytes, from, bytes.size - from) }.digest()
}
