package ai.victorious.filemanager.media.extractors

import ai.victorious.filemanager.core.media.demux.Dts
import ai.victorious.filemanager.core.media.demux.FrameInfo
import ai.victorious.filemanager.core.media.demux.Split
import ai.victorious.filemanager.core.media.demux.TrueHd
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ConstantBitrateSeekMap
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.TrackOutput

/**
 * Media3 extractor for bare DTS (`.dts`) and Dolby TrueHD (`.thd`) elementary streams, which
 * Media3 itself cannot open. Framing comes from `core/.../media/demux/ElementaryAudio.kt`;
 * decoding is done by the FFmpeg extension (`dca`, `truehd`). Seeking is approximate
 * (average bitrate measured over the first 256 KB, then resync to the next frame).
 */
@UnstableApi
class ElementaryAudioExtractor(private val kind: Kind) : Extractor {
    enum class Kind { DTS, TRUEHD }

    private lateinit var output: ExtractorOutput
    private lateinit var track: TrackOutput
    private var buf = ByteArray(BUFFER)
    private var len = 0
    private var eof = false
    private var prepared = false
    private var timeUs = 0L
    private var last: FrameInfo? = null

    private fun split(b: ByteArray, n: Int, end: Boolean): Split = when (kind) {
        Kind.DTS -> Dts.split(b, n, end)
        Kind.TRUEHD -> TrueHd.split(b, n, end, last)
    }

    override fun sniff(input: ExtractorInput): Boolean {
        val b = ByteArray(SNIFF)
        val n = peekUpTo(input, b)
        val s = when (kind) {
            Kind.DTS -> Dts.split(b, n, false)
            Kind.TRUEHD -> TrueHd.split(b, n, false, null)
        }
        return s.frames.size >= MIN_FRAMES && s.frames.first().offset < MAX_LEADING_JUNK
    }

    override fun init(output: ExtractorOutput) {
        this.output = output
        track = output.track(0, C.TRACK_TYPE_AUDIO)
    }

    private fun prepare(input: ExtractorInput) {
        val b = ByteArray(PROBE)
        val n = peekUpTo(input, b)
        input.resetPeekPosition()
        val s = when (kind) {
            Kind.DTS -> Dts.split(b, n, n < b.size)
            Kind.TRUEHD -> TrueHd.split(b, n, n < b.size, null)
        }
        val first = s.frames.firstOrNull()
            ?: throw androidx.media3.common.ParserException.createForMalformedContainer("no ${kind.name} frames", null)
        val bytes = s.frames.sumOf { it.size.toLong() }
        val samples = s.frames.sumOf { it.info.samplesPerFrame.toLong() }
        val bitrate = if (samples > 0) (bytes * 8 * first.info.sampleRate / samples).toInt() else C.RATE_UNSET_INT
        val maxFrame = s.frames.maxOf { it.size }
        val format = Format.Builder()
            .setSampleMimeType(if (kind == Kind.DTS) MimeTypes.AUDIO_DTS else MimeTypes.AUDIO_TRUEHD)
            .setSampleRate(first.info.sampleRate)
            .setChannelCount(first.info.channels)
            .setAverageBitrate(bitrate)
            .setMaxInputSize(maxOf(maxFrame * 2, if (kind == Kind.DTS) 65536 else 16384))
            .build()
        track.format(format)
        output.seekMap(
            if (bitrate > 0) ConstantBitrateSeekMap(input.length, input.position + first.offset, bitrate, C.LENGTH_UNSET, false)
            else androidx.media3.extractor.SeekMap.Unseekable(C.TIME_UNSET),
        )
        output.endTracks()
        prepared = true
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (!prepared) prepare(input)
        if (len == buf.size) buf = buf.copyOf(buf.size * 2) // a frame larger than the buffer
        if (!eof) {
            val n = input.read(buf, len, buf.size - len)
            if (n == C.RESULT_END_OF_INPUT) eof = true else len += n
        }
        val s = split(buf, len, eof)
        for (f in s.frames) {
            track.sampleData(ParsableByteArray(buf.copyOfRange(f.offset, f.offset + f.size)), f.size)
            track.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, f.size, 0, null)
            timeUs += f.info.samplesPerFrame * 1_000_000L / f.info.sampleRate
            last = f.info
        }
        val consumed = s.consumed.coerceIn(0, len)
        System.arraycopy(buf, consumed, buf, 0, len - consumed)
        len -= consumed
        return if (eof && s.frames.isEmpty()) Extractor.RESULT_END_OF_INPUT else Extractor.RESULT_CONTINUE
    }

    override fun seek(position: Long, timeUs: Long) {
        len = 0; eof = false; last = null
        this.timeUs = timeUs
    }

    override fun release() {}

    private companion object {
        const val BUFFER = 64 * 1024
        const val SNIFF = 64 * 1024
        const val PROBE = 256 * 1024
        const val MIN_FRAMES = 2
        const val MAX_LEADING_JUNK = 4096

        fun peekUpTo(input: ExtractorInput, b: ByteArray): Int {
            var n = 0
            while (n < b.size) {
                val r = input.peek(b, n, b.size - n)
                if (r == C.RESULT_END_OF_INPUT) break
                n += r
            }
            return n
        }
    }
}
