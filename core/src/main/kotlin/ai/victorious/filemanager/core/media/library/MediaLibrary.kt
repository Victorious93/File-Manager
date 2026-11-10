package ai.victorious.filemanager.core.media.library

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.text.Normalizer

/*
 * Local media library (written from scratch for this app). It only indexes files already on the
 * device. Nothing here talks to YouTube, YouTube Music or any other streaming service.
 */

/** One local audio or video file with whatever tags the device's media index (or the file name) gave us. */
@Serializable
data class LibraryItem(
    val path: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0,
    val isVideo: Boolean = false,
    val sizeBytes: Long = 0,
    val modifiedMs: Long = 0,
    val trackNumber: Int = 0,
) {
    val folder: String get() = path.substringBeforeLast('/', "")
    val fileName: String get() = path.substringAfterLast('/')
    val displayArtist: String get() = artist.ifBlank { UNKNOWN_ARTIST }
    val displayAlbum: String get() = album.ifBlank { UNKNOWN_ALBUM }

    companion object {
        const val UNKNOWN_ARTIST = "Unknown artist"
        const val UNKNOWN_ALBUM = "Unknown album"
    }
}

/** A named group of items (an artist, an album or a folder). */
data class LibraryGroup(val key: String, val title: String, val subtitle: String, val items: List<LibraryItem>) {
    val totalDurationMs: Long get() = items.sumOf { it.durationMs }
}

object MediaExtensions {
    val AUDIO = setOf("mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "wma", "mka", "mid", "midi", "ac3", "eac3", "ec3", "dts", "thd", "amr", "aiff", "aif")
    val VIDEO = setOf("mp4", "m4v", "mkv", "webm", "mov", "avi", "3gp", "ts", "m2ts", "wmv", "flv", "mpg", "mpeg")

    fun kind(name: String): Boolean? {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            in AUDIO -> false
            in VIDEO -> true
            else -> null
        }
    }
}

object LibraryText {
    private val MARKS = Regex("\\p{Mn}+")

    /** Lower-case, accents removed, punctuation folded to spaces: "Beyoncé – Halo!" -> "beyonce halo". */
    fun fold(s: String): String =
        MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /**
     * Title/artist guess from a file name when the media index has no tags:
     * "01 - Artist - Song.mp3" -> (Artist, Song, 1); "Song.mp3" -> ("", Song, 0).
     */
    fun guessFromFileName(fileName: String): Triple<String, String, Int> {
        var base = fileName.substringBeforeLast('.').replace('_', ' ').trim()
        var track = 0
        Regex("^(\\d{1,3})[\\s.\\-]+(.+)$").find(base)?.let { m ->
            track = m.groupValues[1].toInt(); base = m.groupValues[2].trim()
        }
        val parts = base.split(" - ").map { it.trim() }.filter { it.isNotEmpty() }
        return if (parts.size >= 2) Triple(parts[0], parts.drop(1).joinToString(" - "), track) else Triple("", base, track)
    }
}

enum class LibrarySort { TITLE, ARTIST, RECENTLY_ADDED, DURATION }

/** Pure grouping, sorting and search over a list of [LibraryItem]s. */
class MediaLibraryIndex(items: List<LibraryItem>) {
    val all: List<LibraryItem> = items.distinctBy { it.path }
    val songs: List<LibraryItem> get() = all.filter { !it.isVideo }
    val videos: List<LibraryItem> get() = all.filter { it.isVideo }

    fun sorted(list: List<LibraryItem>, sort: LibrarySort): List<LibraryItem> = when (sort) {
        LibrarySort.TITLE -> list.sortedBy { LibraryText.fold(it.title) }
        LibrarySort.ARTIST -> list.sortedWith(compareBy({ LibraryText.fold(it.displayArtist) }, { LibraryText.fold(it.displayAlbum) }, { it.trackNumber }, { LibraryText.fold(it.title) }))
        LibrarySort.RECENTLY_ADDED -> list.sortedByDescending { it.modifiedMs }
        LibrarySort.DURATION -> list.sortedByDescending { it.durationMs }
    }

    /** Artists (audio only), A to Z; "Unknown artist" last. */
    fun artists(): List<LibraryGroup> = songs.groupBy { LibraryText.fold(it.displayArtist) }.map { (k, l) ->
        val name = l.first().displayArtist
        val albums = l.map { it.displayAlbum }.distinct().size
        LibraryGroup("artist:$k", name, "$albums album${if (albums == 1) "" else "s"} · ${l.size} song${if (l.size == 1) "" else "s"}", trackOrder(l))
    }.sortedWith(compareBy({ it.title == LibraryItem.UNKNOWN_ARTIST }, { LibraryText.fold(it.title) }))

    /** Albums (audio only), keyed by album + album artist so two "Greatest Hits" stay apart. */
    fun albums(): List<LibraryGroup> = songs.groupBy { LibraryText.fold(it.displayAlbum) + "|" + albumArtistKey(it) }.map { (k, l) ->
        val artists = l.map { it.displayArtist }.distinct()
        val by = if (artists.size == 1) artists.first() else "Various artists"
        LibraryGroup("album:$k", l.first().displayAlbum, "$by · ${l.size} track${if (l.size == 1) "" else "s"}", trackOrder(l))
    }.sortedWith(compareBy({ it.title == LibraryItem.UNKNOWN_ALBUM }, { LibraryText.fold(it.title) }))

    /** Folders with any media, A to Z by folder name. */
    fun folders(): List<LibraryGroup> = all.groupBy { it.folder }.map { (dir, l) ->
        val v = l.count { it.isVideo }
        val a = l.size - v
        LibraryGroup("folder:$dir", dir.substringAfterLast('/').ifBlank { "/" }, listOfNotNull(
            a.takeIf { it > 0 }?.let { "$it audio" }, v.takeIf { it > 0 }?.let { "$it video" }, dir,
        ).joinToString(" · "), l.sortedBy { LibraryText.fold(it.fileName) })
    }.sortedBy { LibraryText.fold(it.title) }

    /**
     * Every word of [query] must appear in the title, artist, album or file name (accents and case
     * ignored). Title matches rank first, then artist/album, then file name only.
     */
    fun search(query: String, limit: Int = 200): List<LibraryItem> {
        val words = LibraryText.fold(query).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return all.mapNotNull { item ->
            val title = LibraryText.fold(item.title)
            val meta = LibraryText.fold(item.artist + " " + item.album)
            val file = LibraryText.fold(item.fileName)
            var score = 0
            for (w in words) {
                score += when {
                    title.split(' ').any { it.startsWith(w) } -> 3
                    title.contains(w) -> 2
                    meta.contains(w) -> 2
                    file.contains(w) -> 1
                    else -> return@mapNotNull null
                }
            }
            item to score
        }.sortedWith(compareByDescending<Pair<LibraryItem, Int>> { it.second }.thenBy { LibraryText.fold(it.first.title) })
            .take(limit).map { it.first }
    }

    private fun albumArtistKey(i: LibraryItem) = i.folder

    private fun trackOrder(l: List<LibraryItem>) =
        l.sortedWith(compareBy({ LibraryText.fold(it.displayAlbum) }, { it.trackNumber }, { LibraryText.fold(it.title) }))
}

/** Favourite media paths, kept in app storage as JSON. */
class FavoritesStore(initial: Collection<String> = emptyList()) {
    private val set = LinkedHashSet(initial)
    fun isFavorite(path: String) = path in set
    /** Returns the new state. */
    fun toggle(path: String): Boolean = if (set.remove(path)) false else { set.add(path); true }
    fun all(): List<String> = set.toList().asReversed()
    fun encode(): String = Json.encodeToString(ListSerializer(String.serializer()), set.toList())

    companion object {
        fun decode(text: String?): FavoritesStore = FavoritesStore(
            runCatching { Json.decodeFromString(ListSerializer(String.serializer()), text.orEmpty()) }.getOrDefault(emptyList()),
        )
    }
}

@Serializable
data class PlayEvent(val path: String, val atMs: Long)

/** Play history, newest first, one entry per file, capped. Used for "Recently played" and "Continue watching". */
class PlayHistory(private val max: Int = 200, initial: List<PlayEvent> = emptyList()) {
    private val list = ArrayList(initial.sortedByDescending { it.atMs }.distinctBy { it.path }.take(max))

    fun record(path: String, atMs: Long) {
        if (path.isBlank()) return
        list.removeAll { it.path == path }
        list.add(0, PlayEvent(path, atMs))
        while (list.size > max) list.removeAt(list.lastIndex)
    }

    fun remove(path: String) { list.removeAll { it.path == path } }
    fun clear() = list.clear()
    fun events(): List<PlayEvent> = list.toList()
    fun encode(): String = CODEC.encodeToString(ListSerializer(PlayEvent.serializer()), list)

    companion object {
        private val CODEC = Json { ignoreUnknownKeys = true }
        fun decode(text: String?, max: Int = 200): PlayHistory = PlayHistory(
            max,
            runCatching { CODEC.decodeFromString(ListSerializer(PlayEvent.serializer()), text.orEmpty()) }.getOrDefault(emptyList()),
        )
    }
}

/** A file you stopped part-way through. [progress] is 0..1. */
data class ContinueItem(val item: LibraryItem, val positionMs: Long, val lastPlayedMs: Long) {
    val progress: Float get() = if (item.durationMs > 0) (positionMs.toFloat() / item.durationMs).coerceIn(0f, 1f) else 0f
    val remainingMs: Long get() = (item.durationMs - positionMs).coerceAtLeast(0)
}

object LibraryHome {
    /**
     * "Continue watching / listening": items with a saved resume point (the player only keeps points
     * after 5 s and before 95 %), most recently played first. Files that are gone are skipped.
     */
    fun continueItems(history: List<PlayEvent>, resume: Map<String, Long>, index: Map<String, LibraryItem>, limit: Int = 20): List<ContinueItem> =
        history.mapNotNull { e ->
            val pos = resume[e.path] ?: resume["file://" + e.path] ?: return@mapNotNull null
            val item = index[e.path] ?: return@mapNotNull null
            ContinueItem(item, pos, e.atMs)
        }.take(limit)

    fun recent(history: List<PlayEvent>, index: Map<String, LibraryItem>, limit: Int = 30): List<LibraryItem> =
        history.mapNotNull { index[it.path] }.take(limit)

    fun favorites(fav: FavoritesStore, index: Map<String, LibraryItem>): List<LibraryItem> = fav.all().mapNotNull { index[it] }

    /** Recently added files (by modified time), newest first. */
    fun recentlyAdded(all: List<LibraryItem>, limit: Int = 30): List<LibraryItem> = all.sortedByDescending { it.modifiedMs }.take(limit)
}

/** Built-in player preferences that are remembered between sessions. */
@Serializable
data class PlaybackPrefs(
    /** Speed applied to every new item (0.25..4). */
    val speed: Float = 1f,
    val rememberSpeed: Boolean = true,
    /** Max video height for adaptive network streams (HLS/DASH); 0 means automatic. Local files always play as they are. */
    val maxVideoHeight: Int = 0,
    val subtitlesOn: Boolean = true,
    val shuffle: Boolean = false,
    /** 0 off, 1 one, 2 all (same numbers as Media3's REPEAT_MODE_*). */
    val repeatMode: Int = 0,
    /** Show the chapter buttons when a file has chapters. */
    val chapterButtons: Boolean = true,
) {
    fun normalized(): PlaybackPrefs = copy(
        speed = speed.coerceIn(0.25f, 4f).takeIf { !it.isNaN() } ?: 1f,
        maxVideoHeight = maxVideoHeight.takeIf { it in QUALITY_CHOICES } ?: 0,
        repeatMode = repeatMode.coerceIn(0, 2),
    )

    companion object {
        /** 0 = Auto. */
        val QUALITY_CHOICES = listOf(0, 2160, 1440, 1080, 720, 480, 360)
        private val CODEC = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun decode(text: String?): PlaybackPrefs =
            runCatching { CODEC.decodeFromString(serializer(), text.orEmpty()) }.getOrDefault(PlaybackPrefs()).normalized()
        fun encode(p: PlaybackPrefs): String = CODEC.encodeToString(serializer(), p.normalized())
        fun qualityLabel(h: Int) = if (h == 0) "Auto" else "${h}p"
    }
}
