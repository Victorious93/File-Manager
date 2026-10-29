package ai.victorious.filemanager.media.extractors

import ai.victorious.filemanager.core.media.demux.Asf
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput

/**
 * Media3 extractor for ASF/WMA audio (WMA v1, v2, Pro). Demuxing is `core/.../demux/Asf.kt`;
 * decoding is FFmpeg (`wmav1`, `wmav2`, `wmapro`) through the patched JNI that accepts
 * block_align in extradata. DRM-protected files are refused with a clear error.
 */
@UnstableApi
class AsfExtractor : Extractor {
    private lateinit var output: ExtractorOutput
    private lateinit var track: TrackOutput
    private var header: Asf.Header? = null
    private var stream: Asf.AudioStream? = null
    private var reassembler: Asf.Reassembler? = null
    private var packet = ByteArray(0)

    override fun sniff(input: ExtractorInput): Boolean {
        val b = ByteArray(16)
        return input.peekFully(b, 0, 16, true) && Asf.sniff(b)
    }

    override fun init(output: ExtractorOutput) {
        this.output = output
        track = output.track(0, C.TRACK_TYPE_AUDIO)
    }

    private fun readHeader(input: ExtractorInput) {
        val first = ByteArray(24)
        input.readFully(first, 0, 24)
        val size = Asf.headerObjectSize(first)
        if (size < 30 || size > MAX_HEADER) throw ParserException.createForMalformedContainer("bad ASF header size $size", null)
        val all = ByteArray(size.toInt() + 50)
        first.copyInto(all)
        input.readFully(all, 24, all.size - 24)
        val h = runCatching { Asf.parseHeader(all) }.getOrElse {
            throw ParserException.createForMalformedContainer(it.message, it)
        }
        if (h.encrypted) throw ParserException.createForUnsupportedContainerFeature("DRM-protected WMA cannot be played")
        val a = h.audio.firstOrNull { it.mimeType != null }
            ?: throw ParserException.createForUnsupportedContainerFeature(
                "ASF audio codec not supported (tags: ${h.audio.joinToString { "0x" + it.formatTag.toString(16) }})",
            )
        header = h; stream = a
        reassembler = Asf.Reassembler(a.streamNumber)
        packet = ByteArray(h.packetSize)
        track.format(
            Format.Builder()
                .setSampleMimeType(a.mimeType)
                .setSampleRate(a.sampleRate)
                .setChannelCount(a.channels)
                .setAverageBitrate(a.avgBytesPerSec * 8)
                .setInitializationData(listOf(Asf.ffmpegExtra(a)))
                .setMaxInputSize(maxOf(a.blockAlign * 2, h.packetSize * 4, 16384))
                .build(),
        )
        output.seekMap(AsfSeekMap(h))
        output.endTracks()
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (header == null) { readHeader(input); return Extractor.RESULT_CONTINUE }
        val h = header!!; val a = stream!!
        val index = (input.position - h.firstPacketOffset) / h.packetSize
        if (h.packetCount > 0 && index >= h.packetCount) return Extractor.RESULT_END_OF_INPUT
        if (!input.readFully(packet, 0, h.packetSize, true)) return Extractor.RESULT_END_OF_INPUT
        val blockUs = if (a.avgBytesPerSec > 0) a.blockAlign * 1_000_000L / a.avgBytesPerSec else 0L
        for (p in Asf.parsePacket(packet)) {
            val (ms, obj) = reassembler!!.add(p) ?: continue
            val blocks = Asf.splitBlocks(Asf.descramble(obj, a.spread), a.blockAlign)
            var t = maxOf(0L, (ms - h.prerollMs) * 1000)
            for (blk in blocks) {
                track.sampleData(ParsableByteArray(blk), blk.size)
                track.sampleMetadata(t, C.BUFFER_FLAG_KEY_FRAME, blk.size, 0, null)
                t += blockUs
            }
        }
        return Extractor.RESULT_CONTINUE
    }

    override fun seek(position: Long, timeUs: Long) { reassembler?.reset() }

    override fun release() {}

    private class AsfSeekMap(private val h: Asf.Header) : SeekMap {
        override fun isSeekable() = h.durationUs > 0 && h.packetCount > 0
        override fun getDurationUs() = if (h.durationUs > 0) h.durationUs else C.TIME_UNSET
        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
            if (!isSeekable) return SeekMap.SeekPoints(SeekPoint(0, h.firstPacketOffset))
            val idx = Asf.seekPacket(h, timeUs)
            val t = idx * h.durationUs / h.packetCount
            return SeekMap.SeekPoints(SeekPoint(t, h.firstPacketOffset + idx * h.packetSize))
        }
    }

    private companion object { const val MAX_HEADER = 4L * 1024 * 1024 }
}
