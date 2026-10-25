package ai.victorious.filemanager.core.media.pro

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** One playable item. [uri] may be a file path, content://, http(s)://, rtsp:// or smb://. */
@Serializable
data class MediaItemRef(val id: String, val uri: String, val title: String, val durationMs: Long = 0)

@Serializable
data class Playlist(val id: String, val name: String, val items: List<MediaItemRef>)

/** Playlists persisted as JSON text (the app stores it in filesDir). */
object PlaylistCodec {
    private val json = Json { ignoreUnknownKeys = true }
    fun encode(lists: List<Playlist>): String = json.encodeToString(ListSerializer(Playlist.serializer()), lists)
    fun decode(text: String): List<Playlist> =
        if (text.isBlank()) emptyList() else runCatching { json.decodeFromString(ListSerializer(Playlist.serializer()), text) }.getOrDefault(emptyList())
}

/**
 * Resume positions keyed by media key. Not saved in the first 5 s or the last 5 % (finished);
 * LRU-capped.
 */
class ResumeStore(private val maxEntries: Int = 500) {
    private val map = LinkedHashMap<String, Long>(16, 0.75f, true)

    fun save(key: String, positionMs: Long, durationMs: Long) {
        if (positionMs < MIN_MS || (durationMs > 0 && positionMs >= durationMs * 95 / 100)) { map.remove(key); return }
        map[key] = positionMs
        while (map.size > maxEntries) map.remove(map.keys.first())
    }

    fun resumeAt(key: String): Long? = map[key]
    fun clear(key: String) { map.remove(key) }
    fun snapshot(): Map<String, Long> = LinkedHashMap(map)
    fun load(entries: Map<String, Long>) { map.clear(); map.putAll(entries) }

    companion object { const val MIN_MS = 5_000L }
}

/** Sleep timer: fixed duration, or stop at end of the current track. */
class SleepTimer(private val clock: () -> Long) {
    var deadlineMs: Long? = null
        private set
    var endOfTrack: Boolean = false
        private set

    fun start(durationMs: Long) { require(durationMs > 0); deadlineMs = clock() + durationMs; endOfTrack = false }
    fun startEndOfTrack() { endOfTrack = true; deadlineMs = null }
    fun cancel() { deadlineMs = null; endOfTrack = false }
    val active get() = deadlineMs != null || endOfTrack
    fun remainingMs(): Long? = deadlineMs?.let { (it - clock()).coerceAtLeast(0) }

    /** True when playback should pause now. A fired timer resets itself. */
    fun shouldStop(trackEnded: Boolean = false): Boolean {
        val fire = (deadlineMs?.let { clock() >= it } ?: false) || (endOfTrack && trackEnded)
        if (fire) cancel()
        return fire
    }
}

enum class StreamKind { LOCAL, CONTENT, HTTP, RTSP, SMB }

object StreamSupport {
    fun kind(uri: String): StreamKind? {
        val low = uri.trim().lowercase()
        return when {
            low.startsWith("http://") || low.startsWith("https://") -> StreamKind.HTTP
            low.startsWith("rtsp://") || low.startsWith("rtsps://") -> StreamKind.RTSP
            low.startsWith("smb://") -> StreamKind.SMB
            low.startsWith("content://") -> StreamKind.CONTENT
            low.startsWith("file://") || low.startsWith("/") -> StreamKind.LOCAL
            else -> null
        }
    }

    /** Source label for `media.state` (never the URL/path). */
    fun label(uri: String): String = kind(uri)?.name?.lowercase() ?: "unknown"

    data class SmbTarget(val host: String, val share: String, val path: String, val user: String?, val password: String?, val domain: String?)

    /** `smb://[domain;][user[:pass]@]host/share/path/to/file`. */
    fun parseSmb(uri: String): SmbTarget? {
        if (!uri.lowercase().startsWith("smb://")) return null
        val rest = uri.substring(6)
        val at = rest.lastIndexOf('@', rest.indexOf('/').let { if (it < 0) rest.length else it })
        val (auth, hostPath) = if (at >= 0) rest.substring(0, at) to rest.substring(at + 1) else null to rest
        val parts = hostPath.split('/').filter { it.isNotEmpty() }
        if (parts.size < 3) return null
        var domain: String? = null
        var user: String? = null
        var pass: String? = null
        if (auth != null) {
            val dec = java.net.URLDecoder.decode(auth, "UTF-8")
            val (du, p) = dec.split(':', limit = 2).let { it[0] to it.getOrNull(1) }
            pass = p
            if (du.contains(';')) { domain = du.substringBefore(';'); user = du.substringAfter(';') } else user = du
        }
        return SmbTarget(parts[0], parts[1], parts.drop(2).joinToString("\\") { java.net.URLDecoder.decode(it, "UTF-8") }, user, pass, domain)
    }

    /** Display-safe URI: strips user info (credentials) for titles/logs. */
    fun redact(uri: String): String = uri.replace(Regex("^([a-zA-Z][a-zA-Z0-9+.-]*://)[^/@]*@"), "$1")
}

object SubtitleSupport {
    /** Extension -> Media3 MIME type. */
    val MIME = mapOf(
        "srt" to "application/x-subrip",
        "ass" to "text/x-ssa",
        "ssa" to "text/x-ssa",
        "vtt" to "text/vtt",
        "ttml" to "application/ttml+xml",
    )

    fun mimeFor(name: String): String? = MIME[name.substringAfterLast('.', "").lowercase()]

    /** Sidecar subtitles next to a video: `movie.srt`, `movie.en.srt`, `movie.eng.ass`, … */
    fun sidecars(videoName: String, siblings: List<String>): List<String> {
        val base = videoName.substringBeforeLast('.').lowercase()
        return siblings.filter { s ->
            val low = s.lowercase()
            mimeFor(low) != null && (low.substringBeforeLast('.') == base || low.startsWith("$base."))
        }.sorted()
    }

    /** Language tag from `movie.en.srt` -> `en`. */
    fun language(subName: String): String? {
        val parts = subName.split('.')
        return if (parts.size >= 3) parts[parts.size - 2].takeIf { it.length in 2..3 && it.all(Char::isLetter) }?.lowercase() else null
    }
}

/** Swipe gestures: horizontal = seek, vertical left = brightness, vertical right = volume. */
object GestureMath {
    /** Full-width swipe = 90 s, clamped to the media. */
    fun seekTarget(positionMs: Long, durationMs: Long, dxFraction: Float): Long {
        val t = positionMs + (dxFraction * 90_000).toLong()
        return if (durationMs > 0) t.coerceIn(0, durationMs) else t.coerceAtLeast(0)
    }

    /** Full-height swipe up = +1.0 (dyFraction negative when moving up). */
    fun level(current: Float, dyFraction: Float): Float = (current - dyFraction).coerceIn(0f, 1f)

    fun isLeftHalf(x: Float, width: Float) = x < width / 2
}

object PlaybackSpeed {
    val PRESETS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f, 4f)
    fun clamp(s: Float) = s.coerceIn(0.25f, 4f)
}

/** Equalizer band levels (millibels), clamped to the device's reported range. */
data class EqualizerSettings(val enabled: Boolean, val bandLevels: List<Int>) {
    fun clampedTo(min: Int, max: Int, bands: Int): EqualizerSettings =
        copy(bandLevels = List(bands) { (bandLevels.getOrNull(it) ?: 0).coerceIn(min, max) })
}
