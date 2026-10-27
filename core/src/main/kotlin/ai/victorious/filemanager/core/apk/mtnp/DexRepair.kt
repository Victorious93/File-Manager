package ai.victorious.filemanager.core.apk.mtnp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.Adler32

/**
 * DEX header repair (Dalvik Executable format). Fixes magic, checksum (Adler-32), SHA-1 signature,
 * file_size, and validates the map list. Written from scratch against the public DEX spec —
 * no MT Manager / NP Manager source.
 *
 * Header layout (little-endian): magic[8], checksum[4], signature[20], file_size[4],
 * header_size[4], endian_tag[4], … map_off at offset 0x34.
 */
object DexRepair {
    const val HEADER_SIZE = 0x70
    const val ENDIAN_LE = 0x12345678
    const val MAGIC = "dex\n"
    /** Canonical DEX 035 magic including NUL terminator. */
    val MAGIC_035 = byteArrayOf(
        'd'.code.toByte(), 'e'.code.toByte(), 'x'.code.toByte(), '\n'.code.toByte(),
        '0'.code.toByte(), '3'.code.toByte(), '5'.code.toByte(), 0,
    )

    /** Known map item types from the DEX format. */
    private val KNOWN_MAP_TYPES = setOf(
        0x0000, 0x0001, 0x0002, 0x0003, 0x0004, 0x0005, 0x0006, 0x0007, 0x0008,
        0x1000, 0x1001, 0x1002, 0x1003, 0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006,
    )

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
        val magicBytes: String = "",
    ) {
        val healthy: Boolean get() = magicOk && endianOk && checksumOk && signatureOk &&
            declaredFileSize == actualSize && mapOk && headerSize == HEADER_SIZE
    }

    fun inspect(bytes: ByteArray): Report {
        require(bytes.size >= HEADER_SIZE) { "DEX too small (${bytes.size} bytes)" }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = bytes.copyOfRange(0, 4).toString(Charsets.ISO_8859_1)
        val magicFull = bytes.copyOfRange(0, 8)
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
            magicOk = magicFull.contentEquals(MAGIC_035) ||
                (magic == MAGIC && magicFull[4] in '0'.code.toByte()..'9'.code.toByte()),
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
            magicBytes = magicFull.joinToString("") {
                if (it in 32..126) it.toInt().toChar().toString() else "\\x%02x".format(it.toInt() and 0xff)
            },
        )
    }

    /**
     * Returns a repaired copy. Fixes magic (to DEX 035 when corrupted or nonstandard),
     * header_size, file_size, optionally clamps a bad map_off, then recomputes SHA-1 signature
     * and Adler-32 checksum. Reports every fix applied.
     */
    fun repair(bytes: ByteArray, clampBadMapOff: Boolean = true, fixMagic: Boolean = true): Pair<ByteArray, Report> {
        require(bytes.size >= HEADER_SIZE) { "DEX too small (${bytes.size} bytes)" }
        val out = bytes.copyOf()
        val fixes = mutableListOf<String>()
        val before = inspect(out)
        val buf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)

        // Magic: rewrite to canonical DEX 035 when asked, or when it already looks DEX-like.
        val looksDexLike = before.endianOk || out.copyOfRange(0, 3).toString(Charsets.ISO_8859_1) == "dex" ||
            (before.headerSize == HEADER_SIZE && before.declaredFileSize in 1..out.size * 2)
        if (fixMagic && !out.copyOfRange(0, 8).contentEquals(MAGIC_035)) {
            if (!looksDexLike && !before.magicOk) {
                throw IllegalArgumentException("Not a DEX file (bad magic: ${before.magicBytes})")
            }
            val old = before.magicBytes
            System.arraycopy(MAGIC_035, 0, out, 0, 8)
            fixes += "magic \"$old\" → dex\\n035\\0"
        } else if (!before.magicOk && !fixMagic) {
            throw IllegalArgumentException("Not a DEX file (bad magic)")
        }

        if (!before.endianOk) {
            // Only auto-fix LE endian tag when the rest of the header looks plausible.
            if (before.headerSize == HEADER_SIZE || before.headerSize == 0) {
                buf.putInt(40, ENDIAN_LE)
                fixes += "endian_tag → 0x12345678 (little-endian)"
            } else {
                throw IllegalArgumentException("Unsupported endianness")
            }
        }

        if (buf.getInt(36) != HEADER_SIZE) {
            val old = buf.getInt(36)
            buf.putInt(36, HEADER_SIZE)
            fixes += "header_size $old → $HEADER_SIZE"
        }
        if (buf.getInt(32) != out.size) {
            val old = buf.getInt(32)
            buf.putInt(32, out.size)
            fixes += "file_size $old → ${out.size}"
        }
        var mapOff = buf.getInt(0x34)
        if (clampBadMapOff && (mapOff < HEADER_SIZE || mapOff + 4 > out.size)) {
            buf.putInt(0x34, 0)
            mapOff = 0
            fixes += "map_off was out of range; set to 0 (rewrite DEX via merge/split to rebuild map)"
        }
        // Signature covers everything after the signature field (offset 32).
        val sig = sha1(out, 32)
        val oldSig = out.copyOfRange(12, 32)
        System.arraycopy(sig, 0, out, 12, 20)
        if (!oldSig.contentEquals(sig)) fixes += "signature (SHA-1) recomputed"
        // Checksum covers everything after the checksum field (offset 12).
        val oldCs = buf.getInt(8)
        val newCs = adler32(out, 12)
        buf.putInt(8, newCs)
        if (oldCs != newCs) fixes += "checksum (Adler-32) recomputed"
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
        var ok = true
        var prevOff = -1
        for (i in 0 until count) {
            val base = mapOff + 4 + i * 12
            val type = buf.getShort(base).toInt() and 0xFFFF
            val unused = buf.getShort(base + 2).toInt() and 0xFFFF
            val size = buf.getInt(base + 4)
            val offset = buf.getInt(base + 8)
            if (type !in KNOWN_MAP_TYPES) {
                issues += "map[$i] unknown type 0x${type.toString(16)}"
                ok = false
            }
            if (unused != 0) {
                issues += "map[$i] unused field is $unused (expected 0)"
            }
            if (size < 0) {
                issues += "map[$i] negative size $size"
                ok = false
            }
            if (offset < 0 || offset > bytes.size) {
                issues += "map[$i] offset $offset out of range"
                ok = false
            }
            if (offset < prevOff) {
                issues += "map[$i] offset $offset is before previous entry"
                ok = false
            }
            prevOff = offset
        }
        return ok
    }

    fun adler32(bytes: ByteArray, from: Int): Int {
        val a = Adler32()
        a.update(bytes, from, bytes.size - from)
        return a.value.toInt()
    }

    fun sha1(bytes: ByteArray, from: Int): ByteArray =
        MessageDigest.getInstance("SHA-1").apply { update(bytes, from, bytes.size - from) }.digest()
}
