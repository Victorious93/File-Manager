package ai.victorious.filemanager.core.media.demux

/**
 * Minimal ASF (Advanced Systems Format) demuxer for Windows Media Audio, written from the
 * public ASF specification. It reads the Header Object (File Properties + audio Stream
 * Properties), walks fixed-size Data Object packets, reassembles media objects (including
 * compressed payloads and audio-spread descrambling) and hands back timestamped frames.
 *
 * Only audio streams are exposed. DRM-protected files (Content Encryption objects or the
 * encrypted stream flag) are reported as [Header.encrypted] and must not be played.
 */
object Asf {
    val HEADER = guid("75B22630-668E-11CF-A6D9-00AA0062CE6C")
    val DATA = guid("75B22636-668E-11CF-A6D9-00AA0062CE6C")
    val FILE_PROPERTIES = guid("8CABDCA1-A947-11CF-8EE4-00C00C205365")
    val STREAM_PROPERTIES = guid("B7DC0791-A9B7-11CF-8EE6-00C00C205365")
    val AUDIO_MEDIA = guid("F8699E40-5B4D-11CF-A8FD-00805F5C442B")
    val AUDIO_SPREAD = guid("BFC3CD50-618F-11CF-8BB2-00AA00B4E220")
    val CONTENT_ENCRYPTION = guid("2211B3FB-BD23-11D2-B4B7-00A0C955FC6E")
    val EXT_CONTENT_ENCRYPTION = guid("298AE614-2622-4C17-B935-DAE07EE9289C")

    const val WMAV1 = 0x160
    const val WMAV2 = 0x161
    const val WMAPRO = 0x162

    const val MIME_WMAV1 = "audio/x-ms-wma-v1"
    const val MIME_WMAV2 = "audio/x-ms-wma"
    const val MIME_WMAPRO = "audio/x-ms-wma-pro"

    /** Bytes that start the FFmpeg extradata blob carrying block_align etc. (see ffmpeg_jni.cc). */
    val EXTRA_MAGIC = byteArrayOf('V'.code.toByte(), 'F'.code.toByte(), 'W'.code.toByte(), 'M'.code.toByte())

    data class Spread(val span: Int, val packetLength: Int, val chunkLength: Int)

    data class AudioStream(
        val streamNumber: Int,
        val formatTag: Int,
        val channels: Int,
        val sampleRate: Int,
        val avgBytesPerSec: Int,
        val blockAlign: Int,
        val bitsPerSample: Int,
        val extra: ByteArray,
        val spread: Spread?,
        val encrypted: Boolean,
    ) {
        val mimeType: String? get() = mimeFor(formatTag)
    }

    data class Header(
        /** File offset of the first data packet. */
        val firstPacketOffset: Long,
        val packetSize: Int,
        val packetCount: Long,
        val durationUs: Long,
        val prerollMs: Long,
        val audio: List<AudioStream>,
        val encrypted: Boolean,
    )

    fun mimeFor(tag: Int): String? = when (tag) {
        WMAV1 -> MIME_WMAV1; WMAV2 -> MIME_WMAV2; WMAPRO -> MIME_WMAPRO; else -> null
    }

    /** GUID text → on-disk byte order (first three groups little-endian). */
    fun guid(s: String): ByteArray {
        val h = s.replace("-", "")
        val raw = ByteArray(16) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val out = raw.copyOf()
        intArrayOf(3, 2, 1, 0, 5, 4, 7, 6).forEachIndexed { i, src -> out[i] = raw[src] }
        return out
    }

    private fun eq(b: ByteArray, off: Int, g: ByteArray): Boolean {
        if (off + 16 > b.size) return false
        for (i in 0 until 16) if (b[off + i] != g[i]) return false
        return true
    }

    fun sniff(b: ByteArray): Boolean = eq(b, 0, HEADER)

    /** Size of the Header Object from its first 24 bytes, or -1. */
    fun headerObjectSize(b: ByteArray): Long = if (b.size >= 24 && sniff(b)) le64(b, 16) else -1

    /**
     * Parses [b] = bytes from file start covering the whole Header Object plus the 50-byte
     * Data Object header.
     */
    fun parseHeader(b: ByteArray): Header {
        require(sniff(b)) { "not an ASF file" }
        val headerSize = le64(b, 16)
        require(headerSize in 30..b.size.toLong()) { "truncated ASF header" }
        val count = le32(b, 24).toInt()
        var pos = 30
        var packetSize = 0
        var packetCount = 0L
        var durationUs = 0L
        var preroll = 0L
        var encrypted = false
        val audio = ArrayList<AudioStream>()
        repeat(count) {
            if (pos + 24 > headerSize) return@repeat
            val size = le64(b, pos + 16)
            require(size >= 24 && pos + size <= headerSize) { "bad ASF object size" }
            val d = pos + 24
            when {
                eq(b, pos, FILE_PROPERTIES) -> {
                    packetCount = le64(b, d + 32)
                    val play = le64(b, d + 40)
                    preroll = le64(b, d + 56)
                    val minP = le32(b, d + 68).toInt()
                    val maxP = le32(b, d + 72).toInt()
                    require(minP == maxP && minP > 0) { "variable ASF packet size not supported" }
                    packetSize = minP
                    durationUs = maxOf(0L, play / 10 - preroll * 1000)
                }
                eq(b, pos, STREAM_PROPERTIES) && eq(b, d, AUDIO_MEDIA) -> {
                    val tsLen = le32(b, d + 40).toInt()
                    val ecLen = le32(b, d + 44).toInt()
                    val flags = le16(b, d + 48)
                    val ts = d + 54
                    val tag = le16(b, ts)
                    val cb = if (tsLen >= 18) le16(b, ts + 16) else 0
                    val extra = b.copyOfRange(ts + 18, ts + 18 + minOf(cb, maxOf(0, tsLen - 18)))
                    val ec = ts + tsLen
                    val spread = if (eq(b, d + 16, AUDIO_SPREAD) && ecLen >= 5) {
                        Spread(b[ec].toInt() and 0xFF, le16(b, ec + 1), le16(b, ec + 3))
                    } else null
                    audio += AudioStream(
                        streamNumber = flags and 0x7F,
                        formatTag = tag,
                        channels = le16(b, ts + 2),
                        sampleRate = le32(b, ts + 4).toInt(),
                        avgBytesPerSec = le32(b, ts + 8).toInt(),
                        blockAlign = le16(b, ts + 12),
                        bitsPerSample = le16(b, ts + 14),
                        extra = extra,
                        spread = spread,
                        encrypted = flags and 0x8000 != 0,
                    )
                }
                eq(b, pos, CONTENT_ENCRYPTION) || eq(b, pos, EXT_CONTENT_ENCRYPTION) -> encrypted = true
            }
            pos += size.toInt()
        }
        val dataPos = headerSize.toInt()
        require(dataPos + 50 <= b.size && eq(b, dataPos, DATA)) { "ASF data object missing" }
        if (packetCount == 0L) packetCount = le64(b, dataPos + 40)
        require(packetSize > 0) { "ASF file properties missing" }
        return Header(dataPos + 50L, packetSize, packetCount, durationUs, preroll, audio, encrypted || audio.any { it.encrypted })
    }

    /** One payload (fragment of a media object) taken from a data packet. */
    data class Payload(
        val stream: Int,
        val objectNumber: Int,
        val offset: Int,
        val objectSize: Int,
        val presTimeMs: Long,
        val data: ByteArray,
    )

    private fun varLen(type: Int): Int = when (type and 3) { 0 -> 0; 1 -> 1; 2 -> 2; else -> 4 }
    private fun readVar(b: ByteArray, p: Int, type: Int): Long = when (type and 3) {
        0 -> 0; 1 -> (b[p].toLong() and 0xFF); 2 -> le16(b, p).toLong(); else -> le32(b, p)
    }

    /** Parses one data packet of [packetSize] bytes. Malformed packets yield an empty list. */
    fun parsePacket(b: ByteArray): List<Payload> = runCatching { parsePacketOrThrow(b) }.getOrDefault(emptyList())

    private fun parsePacketOrThrow(b: ByteArray): List<Payload> {
        val packetSize = b.size
        var p = 0
        var flags = b[p++].toInt() and 0xFF
        if (flags and 0x80 != 0) { // error correction data
            p += flags and 0x0F
            flags = b[p++].toInt() and 0xFF
        }
        val prop = b[p++].toInt() and 0xFF
        val multiple = flags and 1 != 0
        val seqT = (flags shr 1) and 3
        val padT = (flags shr 3) and 3
        val lenT = (flags shr 5) and 3
        var packetLength = readVar(b, p, lenT).toInt(); p += varLen(lenT)
        p += varLen(seqT)
        val padding = readVar(b, p, padT).toInt(); p += varLen(padT)
        p += 4 + 2 // send time, duration
        if (packetLength == 0 || packetLength > packetSize) packetLength = packetSize
        val end = packetLength - padding
        val repT = prop and 3
        val offT = (prop shr 2) and 3
        val objT = (prop shr 4) and 3
        val count: Int
        var payLenT = 2
        if (multiple) {
            val pf = b[p++].toInt() and 0xFF
            count = pf and 0x3F
            payLenT = (pf shr 6) and 3
        } else count = 1
        val out = ArrayList<Payload>()
        repeat(count) {
            if (p >= end) return out
            val stream = b[p++].toInt() and 0x7F
            val obj = readVar(b, p, objT).toInt(); p += varLen(objT)
            val off = readVar(b, p, offT); p += varLen(offT)
            val rep = readVar(b, p, repT).toInt(); p += varLen(repT)
            if (rep == 1) { // compressed payload: off = presentation time, then a time delta
                val delta = b[p++].toInt() and 0xFF
                val total = if (multiple) readVar(b, p, payLenT).toInt().also { p += varLen(payLenT) } else end - p
                val stop = p + total
                var t = off
                var n = obj
                while (p < stop) {
                    val l = b[p++].toInt() and 0xFF
                    out += Payload(stream, n++, 0, l, t, b.copyOfRange(p, p + l))
                    p += l
                    t += delta
                }
                return@repeat
            }
            var size = 0; var pres = 0L
            if (rep >= 8) { size = le32(b, p).toInt(); pres = le32(b, p + 4) }
            p += rep
            val len = if (multiple) readVar(b, p, payLenT).toInt().also { p += varLen(payLenT) } else end - p
            require(len >= 0 && p + len <= packetSize)
            out += Payload(stream, obj, off.toInt(), if (size > 0) size else len, pres, b.copyOfRange(p, p + len))
            p += len
        }
        return out
    }

    /** Joins payload fragments into whole media objects for one stream. */
    class Reassembler(private val stream: Int) {
        private var buf: ByteArray? = null
        private var filled = 0
        private var obj = -1
        private var time = 0L

        fun reset() { buf = null; filled = 0; obj = -1 }

        /** Returns a complete media object (presentation ms to bytes) or null. */
        fun add(p: Payload): Pair<Long, ByteArray>? {
            if (p.stream != stream) return null
            if (p.offset == 0) {
                buf = ByteArray(p.objectSize); filled = 0; obj = p.objectNumber; time = p.presTimeMs
            } else if (buf == null || p.objectNumber != obj || p.offset != filled) {
                reset(); return null // missing start: drop this object
            }
            val b = buf!!
            val n = minOf(p.data.size, b.size - filled)
            System.arraycopy(p.data, 0, b, filled, n)
            filled += n
            if (filled >= b.size) { reset(); return time to b }
            return null
        }
    }

    /** Undoes audio-spread interleaving (span > 1); returns [obj] unchanged when not applicable. */
    fun descramble(obj: ByteArray, s: Spread?): ByteArray {
        if (s == null || s.span <= 1 || s.chunkLength <= 0 || s.packetLength % s.chunkLength != 0) return obj
        if (obj.size != s.packetLength * s.span) return obj
        val out = ByteArray(obj.size)
        val chunksPerPacket = s.packetLength / s.chunkLength
        var o = 0
        while (o < obj.size) {
            val k = o / s.chunkLength
            val row = k / s.span
            val col = k % s.span
            val src = (row + col * chunksPerPacket) * s.chunkLength
            System.arraycopy(obj, src, out, o, s.chunkLength)
            o += s.chunkLength
        }
        return out
    }

    /** Splits a media object into decoder packets of [blockAlign] bytes when it divides evenly. */
    fun splitBlocks(obj: ByteArray, blockAlign: Int): List<ByteArray> =
        if (blockAlign <= 0 || obj.size <= blockAlign || obj.size % blockAlign != 0) listOf(obj)
        else List(obj.size / blockAlign) { obj.copyOfRange(it * blockAlign, (it + 1) * blockAlign) }

    /**
     * FFmpeg extradata for our patched JNI: "VFWM", block_align, bit_rate, bits_per_coded_sample
     * (little-endian u32 each), then the WAVEFORMATEX extra bytes.
     */
    fun ffmpegExtra(a: AudioStream): ByteArray {
        val out = ByteArray(16 + a.extra.size)
        EXTRA_MAGIC.copyInto(out)
        putLe32(out, 4, a.blockAlign); putLe32(out, 8, a.avgBytesPerSec * 8); putLe32(out, 12, a.bitsPerSample)
        a.extra.copyInto(out, 16)
        return out
    }

    /** Byte offset of the packet holding [timeUs], proportional over the data object. */
    fun seekPacket(h: Header, timeUs: Long): Long {
        if (h.durationUs <= 0 || h.packetCount <= 0) return 0
        return (timeUs.toDouble() / h.durationUs * h.packetCount).toLong().coerceIn(0, h.packetCount - 1)
    }

    internal fun le16(b: ByteArray, p: Int): Int = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)
    internal fun le32(b: ByteArray, p: Int): Long = (le16(b, p).toLong()) or (le16(b, p + 2).toLong() shl 16)
    internal fun le64(b: ByteArray, p: Int): Long = le32(b, p) or (le32(b, p + 4) shl 32)
    internal fun putLe32(b: ByteArray, p: Int, v: Int) { for (i in 0..3) b[p + i] = (v ushr (8 * i)).toByte() }
}
