package ai.victorious.filemanager.core.media.demux

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures in src/test/resources/demux were made with the stock `ffmpeg` CLI from a 1 s
 * 440 Hz sine (see docs/PHASE18.md); expected counts are what `ffprobe -count_packets` reports.
 */
class DemuxTest {
    private fun res(name: String): ByteArray = javaClass.getResourceAsStream("/demux/$name")!!.readBytes()

    @Test fun dtsFramesMatchFfprobe() {
        val b = res("tone.dts")
        val s = Dts.split(b, b.size, eof = true)
        assertEquals(94, s.frames.size)
        assertEquals(b.size, s.consumed)
        val i = s.frames.first().info
        assertEquals(48000, i.sampleRate); assertEquals(2, i.channels); assertEquals(512, i.samplesPerFrame)
        assertEquals(b.size, s.frames.sumOf { it.size })
    }

    @Test fun dtsIncrementalSplitKeepsPartialFrame() {
        val b = res("tone.dts")
        val half = b.copyOf(b.size / 2)
        val s = Dts.split(half, half.size, eof = false)
        assertTrue(s.frames.isNotEmpty())
        assertTrue(s.consumed < half.size) // the cut frame is kept for the next read
        assertTrue(Dts.isCoreSync(half, s.consumed))
    }

    @Test fun dtsResyncsAfterGarbage() {
        val b = ByteArray(37) { 0x55 } + res("tone.dts")
        val s = Dts.split(b, b.size, eof = true)
        assertEquals(94, s.frames.size)
        assertEquals(37, s.frames.first().offset)
    }

    @Test fun dtsRejectsNonDts() {
        assertNull(Dts.parse(ByteArray(32), 0))
        assertTrue(Dts.split(ByteArray(64), 64, eof = false).frames.isEmpty())
    }

    @Test fun trueHdAccessUnitsMatchFfprobe() {
        val b = res("tone.thd")
        val s = TrueHd.split(b, b.size, eof = true, last = null)
        assertEquals(1200, s.frames.size)
        val i = s.frames.first().info
        assertEquals(48000, i.sampleRate); assertEquals(2, i.channels); assertEquals(40, i.samplesPerFrame)
        assertEquals(b.size, s.frames.sumOf { it.size })
    }

    @Test fun trueHdSplitAcrossReadsCarriesFormat() {
        val b = res("tone.thd")
        val a = TrueHd.split(b, 1000, eof = false, last = null)
        val rest = b.copyOfRange(a.consumed, b.size)
        val c = TrueHd.split(rest, rest.size, eof = true, last = a.frames.last().info)
        assertEquals(1200, a.frames.size + c.frames.size)
    }

    @Test fun trueHdSampleRateCodes() {
        assertEquals(44100, TrueHd.sampleRate(8)); assertEquals(192000, TrueHd.sampleRate(2)); assertEquals(0, TrueHd.sampleRate(5))
    }

    private fun demux(name: String): Triple<Asf.Header, Asf.AudioStream, List<Pair<Long, ByteArray>>> {
        val b = res(name)
        val size = Asf.headerObjectSize(b).toInt()
        val h = Asf.parseHeader(b.copyOf(size + 50))
        val a = h.audio.single()
        val r = Asf.Reassembler(a.streamNumber)
        val out = ArrayList<Pair<Long, ByteArray>>()
        var off = h.firstPacketOffset.toInt()
        while (off + h.packetSize <= b.size) {
            for (p in Asf.parsePacket(b.copyOfRange(off, off + h.packetSize))) r.add(p)?.let { out += it }
            off += h.packetSize
        }
        return Triple(h, a, out)
    }

    @Test fun wmav2HeaderAndPackets() {
        val (h, a, objs) = demux("tone_wmav2.wma")
        assertFalse(h.encrypted)
        assertEquals(Asf.WMAV2, a.formatTag); assertEquals(Asf.MIME_WMAV2, a.mimeType)
        assertEquals(44100, a.sampleRate); assertEquals(2, a.channels); assertEquals(371, a.blockAlign)
        assertEquals(8000, a.avgBytesPerSec)
        assertEquals(1_000_000L, h.durationUs)
        val blocks = objs.flatMap { Asf.splitBlocks(Asf.descramble(it.second, a.spread), a.blockAlign) }
        assertEquals(22, blocks.size)
        assertTrue(blocks.all { it.size == 371 })
        assertEquals(h.prerollMs, objs.first().first) // first object at preroll
    }

    @Test fun wmav1Header() {
        val (_, a, objs) = demux("tone_wmav1.wma")
        assertEquals(Asf.MIME_WMAV1, a.mimeType)
        assertEquals(22, objs.flatMap { Asf.splitBlocks(it.second, a.blockAlign) }.size)
    }

    @Test fun ffmpegExtraLayout() {
        val a = Asf.AudioStream(1, Asf.WMAV2, 2, 44100, 8000, 371, 16, byteArrayOf(1, 2, 3), null, false)
        val e = Asf.ffmpegExtra(a)
        assertArrayEquals(Asf.EXTRA_MAGIC, e.copyOf(4))
        assertEquals(371L, Asf.le32(e, 4)); assertEquals(64000L, Asf.le32(e, 8)); assertEquals(16L, Asf.le32(e, 12))
        assertArrayEquals(byteArrayOf(1, 2, 3), e.copyOfRange(16, 19))
    }

    @Test fun descrambleUndoesSpread() {
        // span 2, packet 4 bytes, chunk 2 bytes: scrambled order holds chunks column-major.
        val s = Asf.Spread(span = 2, packetLength = 4, chunkLength = 2)
        val scrambled = byteArrayOf(0, 0, 2, 2, 1, 1, 3, 3)
        assertArrayEquals(byteArrayOf(0, 0, 1, 1, 2, 2, 3, 3), Asf.descramble(scrambled, s))
        assertArrayEquals(scrambled, Asf.descramble(scrambled, null))
    }

    @Test fun reassemblerDropsObjectWithMissingStart() {
        val r = Asf.Reassembler(1)
        assertNull(r.add(Asf.Payload(1, 5, 10, 20, 0, ByteArray(10))))
        assertNull(r.add(Asf.Payload(1, 6, 0, 20, 7, ByteArray(10))))
        val done = r.add(Asf.Payload(1, 6, 10, 20, 7, ByteArray(10)))!!
        assertEquals(7L, done.first); assertEquals(20, done.second.size)
    }

    @Test fun guidByteOrderAndSniff() {
        assertEquals(0x30, Asf.HEADER[0].toInt() and 0xFF)
        assertEquals(0x26, Asf.HEADER[1].toInt() and 0xFF)
        assertEquals(0xCF, Asf.HEADER[6].toInt() and 0xFF)
        assertTrue(Asf.sniff(res("tone_wmav2.wma")))
        assertFalse(Asf.sniff(res("tone.dts")))
        assertEquals(0L, Asf.seekPacket(demux("tone_wmav2.wma").first, 0))
    }

    @Test fun malformedPacketIsIgnored() {
        assertTrue(Asf.parsePacket(byteArrayOf(0x82.toByte(), 0, 0)).isEmpty())
    }
}
