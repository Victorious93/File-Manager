package ai.victorious.filemanager.core.media.demux

/**
 * Framing for bare elementary audio streams that Media3 has no extractor for:
 * raw DTS (`.dts`, 16-bit big-endian core sync, optional DTS-HD extension substream after
 * each core frame) and raw Dolby TrueHD (`.thd`).
 *
 * Written from the public bitstream descriptions (ETSI TS 102 114 for the DTS core header;
 * the MLP/TrueHD access-unit and major-sync layout). Pure Kotlin so it is unit-testable;
 * the Media3 [androidx.media3.extractor.Extractor] wrappers live in the app module.
 */
data class FrameInfo(
    val sampleRate: Int,
    val channels: Int,
    /** PCM samples per channel carried by one frame / access unit. */
    val samplesPerFrame: Int,
)

/** One frame found in a buffer: [offset] and [size] in bytes. */
data class Frame(val offset: Int, val size: Int, val info: FrameInfo)

/** Result of splitting: whole frames and how many leading bytes may be dropped. */
data class Split(val frames: List<Frame>, val consumed: Int)

object Dts {
    private val SAMPLE_RATES = intArrayOf(0, 8000, 16000, 32000, 0, 0, 11025, 22050, 44100, 0, 0, 12000, 24000, 48000, 0, 0)
    private val AMODE_CHANNELS = intArrayOf(1, 2, 2, 2, 2, 3, 3, 4, 4, 5, 6, 6, 6, 7, 8, 8)
    /** Minimum bytes needed to parse a core header. */
    const val HEADER_BYTES = 11

    fun isCoreSync(b: ByteArray, i: Int): Boolean =
        i + 4 <= b.size && b[i] == 0x7F.toByte() && b[i + 1] == 0xFE.toByte() &&
            b[i + 2] == 0x80.toByte() && b[i + 3] == 0x01.toByte()

    /** Parses the core frame header at [i]; null when it is not a plausible header. Returns info + core size. */
    fun parse(b: ByteArray, i: Int, end: Int = b.size): Pair<FrameInfo, Int>? {
        if (i + HEADER_BYTES > end || !isCoreSync(b, i)) return null
        val r = BitReader(b, i + 4)
        r.skip(1) // FTYPE
        r.skip(5) // deficit sample count
        r.skip(1) // CRC present
        val nblks = r.bits(7)
        val fsize = r.bits(14)
        val amode = r.bits(6)
        val sfreq = r.bits(4)
        r.skip(5) // rate
        r.skip(1 + 1 + 1 + 1 + 1) // fixed, dynf, timef, auxf, hdcd
        r.skip(3 + 1 + 1) // ext audio id, ext audio, aspf
        val lff = r.bits(2)
        val rate = SAMPLE_RATES[sfreq]
        if (rate == 0 || nblks < 5 || fsize < 95) return null
        val ch = (if (amode < 16) AMODE_CHANNELS[amode] else 2) + if (lff != 0) 1 else 0
        return FrameInfo(rate, ch, (nblks + 1) * 32) to (fsize + 1)
    }

    /**
     * Splits [len] bytes of [buf] into frames. A frame runs from one core sync to the next one
     * (so any DTS-HD extension substream stays attached to its core frame). At end of stream
     * ([eof]) the trailing frame is emitted too.
     */
    fun split(buf: ByteArray, len: Int, eof: Boolean): Split {
        val frames = ArrayList<Frame>()
        var pos = 0
        while (pos + 4 <= len && parse(buf, pos, len) == null) pos++
        if (pos + HEADER_BYTES > len) return Split(frames, if (eof) len else maxOf(0, pos))
        while (true) {
            val (info, coreSize) = parse(buf, pos, len) ?: break
            var next = pos + coreSize
            while (next + HEADER_BYTES <= len && parse(buf, next, len) == null) next++
            if (next + HEADER_BYTES <= len) {
                frames += Frame(pos, next - pos, info); pos = next
            } else if (eof && pos + coreSize <= len) {
                frames += Frame(pos, len - pos, info); pos = len; break
            } else break
        }
        return Split(frames, pos)
    }
}

object TrueHd {
    private val CHANNEL_COUNT = intArrayOf(2, 1, 1, 2, 2, 2, 2, 1, 1, 2, 2, 1, 1)

    /** True when the access unit at [i] carries a TrueHD (0xBA) major sync. */
    fun isMajorSync(b: ByteArray, i: Int, end: Int = b.size): Boolean =
        i + 8 <= end && b[i + 4] == 0xF8.toByte() && b[i + 5] == 0x72.toByte() &&
            b[i + 6] == 0x6F.toByte() && b[i + 7] == 0xBA.toByte()

    /** Access unit length in bytes (12-bit word count × 2), or 0 if unreadable. */
    fun auLength(b: ByteArray, i: Int, end: Int = b.size): Int =
        if (i + 2 > end) 0 else (((b[i].toInt() and 0x0F) shl 8) or (b[i + 1].toInt() and 0xFF)) * 2

    fun sampleRate(code: Int): Int = when (code) {
        0 -> 48000; 1 -> 96000; 2 -> 192000; 8 -> 44100; 9 -> 88200; 10 -> 176400; else -> 0
    }

    private fun channels(arrangement: Int, bits: Int): Int {
        var n = 0
        for (k in 0 until bits) if (arrangement and (1 shl k) != 0) n += CHANNEL_COUNT[k]
        return n
    }

    /** Parses the major sync format info of the access unit at [i]. */
    fun parseMajorSync(b: ByteArray, i: Int, end: Int = b.size): FrameInfo? {
        if (i + 12 > end || !isMajorSync(b, i, end)) return null
        val r = BitReader(b, i + 8)
        val rateCode = r.bits(4)
        r.skip(4)
        r.skip(2 + 2)
        val six = r.bits(5)
        r.skip(2)
        val eight = r.bits(13)
        val rate = sampleRate(rateCode)
        if (rate == 0) return null
        val ch = maxOf(channels(six, 5), channels(eight, 13)).coerceIn(1, 8)
        val samples = 40 shl (rateCode and 7)
        return FrameInfo(rate, ch, samples)
    }

    /**
     * Splits [len] bytes into access units. Units before the first major sync are skipped
     * (they cannot be decoded); [last] carries the format from a previous call.
     */
    fun split(buf: ByteArray, len: Int, eof: Boolean, last: FrameInfo?): Split {
        val frames = ArrayList<Frame>()
        var pos = 0
        var info = last
        if (info == null) {
            while (pos + 12 <= len && parseMajorSync(buf, pos, len) == null) pos++
            if (pos + 12 > len) return Split(frames, if (eof) len else pos)
        }
        while (pos + 4 <= len) {
            val major = parseMajorSync(buf, pos, len)
            if (major != null) info = major
            val l = auLength(buf, pos, len)
            if (l < 4) { // lost sync: hunt for the next major sync
                pos++
                while (pos + 12 <= len && parseMajorSync(buf, pos, len) == null) pos++
                if (pos + 12 > len) break
                continue
            }
            if (pos + l > len) break
            frames += Frame(pos, l, info ?: break)
            pos += l
        }
        if (eof) pos = len
        return Split(frames, pos)
    }
}

/** Big-endian MSB-first bit reader over a byte array. */
internal class BitReader(private val b: ByteArray, start: Int) {
    private var bit = start * 8
    fun bits(n: Int): Int {
        var v = 0
        repeat(n) {
            val byte = b[bit ushr 3].toInt() and 0xFF
            v = (v shl 1) or ((byte ushr (7 - (bit and 7))) and 1)
            bit++
        }
        return v
    }
    fun skip(n: Int) { bit += n }
}
