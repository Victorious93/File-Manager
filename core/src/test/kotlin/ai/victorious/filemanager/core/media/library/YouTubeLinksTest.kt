package ai.victorious.filemanager.core.media.library

import ai.victorious.filemanager.core.media.library.YouTubeLinks.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeLinksTest {
    private val id = "dQw4w9WgXcQ"

    @Test fun normalVideoLinksAreCleanedOfTrackingParameters() {
        val l = YouTubeLinks.parse("https://www.youtube.com/watch?v=$id&feature=share&si=abc123")!!
        assertEquals(Kind.VIDEO, l.kind)
        assertEquals(id, l.id)
        assertEquals("https://www.youtube.com/watch?v=$id", l.url)
        assertEquals(YouTubeLinks.YOUTUBE_PACKAGE, l.preferredPackage)
        assertNull(l.fallbackPackage)
    }

    @Test fun shortLinksMobileEmbedLiveAndNoScheme() {
        assertEquals("https://www.youtube.com/watch?v=$id&t=90s", YouTubeLinks.parse("https://youtu.be/$id?t=1m30s")!!.url)
        assertEquals(90, YouTubeLinks.parse("youtu.be/$id?t=90")!!.startSeconds)
        assertEquals(Kind.VIDEO, YouTubeLinks.parse("m.youtube.com/watch?v=$id")!!.kind)
        assertEquals(Kind.VIDEO, YouTubeLinks.parse("https://www.youtube-nocookie.com/embed/$id")!!.kind)
        assertEquals(Kind.VIDEO, YouTubeLinks.parse("https://www.youtube.com/live/$id")!!.kind)
        assertEquals("https://www.youtube.com/shorts/$id", YouTubeLinks.parse("https://youtube.com/shorts/$id?si=x")!!.url)
    }

    @Test fun playlistsAndChannels() {
        val p = YouTubeLinks.parse("https://www.youtube.com/playlist?list=PL1234abcd")!!
        assertEquals(Kind.PLAYLIST, p.kind)
        assertEquals("https://www.youtube.com/playlist?list=PL1234abcd", p.url)
        assertEquals("https://www.youtube.com/watch?v=$id&list=PL1234abcd", YouTubeLinks.parse("https://www.youtube.com/watch?v=$id&list=PL1234abcd&index=3")!!.url)
        assertEquals(Kind.CHANNEL, YouTubeLinks.parse("https://www.youtube.com/@SomeCreator")!!.kind)
        assertEquals("https://www.youtube.com/channel/UCabc", YouTubeLinks.parse("https://youtube.com/channel/UCabc/videos")!!.url)
    }

    @Test fun youTubeMusicGoesToTheMusicAppWithYouTubeAsFallback() {
        val m = YouTubeLinks.parse("https://music.youtube.com/watch?v=$id&list=RDAMVM$id&si=z")!!
        assertEquals(Kind.MUSIC_TRACK, m.kind)
        assertEquals(YouTubeLinks.MUSIC_PACKAGE, m.preferredPackage)
        assertEquals(YouTubeLinks.YOUTUBE_PACKAGE, m.fallbackPackage)
        assertEquals("https://www.youtube.com/watch?v=$id", m.youtubeUrl)
        val pl = YouTubeLinks.parse("https://music.youtube.com/playlist?list=OLAK5uy_abc")!!
        assertEquals(Kind.MUSIC_PLAYLIST, pl.kind)
        assertEquals(Kind.MUSIC_BROWSE, YouTubeLinks.parse("https://music.youtube.com/browse/MPREb_xyz")!!.kind)
        assertEquals(Kind.MUSIC_BROWSE, YouTubeLinks.parse("https://music.youtube.com/")!!.kind)
    }

    @Test fun rejectsLookAlikesAndJunk() {
        listOf(
            "https://youtube.com.evil.example/watch?v=$id",
            "https://evilyoutube.com/watch?v=$id",
            "https://user:pw@www.youtube.com/watch?v=$id",
            "javascript:alert(1)",
            "ftp://www.youtube.com/watch?v=$id",
            "https://www.youtube.com/watch?v=short",
            "https://www.youtube.com/watch?v=$id\"><script>",
            "https://www.youtube.com/",
            "",
            "https://example.com/?u=https://youtu.be/$id",
        ).forEach { assertNull(it, YouTubeLinks.parse(it)) }
    }

    @Test fun extractsEveryDistinctLinkFromText() {
        val text = """
            [InternetShortcut]
            URL=https://youtu.be/$id
            also https://www.youtube.com/watch?v=$id and (https://music.youtube.com/watch?v=abcdefghijk).
            <string>https://www.youtube.com/playlist?list=PLxyz12</string>
            not https://vimeo.com/123
        """.trimIndent()
        val links = YouTubeLinks.extract(text)
        assertEquals(listOf(Kind.VIDEO, Kind.MUSIC_TRACK, Kind.PLAYLIST), links.map { it.kind })
    }

    @Test fun timeParsing() {
        assertEquals(3723, YouTubeLinks.parseTime("1h2m3s"))
        assertEquals(45, YouTubeLinks.parseTime("45s"))
        assertEquals(0, YouTubeLinks.parseTime("abc"))
        assertEquals(0, YouTubeLinks.parseTime(null))
    }

    @Test fun savedLinksAreCleanNewestFirstAndUnique() {
        var list = SavedLinksCodec.add(emptyList(), "https://youtu.be/$id?si=1", null, 1)!!
        list = SavedLinksCodec.add(list, "https://music.youtube.com/watch?v=abcdefghijk", "Song", 2)!!
        list = SavedLinksCodec.add(list, "https://www.youtube.com/watch?v=$id", "Again", 3)!!
        assertEquals(listOf("Again", "Song"), list.map { it.label })
        assertNull(SavedLinksCodec.add(list, "https://example.com", null, 4))
        assertEquals(list, SavedLinksCodec.decode(SavedLinksCodec.encode(list)))
        assertTrue(SavedLinksCodec.decode("oops").isEmpty())
        assertNotNull(list.first().url)
    }
}
