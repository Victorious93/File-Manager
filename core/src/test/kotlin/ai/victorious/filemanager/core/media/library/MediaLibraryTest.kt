package ai.victorious.filemanager.core.media.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaLibraryTest {
    private fun song(path: String, title: String, artist: String = "", album: String = "", track: Int = 0, dur: Long = 200_000, mod: Long = 0) =
        LibraryItem(path, title, artist, album, dur, isVideo = false, modifiedMs = mod, trackNumber = track)

    private val items = listOf(
        song("/m/Beyonce/Halo.mp3", "Halo", "Beyoncé", "I Am... Sasha Fierce", 2, mod = 30),
        song("/m/Beyonce/IfIWere.mp3", "If I Were a Boy", "Beyoncé", "I Am... Sasha Fierce", 1, mod = 10),
        song("/m/Adele/Hello.mp3", "Hello", "Adele", "25", 1, mod = 20),
        song("/m/misc/untagged.mp3", "untagged"),
        LibraryItem("/v/Trip/beach.mp4", "beach", durationMs = 60_000, isVideo = true, modifiedMs = 40),
    )
    private val index = MediaLibraryIndex(items + items.first())

    @Test fun dedupesAndSplitsSongsFromVideos() {
        assertEquals(5, index.all.size)
        assertEquals(4, index.songs.size)
        assertEquals(listOf("beach"), index.videos.map { it.title })
    }

    @Test fun artistsAreGroupedAccentInsensitiveAndUnknownGoesLast() {
        val a = index.artists()
        assertEquals(listOf("Adele", "Beyoncé", LibraryItem.UNKNOWN_ARTIST), a.map { it.title })
        // Album track order inside the artist.
        assertEquals(listOf("If I Were a Boy", "Halo"), a[1].items.map { it.title })
        assertEquals("1 album · 2 songs", a[1].subtitle)
    }

    @Test fun albumsAndFolders() {
        assertEquals(listOf("25", "I Am... Sasha Fierce", LibraryItem.UNKNOWN_ALBUM), index.albums().map { it.title })
        val f = index.folders()
        assertEquals(listOf("Adele", "Beyonce", "misc", "Trip"), f.map { it.title })
        assertTrue(f.last().subtitle.startsWith("1 video"))
    }

    @Test fun searchMatchesEveryWordIgnoringCaseAndAccentsAndRanksTitlesFirst() {
        assertEquals(listOf("Halo", "If I Were a Boy"), index.search("beyonce").map { it.title }.sorted())
        assertEquals(listOf("Halo"), index.search("HALO beyonce").map { it.title })
        assertEquals(listOf("Hello"), index.search("hel").map { it.title })
        assertTrue(index.search("   ").isEmpty())
        assertTrue(index.search("halo adele").isEmpty())
        // A title hit outranks a file-name-only hit.
        val idx = MediaLibraryIndex(listOf(song("/a/x_rain.mp3", "Other"), song("/a/y.mp3", "Rain")))
        assertEquals(listOf("Rain", "Other"), idx.search("rain").map { it.title })
    }

    @Test fun sorting() {
        assertEquals("beach", index.sorted(index.all, LibrarySort.RECENTLY_ADDED).first().title)
        assertEquals(listOf("beach", "Halo", "Hello", "If I Were a Boy", "untagged"), index.sorted(index.all, LibrarySort.TITLE).map { it.title })
    }

    @Test fun guessesTagsFromFileNames() {
        assertEquals(Triple("Daft Punk", "One More Time", 1), LibraryText.guessFromFileName("01 - Daft Punk - One More Time.mp3"))
        assertEquals(Triple("", "Song", 0), LibraryText.guessFromFileName("Song.flac"))
        assertEquals(Triple("A", "B - C", 12), LibraryText.guessFromFileName("12. A - B - C.ogg"))
        assertEquals("beyonce halo", LibraryText.fold("Beyoncé – Halo!"))
    }

    @Test fun mediaExtensions() {
        assertEquals(false, MediaExtensions.kind("a.FLAC"))
        assertEquals(true, MediaExtensions.kind("b.mkv"))
        assertNull(MediaExtensions.kind("c.txt"))
    }

    @Test fun favoritesToggleAndSurviveEncoding() {
        val f = FavoritesStore()
        assertTrue(f.toggle("/a"))
        assertTrue(f.toggle("/b"))
        assertFalse(f.toggle("/a"))
        val back = FavoritesStore.decode(f.encode())
        assertEquals(listOf("/b"), back.all())
        assertTrue(FavoritesStore.decode("not json").all().isEmpty())
    }

    @Test fun historyIsNewestFirstUniqueAndCapped() {
        val h = PlayHistory(max = 3)
        h.record("/a", 1); h.record("/b", 2); h.record("/a", 3); h.record("/c", 4); h.record("/d", 5)
        assertEquals(listOf("/d", "/c", "/a"), h.events().map { it.path })
        val back = PlayHistory.decode(h.encode(), 3)
        assertEquals(h.events(), back.events())
        assertTrue(PlayHistory.decode("{").events().isEmpty())
    }

    @Test fun continueWatchingOnlyListsItemsWithAResumePointThatStillExist() {
        val byPath = items.associateBy { it.path }
        val hist = listOf(PlayEvent("/v/Trip/beach.mp4", 9), PlayEvent("/gone.mp4", 8), PlayEvent("/m/Adele/Hello.mp3", 7), PlayEvent("/m/misc/untagged.mp3", 6))
        val resume = mapOf("/v/Trip/beach.mp4" to 30_000L, "/gone.mp4" to 1000L, "/m/misc/untagged.mp3" to 50_000L)
        val c = LibraryHome.continueItems(hist, resume, byPath)
        assertEquals(listOf("beach", "untagged"), c.map { it.item.title })
        assertEquals(0.5f, c[0].progress, 0.001f)
        assertEquals(30_000L, c[0].remainingMs)
        assertEquals(listOf("beach", "Hello", "untagged"), LibraryHome.recent(hist, byPath).map { it.title })
    }

    @Test fun playbackPrefsRoundTripAndClamp() {
        val p = PlaybackPrefs(speed = 1.5f, maxVideoHeight = 720, repeatMode = 2, shuffle = true)
        assertEquals(p, PlaybackPrefs.decode(PlaybackPrefs.encode(p)))
        val bad = PlaybackPrefs.decode("""{"speed":9,"maxVideoHeight":999,"repeatMode":7}""")
        assertEquals(4f, bad.speed)
        assertEquals(0, bad.maxVideoHeight)
        assertEquals(2, bad.repeatMode)
        assertEquals(PlaybackPrefs(), PlaybackPrefs.decode("garbage"))
        assertEquals("Auto", PlaybackPrefs.qualityLabel(0))
        assertEquals("1080p", PlaybackPrefs.qualityLabel(1080))
    }
}
