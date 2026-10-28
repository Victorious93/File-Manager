package ai.victorious.filemanager.core.util

import java.io.File
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DevUtilitiesTest {
    @Test fun knownHashes() {
        assertEquals("900150983cd24fb0d6963f7d28e17f72", Hashes.ofText("MD5", "abc"))
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", Hashes.ofText("SHA-1", "abc"))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Hashes.ofText("SHA-256", "abc"))
        assertTrue(Hashes.ofText("SHA-512", "abc").startsWith("ddaf35a193617aba"))
        val f = File.createTempFile("hash", ".bin").apply { writeText("abc"); deleteOnExit() }
        assertEquals(Hashes.ofText("SHA-256", "abc"), Hashes.ofFile("SHA-256", f))
        assertTrue(Hashes.matches("ABcd", " abcd "))
    }

    @Test fun codecRoundTrips() {
        val s = "héllo ✓ world?>"
        assertEquals("aGVsbG8=", Codec.base64Encode("hello"))
        assertEquals(s, Codec.base64Decode(Codec.base64Encode(s)))
        assertEquals(s, Codec.base64Decode(Codec.base64Encode(s, urlSafe = true)))
        assertEquals("616263", Codec.hexEncode("abc"))
        assertEquals("abc", Codec.hexDecode("0x61 62:63"))
        assertEquals(s, Codec.hexDecode(Codec.hexEncode(s)))
    }

    @Test(expected = IllegalArgumentException::class) fun badHex() { Codec.hexDecode("abc") }
    @Test(expected = IllegalArgumentException::class) fun badBase64() { Codec.base64Decode("@@@") }

    @Test fun timestamps() {
        val ny = ZoneId.of("America/New_York")
        val a = Timestamps.fromNumber(1_700_000_000, ny)
        assertEquals("2023-11-14T22:13:20Z", a.utc)
        assertEquals("2023-11-14 17:13:20 EST", a.local)
        assertEquals(a.epochMillis, Timestamps.fromNumber(1_700_000_000_000, ny).epochMillis)
        assertEquals(1_700_000_000L, Timestamps.fromText("2023-11-14T22:13:20Z", ny).epochSeconds)
        assertEquals(1_700_000_000L, Timestamps.fromText("2023-11-14 17:13:20", ny).epochSeconds)
        assertEquals(1_700_000_000L, Timestamps.fromText("2023-11-14T17:13:20-05:00", ny).epochSeconds)
    }

    @Test fun colors() {
        assertEquals("#FF8800", Colors.parse("#f80").hex())
        assertEquals("#80112233", Colors.parse("80112233").hex())
        assertEquals("rgb(255, 136, 0)", Colors.parse("#FF8800").rgb())
        assertEquals(0xFF0000FF.toInt(), Colors.parse("rgb(0,0,255)").argb)
        assertEquals("#7F0A0B0C", Colors.parse("rgba(10, 11, 12, 0.5)").hex())
        val (h, s, v) = Colors.parse("#00FF00").hsv()
        assertEquals(120f, h, 0.01f); assertEquals(1f, s, 0.01f); assertEquals(1f, v, 0.01f)
    }

    @Test(expected = IllegalArgumentException::class) fun badColor() { Colors.parse("#12345") }

    @Test fun regex() {
        val r = RegexTester.run("(\\w+)@(\\w+)\\.com", "a@b.com, c@d.com", replacement = "$2")
        assertNull(r.error); assertEquals(2, r.hits.size)
        assertEquals(listOf("c", "d"), r.hits[1].groups); assertEquals("b, d", r.replaced)
        assertEquals(1, RegexTester.run("^x", "a\nx", multiline = true).hits.size)
        assertEquals(1, RegexTester.run("ABC", "abc", ignoreCase = true).hits.size)
        assertNotNull(RegexTester.run("(", "x").error)
    }
}
