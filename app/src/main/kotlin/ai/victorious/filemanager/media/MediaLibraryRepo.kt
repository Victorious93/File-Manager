package ai.victorious.filemanager.media

import ai.victorious.filemanager.core.media.library.Chapter
import ai.victorious.filemanager.core.media.library.ChapterParsers
import ai.victorious.filemanager.core.media.library.FavoritesStore
import ai.victorious.filemanager.core.media.library.LibraryItem
import ai.victorious.filemanager.core.media.library.LibraryText
import ai.victorious.filemanager.core.media.library.LyricLine
import ai.victorious.filemanager.core.media.library.Lyrics
import ai.victorious.filemanager.core.media.library.MediaExtensions
import ai.victorious.filemanager.core.media.library.PlayHistory
import ai.victorious.filemanager.core.media.library.PlaybackPrefs
import ai.victorious.filemanager.core.media.library.SavedLink
import ai.victorious.filemanager.core.media.library.SavedLinksCodec
import ai.victorious.filemanager.core.media.library.YouTubeLinks
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.Executors

/**
 * Storage for the local media library: favourites, play history, saved YouTube links and remembered
 * player settings live in app-private SharedPreferences. The index itself comes from Android's
 * MediaStore (tags, durations), with a file walk over the usual media folders as a fallback.
 * Nothing is uploaded and nothing is fetched from the network.
 */
object MediaLibraryRepo {
    private const val PREFS = "media_library"
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "media-library").apply { isDaemon = true } }

    /** Chapters and lyrics found for the item that is playing now. */
    data class Extras(val mediaId: String, val chapters: List<Chapter>, val lyrics: List<LyricLine>)

    private val _extras = MutableStateFlow<Extras?>(null)
    val extras: StateFlow<Extras?> = _extras

    private val _version = MutableStateFlow(0)
    /** Bumps when favourites, history or saved links change, so open screens refresh. */
    val version: StateFlow<Int> = _version

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- favourites / history / links / player settings ----
    fun favorites(ctx: Context): FavoritesStore = FavoritesStore.decode(prefs(ctx).getString("favorites", null))
    fun toggleFavorite(ctx: Context, path: String): Boolean {
        val f = favorites(ctx); val on = f.toggle(path)
        prefs(ctx).edit().putString("favorites", f.encode()).apply(); _version.value++
        return on
    }

    fun history(ctx: Context): PlayHistory = PlayHistory.decode(prefs(ctx).getString("history", null))
    fun recordPlay(ctx: Context, mediaId: String) {
        if (!mediaId.startsWith("/")) return // local files only; stream URLs are never kept
        val h = history(ctx); h.record(mediaId, System.currentTimeMillis())
        prefs(ctx).edit().putString("history", h.encode()).apply(); _version.value++
    }
    fun clearHistory(ctx: Context) { prefs(ctx).edit().remove("history").apply(); _version.value++ }

    fun links(ctx: Context): List<SavedLink> = SavedLinksCodec.decode(prefs(ctx).getString("yt_links", null))
    /** Returns false when [raw] is not a YouTube / YouTube Music link. */
    fun addLink(ctx: Context, raw: String, label: String?): Boolean {
        val next = SavedLinksCodec.add(links(ctx), raw, label, System.currentTimeMillis()) ?: return false
        prefs(ctx).edit().putString("yt_links", SavedLinksCodec.encode(next)).apply(); _version.value++
        return true
    }
    fun removeLink(ctx: Context, url: String) {
        prefs(ctx).edit().putString("yt_links", SavedLinksCodec.encode(links(ctx).filter { it.url != url })).apply(); _version.value++
    }

    fun playbackPrefs(ctx: Context): PlaybackPrefs = PlaybackPrefs.decode(prefs(ctx).getString("playback", null))
    fun savePlaybackPrefs(ctx: Context, p: PlaybackPrefs) { prefs(ctx).edit().putString("playback", PlaybackPrefs.encode(p)).apply() }

    // ---- chapters + lyrics for the playing item (off the main thread) ----
    fun loadExtras(mediaId: String) {
        if (!mediaId.startsWith("/")) { _extras.value = Extras(mediaId, emptyList(), emptyList()); return }
        io.execute {
            val f = File(mediaId)
            val chapters = runCatching { ChapterParsers.forFile(f) }.getOrDefault(emptyList())
            val lyrics = if (MediaExtensions.kind(f.name) == false) runCatching { Lyrics.forFile(f) }.getOrDefault(emptyList()) else emptyList()
            _extras.value = Extras(mediaId, chapters, lyrics)
        }
    }

    // ---- scanning ----
    /** Every local audio/video file Android has indexed; falls back to walking Music, Movies, Download, DCIM and Podcasts. Call off the main thread. */
    fun scan(ctx: Context): List<LibraryItem> {
        val fromStore = runCatching { queryMediaStore(ctx) }.getOrDefault(emptyList())
        return fromStore.ifEmpty { walkFallback() }
    }

    private fun queryMediaStore(ctx: Context): List<LibraryItem> {
        val out = ArrayList<LibraryItem>()
        @Suppress("DEPRECATION") val dataCol = MediaStore.MediaColumns.DATA
        val audio = arrayOf(dataCol, MediaStore.MediaColumns.TITLE, MediaStore.Audio.AudioColumns.ARTIST, MediaStore.Audio.AudioColumns.ALBUM,
            MediaStore.MediaColumns.DURATION, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.Audio.AudioColumns.TRACK)
        ctx.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, audio, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val path = c.getString(0) ?: continue
                val guess = LibraryText.guessFromFileName(path.substringAfterLast('/'))
                val artist = c.getString(2)?.takeUnless { it == "<unknown>" }.orEmpty().ifBlank { guess.first }
                out += LibraryItem(
                    path = path,
                    title = c.getString(1)?.takeIf { it.isNotBlank() } ?: guess.second,
                    artist = artist,
                    album = c.getString(3)?.takeUnless { it == "<unknown>" }.orEmpty(),
                    durationMs = c.getLong(4), isVideo = false, sizeBytes = c.getLong(5), modifiedMs = c.getLong(6) * 1000,
                    trackNumber = (c.getInt(7) % 1000).takeIf { it > 0 } ?: guess.third,
                )
            }
        }
        val video = arrayOf(dataCol, MediaStore.MediaColumns.TITLE, MediaStore.MediaColumns.DURATION, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED)
        ctx.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, video, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val path = c.getString(0) ?: continue
                out += LibraryItem(path, c.getString(1)?.takeIf { it.isNotBlank() } ?: path.substringAfterLast('/').substringBeforeLast('.'),
                    durationMs = c.getLong(2), isVideo = true, sizeBytes = c.getLong(3), modifiedMs = c.getLong(4) * 1000)
            }
        }
        return out.filter { File(it.path).isFile }
    }

    private fun walkFallback(): List<LibraryItem> {
        val root = Environment.getExternalStorageDirectory()
        val dirs = listOf("Music", "Movies", "Download", "DCIM", "Podcasts", "Audiobooks").map { File(root, it) }
        val out = ArrayList<LibraryItem>()
        for (d in dirs) {
            d.walkTopDown().maxDepth(6).onEnter { !it.name.startsWith(".") && !File(it, ".nomedia").exists() }
                .filter { it.isFile }.forEach { f ->
                    val video = MediaExtensions.kind(f.name) ?: return@forEach
                    val (artist, title, track) = LibraryText.guessFromFileName(f.name)
                    out += LibraryItem(f.absolutePath, title, artist, f.parentFile?.name.orEmpty().takeIf { !video }.orEmpty(),
                        0, video, f.length(), f.lastModified(), track)
                    if (out.size >= 20_000) return out
                }
        }
        return out
    }

    // ---- YouTube / YouTube Music: hand the link to the official app (or the browser) ----
    /** Opens [link] in the official YouTube Music / YouTube app, falling back to the browser. Returns what opened it. */
    fun openOfficial(ctx: Context, link: YouTubeLinks.Link): String {
        fun tryOpen(url: String, pkg: String?): Boolean = try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
            if (pkg != null) i.setPackage(pkg)
            if (ctx !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i); true
        } catch (e: ActivityNotFoundException) { false }
        return when {
            tryOpen(link.url, link.preferredPackage) -> if (link.isMusic) "YouTube Music" else "YouTube"
            link.fallbackPackage != null && tryOpen(link.youtubeUrl, link.fallbackPackage) -> "YouTube"
            tryOpen(link.url, null) -> "browser"
            else -> error("No app can open this link")
        }
    }

    fun isInstalled(ctx: Context, pkg: String): Boolean =
        runCatching { ctx.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)
}
