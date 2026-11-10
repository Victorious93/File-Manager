package ai.victorious.filemanager.core.media.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files

class ChaptersTest {
    private val ch = listOf(Chapter(0, "Intro"), Chapter(60_000, "Verse"), Chapter(120_000, "End"))

    @Test fun navigationWorksLikeACdPlayer() {
        assertEquals(1, ChapterNav.currentIndex(ch, 61_000))
        assertEquals(120_000L, ChapterNav.next(ch, 61_000))
        assertNull(ChapterNav.next(ch, 130_000))
        assertEquals(60_000L, ChapterNav.previous(ch, 70_000)) // > 3 s in: restart this chapter
        assertEquals(0L, ChapterNav.previous(ch, 61_000))      // just started: go to the one before
        assertEquals(0L, ChapterNav.previous(ch, 1_000))
        assertEquals(0L, ChapterNav.previous(emptyList(), 5_000))
    }

    @Test fun normalizeSortsDedupesAndNamesBlankChapters() {
        val n = ChapterNav.normalize(listOf(Chapter(5000, ""), Chapter(0, "A"), Chapter(5000, "dup"), Chapter(-1, "bad")))
        assertEquals(listOf(Chapter(0, "A"), Chapter(5000, "Chapter 2")), n)
    }

    @Test fun ogmAndFfmetadataSidecars() {
        val ogm = "CHAPTER01=00:00:00.000\nCHAPTER01NAME=Opening\nCHAPTER02=00:01:02.500\nCHAPTER02NAME=Middle\n"
        assertEquals(listOf(Chapter(0, "Opening"), Chapter(62_500, "Middle")), ChapterParsers.parseText(ogm))
        val ff = ";FFMETADATA1\ntitle=Movie\n[CHAPTER]\nTIMEBASE=1/1000\nSTART=0\nEND=1000\ntitle=One\n[CHAPTER]\nTIMEBASE=1/10\nSTART=50\nEND=60\ntitle=Two \\= 2\n"
        assertEquals(listOf(Chapter(0, "One"), Chapter(5_000, "Two = 2")), ChapterParsers.parseText(ff))
        assertEquals(3_723_500L, ChapterParsers.clock("1:02:03.5"))
        assertNull(ChapterParsers.clock("x:1"))
        assertEquals("movie.chapters.txt", ChapterParsers.sidecarNames("movie.mkv").first())
    }

    private fun chplPayload(version: Int, vararg chapters: Pair<Long, String>): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(version); o.write(byteArrayOf(0, 0, 0))
        if (version >= 1) o.write(byteArrayOf(0, 0, 0, 0))
        o.write(chapters.size)
        for ((ms, t) in chapters) {
            o.write(ByteBuffer.allocate(8).putLong(ms * 10_000).array())
            val b = t.toByteArray(); o.write(b.size); o.write(b)
        }
        return o.toByteArray()
    }

    private fun box(type: String, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + payload.size).putInt(8 + payload.size).put(type.toByteArray(Charsets.ISO_8859_1)).put(payload).array()

    @Test fun mp4NeroChaptersFromARealFileLayout() {
        assertEquals(listOf(Chapter(0, "A"), Chapter(90_000, "B")), ChapterParsers.parseChpl(chplPayload(0, 0L to "A", 90_000L to "B")))
        val chpl = box("chpl", chplPayload(1, 0L to "Start", 30_000L to "Middle"))
        val file = box("ftyp", "M4A isom".toByteArray()) + box("free", ByteArray(5)) +
            box("moov", box("mvhd", ByteArray(100)) + box("udta", chpl)) + box("mdat", ByteArray(64))
        val f = Files.createTempFile("ch", ".m4b").toFile()
        try {
            f.writeBytes(file)
            assertEquals(listOf(Chapter(0, "Start"), Chapter(30_000, "Middle")), ChapterParsers.forFile(f))
            f.writeBytes(box("ftyp", ByteArray(8)) + box("mdat", ByteArray(8)))
            assertTrue(ChapterParsers.mp4(f).isEmpty())
            f.writeBytes(ByteArray(3))
            assertTrue(ChapterParsers.mp4(f).isEmpty())
        } finally { f.delete() }
    }

    // --- tiny EBML writer for the Matroska test ---
    private fun id(v: Long): ByteArray { var n = 1; while (v ushr (8 * n) != 0L) n++; return ByteArray(n) { ((v ushr (8 * (n - 1 - it))) and 0xFF).toByte() } }
    private fun size(s: Int): ByteArray = byteArrayOf((0x40 or (s ushr 8)).toByte(), (s and 0xFF).toByte()) // 2-byte vint
    private fun el(i: Long, data: ByteArray) = id(i) + size(data.size) + data
    private fun u(v: Long) = ByteBuffer.allocate(8).putLong(v).array()

    @Test fun matroskaChaptersAreReadBeforeTheClusters() {
        fun atom(ms: Long, title: String) = el(0xB6, el(0x73C4, u(1)) + el(0x91, u(ms * 1_000_000)) + el(0x80, el(0x85, title.toByteArray()) + el(0x437C, "eng".toByteArray())))
        val chapters = el(0x1043A770, el(0x45B9, atom(0, "Cold open") + atom(125_000, "Act one")))
        val ebmlHeader = el(0x1A45DFA3, el(0x4282, "matroska".toByteArray()))
        val segBody = el(0x1549A966, ByteArray(10)) + chapters + el(0x1F43B675, ByteArray(20))
        val f = Files.createTempFile("ch", ".mkv").toFile()
        try {
            f.writeBytes(ebmlHeader + el(0x18538067, segBody))
            assertEquals(listOf(Chapter(0, "Cold open"), Chapter(125_000, "Act one")), ChapterParsers.forFile(f))
            f.writeBytes(ebmlHeader + el(0x18538067, el(0x1F43B675, ByteArray(20))))
            assertTrue(ChapterParsers.matroska(f).isEmpty())
        } finally { f.delete() }
    }

    // --- ID3v2.4 builder ---
    private fun ss(n: Int) = byteArrayOf(((n shr 21) and 0x7F).toByte(), ((n shr 14) and 0x7F).toByte(), ((n shr 7) and 0x7F).toByte(), (n and 0x7F).toByte())
    private fun frame(fid: String, body: ByteArray) = fid.toByteArray() + ss(body.size) + byteArrayOf(0, 0) + body

    @Test fun id3ChaptersAndEmbeddedLyrics() {
        fun chap(eid: String, start: Int, end: Int, title: String) = frame("CHAP",
            eid.toByteArray() + byteArrayOf(0) + ByteBuffer.allocate(16).putInt(start).putInt(end).putInt(-1).putInt(-1).array() +
                frame("TIT2", byteArrayOf(3) + title.toByteArray()))
        val uslt = frame("USLT", byteArrayOf(3) + "eng".toByteArray() + byteArrayOf(0) + "[00:01.00]First line\n[00:05.50]Second".toByteArray())
        val frames = frame("TIT2", byteArrayOf(0) + "Song".toByteArray()) + chap("c0", 0, 1000, "Part 1") + chap("c1", 42_000, 50_000, "Part 2") + uslt
        val tag = "ID3".toByteArray() + byteArrayOf(4, 0, 0) + ss(frames.size) + frames
        val parsed = ChapterParsers.parseId3(tag)
        assertEquals(listOf(Chapter(0, "Part 1"), Chapter(42_000, "Part 2")), parsed.chapters)
        val lyr = Lyrics.parseLrc(parsed.lyrics!!)
        assertEquals(listOf(1000L, 5500L), lyr.map { it.timeMs })
        val f = Files.createTempFile("ch", ".mp3").toFile()
        try {
            f.writeBytes(tag + ByteArray(32))
            assertEquals(2, ChapterParsers.forFile(f).size)
            assertEquals("Second", Lyrics.forFile(f).last().text)
        } finally { f.delete() }
        assertTrue(ChapterParsers.parseId3(ByteArray(4)).chapters.isEmpty())
    }

    @Test fun sidecarChapterFileIsUsedWhenNothingIsEmbedded() {
        val dir = Files.createTempDirectory("chs").toFile()
        try {
            val v = File(dir, "clip.mp4").apply { writeBytes(ByteArray(16)) }
            File(dir, "clip.chapters.txt").writeText("CHAPTER1=00:00:10\nCHAPTER1NAME=Ten\n")
            assertEquals(listOf(Chapter(10_000, "Ten")), ChapterParsers.forFile(v))
        } finally { dir.deleteRecursively() }
    }

    @Test fun lrcParsingWithOffsetsRepeatsAndPlainText() {
        val l = Lyrics.parseLrc("[ar:Someone]\n[offset:+500]\n[00:10.00][00:30.00]Chorus\n[00:20.5]Verse\n")
        assertEquals(listOf(9_500L, 20_000L, 29_500L), l.map { it.timeMs })
        assertEquals(listOf("Chorus", "Verse", "Chorus"), l.map { it.text })
        assertEquals(1, Lyrics.currentIndex(l, 25_000))
        assertEquals(-1, Lyrics.currentIndex(l, 1_000))
        val plain = Lyrics.parseLrc("Just words\nno times\n")
        assertEquals(listOf(null, null), plain.map { it.timeMs })
        assertEquals(-1, Lyrics.currentIndex(plain, 99_000))
    }
}
