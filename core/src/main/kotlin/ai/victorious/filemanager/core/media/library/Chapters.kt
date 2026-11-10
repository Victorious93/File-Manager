package ai.victorious.filemanager.core.media.library

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset

/*
 * Chapters and lyrics that are ALREADY inside a local file or sit next to it. Written from scratch
 * from the public container specs (ISO-BMFF "chpl", Matroska EBML Chapters, ID3v2 CHAP/USLT) and
 * the plain-text OGM / FFmetadata / LRC formats. No network lookup of any kind (no sponsor databases).
 */

data class Chapter(val startMs: Long, val title: String)

object ChapterNav {
    /** Chapters sorted, de-duplicated by start, with blank titles named "Chapter N". */
    fun normalize(list: List<Chapter>): List<Chapter> =
        list.filter { it.startMs >= 0 }.sortedBy { it.startMs }.distinctBy { it.startMs }
            .mapIndexed { i, c -> c.copy(title = c.title.trim().ifBlank { "Chapter ${i + 1}" }) }

    fun currentIndex(chapters: List<Chapter>, positionMs: Long): Int =
        chapters.indexOfLast { it.startMs <= positionMs }

    /** Start of the next chapter, or null in the last one. */
    fun next(chapters: List<Chapter>, positionMs: Long): Long? = chapters.firstOrNull { it.startMs > positionMs + 500 }?.startMs

    /** Like a CD player: more than 3 s into a chapter goes back to its start, otherwise to the one before. */
    fun previous(chapters: List<Chapter>, positionMs: Long): Long {
        val i = currentIndex(chapters, positionMs)
        if (i < 0) return 0
        return if (positionMs - chapters[i].startMs > 3_000 || i == 0) chapters[i].startMs else chapters[i - 1].startMs
    }
}

object ChapterParsers {
    /** Sidecar chapter files we look for next to `movie.mkv`. */
    fun sidecarNames(mediaName: String): List<String> {
        val base = mediaName.substringBeforeLast('.')
        return listOf("$base.chapters.txt", "$base.chapters", "${base}_chapters.txt", "$base.ffmetadata", "$base.ffmeta")
    }

    /** Picks OGM or FFmetadata by content. */
    fun parseText(text: String): List<Chapter> =
        if (text.trimStart().startsWith(";FFMETADATA", ignoreCase = true) || text.contains("[CHAPTER]")) ffmetadata(text) else ogm(text)

    /** `CHAPTER01=00:01:02.345` + `CHAPTER01NAME=Intro`. */
    fun ogm(text: String): List<Chapter> {
        val times = HashMap<String, Long>()
        val names = HashMap<String, String>()
        for (line in text.lineSequence()) {
            val m = Regex("""^\s*CHAPTER(\d+)(NAME)?\s*=\s*(.*)$""", RegexOption.IGNORE_CASE).find(line) ?: continue
            val n = m.groupValues[1].trimStart('0')
            if (m.groupValues[2].isNotEmpty()) names[n] = m.groupValues[3].trim()
            else clock(m.groupValues[3].trim())?.let { times[n] = it }
        }
        return ChapterNav.normalize(times.map { (n, t) -> Chapter(t, names[n].orEmpty()) })
    }

    /** ffmpeg's ;FFMETADATA1 text: `[CHAPTER]`, `TIMEBASE=1/1000`, `START=`, `title=`. */
    fun ffmetadata(text: String): List<Chapter> {
        val out = ArrayList<Chapter>()
        var inCh = false; var num = 1L; var den = 1000L; var start: Long? = null; var title = ""
        fun flush() { if (inCh) start?.let { s -> out += Chapter(if (den == 0L) 0 else s * num * 1000 / den, title) }; start = null; title = "" }
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("[")) { flush(); inCh = line.equals("[CHAPTER]", true); num = 1; den = 1000; continue }
            if (!inCh || line.startsWith(";") || line.startsWith("#")) continue
            val k = line.substringBefore('=').trim().lowercase(); val v = line.substringAfter('=', "").trim()
            when (k) {
                "timebase" -> { num = v.substringBefore('/').toLongOrNull() ?: 1; den = v.substringAfter('/', "1000").toLongOrNull() ?: 1000 }
                "start" -> start = v.toLongOrNull()
                "title" -> title = v.replace("\\=", "=").replace("\\;", ";").replace("\\#", "#").replace("\\\\", "\\")
            }
        }
        flush()
        return ChapterNav.normalize(out)
    }

    /** "1:02:03.5", "02:03", "123.4" -> ms. */
    fun clock(s: String): Long? {
        val parts = s.split(':')
        if (parts.isEmpty() || parts.size > 3) return null
        var sec = 0.0
        for (p in parts) { val v = p.toDoubleOrNull() ?: return null; if (v < 0) return null; sec = sec * 60 + v }
        return (sec * 1000).toLong()
    }

    // ---------------- MP4 / M4A / M4B: Nero "chpl" in moov/udta ----------------

    fun mp4(file: File): List<Chapter> = runCatching<List<Chapter>> {
        RandomAccessFile(file, "r").use { raf: RandomAccessFile ->
            val moov = findBox(raf, 0, raf.length(), "moov") ?: return emptyList()
            val udta = findBox(raf, moov.first, moov.second, "udta") ?: return emptyList()
            val chpl = findBox(raf, udta.first, udta.second, "chpl") ?: return emptyList()
            val len = (chpl.second - chpl.first).toInt().coerceIn(0, 1 shl 20)
            val buf = ByteArray(len); raf.seek(chpl.first); raf.readFully(buf)
            parseChpl(buf)
        }
    }.getOrDefault(emptyList())

    /** chpl payload: version, flags(3), [reserved(4) when version>=1], count, then (start in 100 ns units, title length, title) entries. */
    fun parseChpl(b: ByteArray): List<Chapter> {
        var p = 0
        fun need(n: Int) = p + n <= b.size
        if (!need(4)) return emptyList()
        val version = b[0].toInt() and 0xFF; p = 4
        if (version >= 1) { if (!need(4)) return emptyList(); p += 4 }
        if (!need(1)) return emptyList()
        val count = b[p++].toInt() and 0xFF
        val out = ArrayList<Chapter>()
        repeat(count) {
            if (!need(9)) return ChapterNav.normalize(out)
            var t = 0L; repeat(8) { t = (t shl 8) or (b[p++].toLong() and 0xFF) }
            val n = b[p++].toInt() and 0xFF
            if (!need(n)) return ChapterNav.normalize(out)
            out += Chapter(t / 10_000, String(b, p, n, Charsets.UTF_8)); p += n
        }
        return ChapterNav.normalize(out)
    }

    /** Finds a child box [type] between [from] and [to]; returns its payload range. */
    private fun findBox(raf: RandomAccessFile, from: Long, to: Long, type: String): Pair<Long, Long>? {
        var pos = from
        val hdr = ByteArray(8)
        var guard = 0
        while (pos + 8 <= to && guard++ < 10_000) {
            raf.seek(pos); raf.readFully(hdr)
            var size = ((hdr[0].toLong() and 0xFF) shl 24) or ((hdr[1].toLong() and 0xFF) shl 16) or ((hdr[2].toLong() and 0xFF) shl 8) or (hdr[3].toLong() and 0xFF)
            val name = String(hdr, 4, 4, Charsets.ISO_8859_1)
            var headerLen = 8L
            if (size == 1L) { size = raf.readLong(); headerLen = 16 } else if (size == 0L) size = to - pos
            if (size < headerLen || pos + size > to) return null
            if (name == type) return (pos + headerLen) to (pos + size)
            pos += size
        }
        return null
    }

    // ---------------- Matroska / WebM: Segment > Chapters > EditionEntry > ChapterAtom ----------------

    private const val ID_SEGMENT = 0x18538067L
    private const val ID_CHAPTERS = 0x1043A770L
    private const val ID_EDITION = 0x45B9L
    private const val ID_ATOM = 0xB6L
    private const val ID_TIME_START = 0x91L
    private const val ID_DISPLAY = 0x80L
    private const val ID_STRING = 0x85L

    fun matroska(file: File): List<Chapter> = runCatching<List<Chapter>> {
        RandomAccessFile(file, "r").use { raf: RandomAccessFile ->
            val r = Ebml(raf)
            var pos = 0L
            // Skip the EBML header, then find the Segment.
            var seg: Pair<Long, Long>? = null
            var guard = 0
            while (pos < raf.length() && guard++ < 16) {
                val (id, size, data) = r.header(pos) ?: return emptyList()
                if (id == ID_SEGMENT) { seg = data to (if (size < 0) raf.length() else minOf(raf.length(), data + size)); break }
                if (size < 0) return emptyList()
                pos = data + size
            }
            val (segStart, segEnd) = seg ?: return emptyList()
            pos = segStart; guard = 0
            while (pos < segEnd && guard++ < 100_000) {
                val (id, size, data) = r.header(pos) ?: break
                if (size < 0) break // unknown-size cluster: chapters are normally before it
                if (id == ID_CHAPTERS) {
                    val len = size.coerceAtMost(4L shl 20).toInt()
                    val buf = ByteArray(len); raf.seek(data); raf.readFully(buf)
                    return parseMkvChapters(buf)
                }
                pos = data + size
            }
            emptyList<Chapter>()
        }
    }.getOrDefault(emptyList())

    /** Parses the payload of a Matroska Chapters element (first edition with atoms wins). */
    fun parseMkvChapters(b: ByteArray): List<Chapter> {
        val out = ArrayList<Chapter>()
        for ((id, s, e) in children(b, 0, b.size)) {
            if (id != ID_EDITION) continue
            for ((aid, as_, ae) in children(b, s, e)) {
                if (aid != ID_ATOM) continue
                var start = -1L; var title = ""
                for ((cid, cs, ce) in children(b, as_, ae)) when (cid) {
                    ID_TIME_START -> start = uint(b, cs, ce) / 1_000_000
                    ID_DISPLAY -> if (title.isEmpty()) for ((did, ds, de) in children(b, cs, ce)) if (did == ID_STRING) title = String(b, ds, de - ds, Charsets.UTF_8).trimEnd('\u0000')
                }
                if (start >= 0) out += Chapter(start, title)
            }
            if (out.isNotEmpty()) break
        }
        return ChapterNav.normalize(out)
    }

    private fun uint(b: ByteArray, s: Int, e: Int): Long { var v = 0L; for (i in s until minOf(e, s + 8)) v = (v shl 8) or (b[i].toLong() and 0xFF); return v }

    private fun children(b: ByteArray, from: Int, to: Int): List<Triple<Long, Int, Int>> {
        val out = ArrayList<Triple<Long, Int, Int>>()
        var p = from
        while (p < to) {
            val (id, idLen) = vint(b, p, keepMarker = true) ?: break
            val (size, sLen) = vint(b, p + idLen, keepMarker = false) ?: break
            val ds = p + idLen + sLen
            if (size < 0 || ds + size > to) break
            out += Triple(id, ds, (ds + size).toInt()); p = (ds + size).toInt()
        }
        return out
    }

    /** EBML variable-length integer at [p]. Returns (value, length); size value -1 means "unknown". */
    private fun vint(b: ByteArray, p: Int, keepMarker: Boolean): Pair<Long, Int>? {
        if (p >= b.size) return null
        val first = b[p].toInt() and 0xFF
        if (first == 0) return null
        val len = Integer.numberOfLeadingZeros(first) - 23
        if (len !in 1..8 || p + len > b.size) return null
        var v = if (keepMarker) first.toLong() else (first and (0xFF shr len)).toLong()
        var allOnes = (first and (0xFF shr len)) == (0xFF shr len)
        for (i in 1 until len) { val x = b[p + i].toInt() and 0xFF; if (x != 0xFF) allOnes = false; v = (v shl 8) or x.toLong() }
        return (if (!keepMarker && allOnes) -1L else v) to len
    }

    private class Ebml(val raf: RandomAccessFile) {
        /** (id, size or -1, dataStart). */
        fun header(pos: Long): Triple<Long, Long, Long>? {
            if (pos >= raf.length()) return null
            val buf = ByteArray(16); raf.seek(pos)
            val n = raf.read(buf); if (n <= 0) return null
            val (id, il) = vint(buf.copyOf(n), 0, true) ?: return null
            val (size, sl) = vint(buf.copyOf(n), il, false) ?: return null
            return Triple(id, size, pos + il + sl)
        }
    }

    // ---------------- MP3: ID3v2.3 / 2.4 CHAP and USLT frames ----------------

    data class Id3Extras(val chapters: List<Chapter>, val lyrics: String?)

    fun id3(file: File): Id3Extras = runCatching<Id3Extras> {
        file.inputStream().use { input ->
            val head = ByteArray(10)
            if (input.read(head) != 10) return Id3Extras(emptyList(), null)
            if (String(head, 0, 3, Charsets.ISO_8859_1) != "ID3") return Id3Extras(emptyList(), null)
            val size = syncsafe(head, 6)
            if (size <= 0 || size > (16 shl 20)) return Id3Extras(emptyList(), null)
            val body = ByteArray(size)
            var read = 0
            while (read < size) { val n = input.read(body, read, size - read); if (n <= 0) break; read += n }
            parseId3(head + body.copyOf(read))
        }
    }.getOrDefault(Id3Extras(emptyList(), null))

    /** [tag] is the whole ID3v2 tag including its 10-byte header. */
    fun parseId3(tag: ByteArray): Id3Extras {
        if (tag.size < 10 || String(tag, 0, 3, Charsets.ISO_8859_1) != "ID3") return Id3Extras(emptyList(), null)
        val major = tag[3].toInt()
        val flags = tag[5].toInt()
        if (major !in 3..4 || (flags and 0x80) != 0) return Id3Extras(emptyList(), null) // unsynchronised tags are rare; skip them
        var p = 10
        if ((flags and 0x40) != 0 && p + 4 <= tag.size) p += if (major == 4) syncsafe(tag, p) else 4 + be32(tag, p)
        val end = minOf(tag.size, 10 + syncsafe(tag, 6))
        val chapters = ArrayList<Chapter>()
        var lyrics: String? = null
        for ((id, s, e) in frames(tag, p, end, major)) when (id) {
            "CHAP" -> {
                var q = s
                while (q < e && tag[q].toInt() != 0) q++
                q++
                if (q + 16 > e) continue
                val start = be32(tag, q).toLong() and 0xFFFFFFFFL
                var title = ""
                for ((sid, ss, se) in frames(tag, q + 16, e, major)) if (sid == "TIT2") title = text(tag, ss, se)
                chapters += Chapter(start, title)
            }
            "USLT" -> if (lyrics == null && e - s > 4) {
                val enc = tag[s].toInt()
                val afterDesc = skipString(tag, s + 4, e, enc)
                lyrics = decode(tag, afterDesc, e, enc).trim().ifEmpty { null }
            }
        }
        return Id3Extras(ChapterNav.normalize(chapters), lyrics)
    }

    private fun frames(b: ByteArray, from: Int, to: Int, major: Int): List<Triple<String, Int, Int>> {
        val out = ArrayList<Triple<String, Int, Int>>()
        var p = from
        while (p + 10 <= to) {
            if (b[p].toInt() == 0) break
            val id = String(b, p, 4, Charsets.ISO_8859_1)
            if (!id.all { it in 'A'..'Z' || it in '0'..'9' }) break
            val size = if (major == 4) syncsafe(b, p + 4) else be32(b, p + 4)
            if (size < 0 || p + 10 + size > to) break
            out += Triple(id, p + 10, p + 10 + size)
            p += 10 + size
        }
        return out
    }

    private fun text(b: ByteArray, s: Int, e: Int): String = if (e <= s) "" else decode(b, s + 1, e, b[s].toInt()).trimEnd('\u0000').trim()

    private fun charset(enc: Int): Charset = when (enc) { 1 -> Charsets.UTF_16; 2 -> Charsets.UTF_16BE; 3 -> Charsets.UTF_8; else -> Charsets.ISO_8859_1 }
    private fun decode(b: ByteArray, s: Int, e: Int, enc: Int) = if (e <= s) "" else String(b, s, e - s, charset(enc))

    private fun skipString(b: ByteArray, from: Int, to: Int, enc: Int): Int {
        var p = from
        if (enc == 1 || enc == 2) {
            while (p + 1 < to && !(b[p].toInt() == 0 && b[p + 1].toInt() == 0)) p += 2
            return minOf(to, p + 2)
        }
        while (p < to && b[p].toInt() != 0) p++
        return minOf(to, p + 1)
    }

    private fun syncsafe(b: ByteArray, p: Int) =
        ((b[p].toInt() and 0x7F) shl 21) or ((b[p + 1].toInt() and 0x7F) shl 14) or ((b[p + 2].toInt() and 0x7F) shl 7) or (b[p + 3].toInt() and 0x7F)

    private fun be32(b: ByteArray, p: Int) =
        ((b[p].toInt() and 0xFF) shl 24) or ((b[p + 1].toInt() and 0xFF) shl 16) or ((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF)

    /** Everything we can find for a local file: embedded chapters first, then a sidecar chapter file. */
    fun forFile(file: File): List<Chapter> {
        if (!file.isFile) return emptyList()
        val embedded = when (file.extension.lowercase()) {
            "mp4", "m4v", "m4a", "m4b", "mov", "3gp" -> mp4(file)
            "mkv", "mka", "webm" -> matroska(file)
            "mp3" -> id3(file).chapters
            else -> emptyList()
        }
        if (embedded.isNotEmpty()) return embedded
        val dir = file.parentFile ?: return emptyList()
        for (n in sidecarNames(file.name)) {
            val f = File(dir, n)
            if (f.isFile && f.length() < 1_000_000) return parseText(f.readText()).takeIf { it.isNotEmpty() } ?: continue
        }
        return emptyList()
    }
}

/** Lyrics from a `.lrc` file next to the song (or ID3 USLT text). Synced lines have a time; plain lyrics do not. */
data class LyricLine(val timeMs: Long?, val text: String)

object Lyrics {
    private val STAMP = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val OFFSET = Regex("""^\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)
    private val META = Regex("""^\[[a-zA-Z]+:.*]$""")

    fun parseLrc(text: String): List<LyricLine> {
        var offset = 0L
        val timed = ArrayList<LyricLine>()
        val plain = ArrayList<LyricLine>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            OFFSET.find(line)?.let { offset = it.groupValues[1].toLong(); return@let }
            val stamps = STAMP.findAll(line).toList()
            if (stamps.isEmpty()) {
                if (line.isNotEmpty() && !META.matches(line)) plain += LyricLine(null, line)
                continue
            }
            val words = line.substring(stamps.last().range.last + 1).trim()
            for (m in stamps) {
                val frac = m.groupValues[3]
                val ms = when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.toLong() }
                // A positive [offset] means the lyrics come sooner.
                val t = (m.groupValues[1].toLong() * 60_000 + m.groupValues[2].toLong() * 1000 + ms - offset).coerceAtLeast(0)
                timed += LyricLine(t, words)
            }
        }
        return if (timed.isNotEmpty()) timed.sortedBy { it.timeMs } else plain
    }

    fun plain(text: String): List<LyricLine> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { LyricLine(null, it) }

    /** Index of the line being sung at [positionMs], or -1 (before the first line, or unsynced lyrics). */
    fun currentIndex(lines: List<LyricLine>, positionMs: Long): Int = lines.indexOfLast { it.timeMs != null && it.timeMs <= positionMs }

    fun sidecar(mediaFile: File): File? {
        val base = mediaFile.name.substringBeforeLast('.')
        val dir = mediaFile.parentFile ?: return null
        return listOf("$base.lrc", "$base.LRC").map { File(dir, it) }.firstOrNull { it.isFile && it.length() < 512_000 }
    }

    /** Sidecar .lrc first, then lyrics embedded in an MP3's ID3 tag. */
    fun forFile(file: File): List<LyricLine> {
        sidecar(file)?.let { f -> val l = parseLrc(f.readText()); if (l.isNotEmpty()) return l }
        if (file.extension.equals("mp3", true)) ChapterParsers.id3(file).lyrics?.let { return parseLrc(it) }
        return emptyList()
    }
}
