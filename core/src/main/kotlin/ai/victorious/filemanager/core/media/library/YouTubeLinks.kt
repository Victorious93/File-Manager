package ai.victorious.filemanager.core.media.library

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URLDecoder

/**
 * Recognises YouTube and YouTube Music links the user already has (pasted, shared, or saved in a
 * .url/.webloc/.txt file) so the app can hand them to the OFFICIAL YouTube / YouTube Music app, or
 * to the browser. This file never downloads, streams, scrapes or rewrites anything from YouTube;
 * it only cleans the link (drops tracking parameters) and picks which official app should open it.
 */
object YouTubeLinks {
    const val YOUTUBE_PACKAGE = "com.google.android.youtube"
    const val MUSIC_PACKAGE = "com.google.android.apps.youtube.music"

    enum class Kind(val label: String) {
        VIDEO("Video"), SHORT("Short"), PLAYLIST("Playlist"), CHANNEL("Channel"),
        MUSIC_TRACK("YouTube Music song"), MUSIC_PLAYLIST("YouTube Music playlist"), MUSIC_BROWSE("YouTube Music page"),
    }

    data class Link(val kind: Kind, val id: String, val url: String, val startSeconds: Int = 0) {
        val isMusic: Boolean get() = kind == Kind.MUSIC_TRACK || kind == Kind.MUSIC_PLAYLIST || kind == Kind.MUSIC_BROWSE
        /** Official app to try first. */
        val preferredPackage: String get() = if (isMusic) MUSIC_PACKAGE else YOUTUBE_PACKAGE
        /** Official app to try if the first one is missing (a music song also plays in YouTube). */
        val fallbackPackage: String? get() = when (kind) {
            Kind.MUSIC_TRACK, Kind.MUSIC_PLAYLIST -> YOUTUBE_PACKAGE
            else -> null
        }
        /** The same content as a normal youtube.com link, for the YouTube-app fallback. */
        val youtubeUrl: String get() = when (kind) {
            Kind.MUSIC_TRACK -> "https://www.youtube.com/watch?v=$id"
            Kind.MUSIC_PLAYLIST -> "https://www.youtube.com/playlist?list=$id"
            else -> url
        }
    }

    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val LIST_ID = Regex("^[A-Za-z0-9_-]{2,64}$")
    private val HANDLE = Regex("^@[A-Za-z0-9._-]{1,100}$")
    private val NAME = Regex("^[A-Za-z0-9._-]{1,100}$")
    private val YT_HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "youtube-nocookie.com", "www.youtube-nocookie.com")
    private const val SHORT_HOST = "youtu.be"
    private const val MUSIC_HOST = "music.youtube.com"
    private val URL_IN_TEXT = Regex("""(?i)\b((?:https?://)?(?:(?:www\.|m\.|music\.)?youtube(?:-nocookie)?\.com|youtu\.be)/[^\s"'<>()\[\]{}]*)""")

    fun isYouTubeHost(host: String?): Boolean {
        val h = host?.lowercase()?.trimEnd('.') ?: return false
        return h in YT_HOSTS || h == SHORT_HOST || h == MUSIC_HOST
    }

    /** Parses one link, or null when it is not a YouTube / YouTube Music link we understand. */
    fun parse(raw: String): Link? {
        var text = raw.trim().trimEnd('.', ',', ';', ')')
        if (text.isEmpty() || text.length > 2048) return null
        if (!text.contains("://")) text = "https://$text"
        val uri = runCatching { URI(text) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return null
        if (uri.rawUserInfo != null) return null
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return null
        if (!isYouTubeHost(host)) return null
        val segs = (uri.rawPath ?: "").split('/').filter { it.isNotEmpty() }.map { decode(it) }
        val q = query(uri.rawQuery)
        val t = parseTime(q["t"] ?: q["start"])
        return when (host) {
            SHORT_HOST -> segs.firstOrNull()?.takeIf { VIDEO_ID.matches(it) }?.let { video(it, t, q["list"]) }
            MUSIC_HOST -> when (segs.firstOrNull()) {
                "watch" -> q["v"]?.takeIf { VIDEO_ID.matches(it) }?.let { Link(Kind.MUSIC_TRACK, it, "https://music.youtube.com/watch?v=$it" + listSuffix(q["list"]), t) }
                "playlist" -> q["list"]?.takeIf { LIST_ID.matches(it) }?.let { Link(Kind.MUSIC_PLAYLIST, it, "https://music.youtube.com/playlist?list=$it") }
                "browse", "channel" -> segs.getOrNull(1)?.takeIf { NAME.matches(it) }?.let { Link(Kind.MUSIC_BROWSE, it, "https://music.youtube.com/${segs[0]}/$it") }
                null -> Link(Kind.MUSIC_BROWSE, "", "https://music.youtube.com/")
                else -> null
            }
            else -> when (val first = segs.firstOrNull()) {
                "watch" -> q["v"]?.takeIf { VIDEO_ID.matches(it) }?.let { video(it, t, q["list"]) }
                "shorts" -> segs.getOrNull(1)?.takeIf { VIDEO_ID.matches(it) }?.let { Link(Kind.SHORT, it, "https://www.youtube.com/shorts/$it") }
                "embed", "v", "live", "e" -> segs.getOrNull(1)?.takeIf { VIDEO_ID.matches(it) }?.let { video(it, t, null) }
                "playlist" -> q["list"]?.takeIf { LIST_ID.matches(it) }?.let { Link(Kind.PLAYLIST, it, "https://www.youtube.com/playlist?list=$it") }
                "channel", "c", "user" -> segs.getOrNull(1)?.takeIf { NAME.matches(it) }?.let { Link(Kind.CHANNEL, it, "https://www.youtube.com/$first/$it") }
                null -> null
                else -> first.takeIf { HANDLE.matches(it) }?.let { Link(Kind.CHANNEL, it, "https://www.youtube.com/$it") }
            }
        }
    }

    /** Every distinct YouTube / YouTube Music link found in [text] (a share, a note, a .url or .webloc file). */
    fun extract(text: String, max: Int = 200): List<Link> =
        URL_IN_TEXT.findAll(text).mapNotNull { parse(it.groupValues[1]) }.distinctBy { it.url }.take(max).toList()

    private fun video(id: String, t: Int, list: String?) =
        Link(Kind.VIDEO, id, "https://www.youtube.com/watch?v=$id" + listSuffix(list) + (if (t > 0) "&t=${t}s" else ""), t)

    private fun listSuffix(list: String?) = list?.takeIf { LIST_ID.matches(it) }?.let { "&list=$it" } ?: ""

    /** "90", "90s", "1m30s", "1h2m3s" -> seconds; anything else -> 0. */
    fun parseTime(v: String?): Int {
        val s = v?.trim()?.lowercase() ?: return 0
        s.toIntOrNull()?.let { return it.coerceAtLeast(0) }
        val m = Regex("^(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?$").matchEntire(s) ?: return 0
        if (m.value.isEmpty()) return 0
        val (h, mi, se) = m.destructured
        return (h.toIntOrNull() ?: 0) * 3600 + (mi.toIntOrNull() ?: 0) * 60 + (se.toIntOrNull() ?: 0)
    }

    private fun query(raw: String?): Map<String, String> = raw.orEmpty().split('&').mapNotNull {
        val k = it.substringBefore('=')
        if (k.isEmpty() || !it.contains('=')) null else decode(k) to decode(it.substringAfter('='))
    }.toMap()

    private fun decode(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}

/** A YouTube / YouTube Music link the user saved in the library. Only the link and a label are stored. */
@Serializable
data class SavedLink(val url: String, val label: String, val addedMs: Long)

object SavedLinksCodec {
    private val json = Json { ignoreUnknownKeys = true }
    fun encode(list: List<SavedLink>): String = json.encodeToString(ListSerializer(SavedLink.serializer()), list)
    fun decode(text: String?): List<SavedLink> =
        runCatching { json.decodeFromString(ListSerializer(SavedLink.serializer()), text.orEmpty()) }.getOrDefault(emptyList())

    /** Adds the cleaned link (newest first, no duplicates). Returns null when [raw] is not a YouTube link. */
    fun add(list: List<SavedLink>, raw: String, label: String?, nowMs: Long): List<SavedLink>? {
        val link = YouTubeLinks.parse(raw) ?: return null
        val name = label?.trim().takeUnless { it.isNullOrEmpty() } ?: "${link.kind.label} ${link.id}".trim()
        return listOf(SavedLink(link.url, name.take(120), nowMs)) + list.filter { it.url != link.url }
    }
}
