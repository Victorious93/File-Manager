package ai.victorious.filemanager.core.media.pro

import ai.victorious.filemanager.core.fleet.EnvelopeException
import ai.victorious.filemanager.core.fleet.NodeOp
import ai.victorious.filemanager.core.fleet.OpKind
import ai.victorious.filemanager.core.fleet.RemoteError
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull

/** Snapshot of the Media Pro session for `media.state`. Never includes local file paths. */
data class MediaState(
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val title: String,
    /** `local` | `http` | `rtsp` | `smb` | `content` — the source *kind*, never a path or URL. */
    val source: String,
)

/** Controls the live player. Every call returns false / null when there is no session. */
interface MediaRemote {
    fun state(): MediaState?
    fun play(): Boolean
    fun pause(): Boolean
    fun toggle(): Boolean
    fun next(): Boolean
    fun prev(): Boolean
    fun seekTo(positionMs: Long): Boolean
    fun seekBy(deltaMs: Long): Boolean
    /** Player volume 0..100. App may also map this to STREAM_MUSIC when the user enables that option. */
    fun setVolume(level: Int): Boolean
}

/**
 * `media.*` bridge ops for Victorious Keyboard (pinned debug package, media.* scope).
 * Idle (no player / empty queue) -> `no_session`.
 */
object MediaBridgeOps {
    const val PLAY = "media.play"
    const val PAUSE = "media.pause"
    const val TOGGLE = "media.toggle"
    const val NEXT = "media.next"
    const val PREV = "media.prev"
    const val SEEK = "media.seek"
    const val SEEK_BY = "media.seekBy"
    const val VOLUME = "media.volume"
    const val STATE = "media.state"

    /** Media-specific error (extension to the frozen category set): nothing is loaded. */
    const val NO_SESSION = "no_session"

    val NAMES = listOf(PLAY, PAUSE, TOGGLE, NEXT, PREV, SEEK, SEEK_BY, VOLUME, STATE)

    private fun kind(name: String) = if (name == STATE) OpKind.READ else OpKind.ACTION

    /** Placeholders (no player available in this build/process) -> `capability_off`. */
    fun placeholders(): List<NodeOp> = NAMES.map { NodeOp(it, kind(it), remoteAllowed = true, requiresConfirm = false, enabled = false) }

    fun ops(remote: MediaRemote): List<NodeOp> = NAMES.map { name ->
        NodeOp(name, kind(name), remoteAllowed = true, requiresConfirm = false) { args -> run(remote, name, args) }
    }

    private fun noSession(): Nothing = throw EnvelopeException(NO_SESSION, "no media session (nothing is playing or loaded)")
    private fun bad(m: String): Nothing = throw EnvelopeException(RemoteError.INVALID_PARAMETERS, m)

    private fun long(args: JsonObject, k: String): Long =
        (args[k] as? JsonPrimitive)?.let { it.longOrNull ?: it.content.toLongOrNull() } ?: bad("$k (integer) is required")

    fun run(remote: MediaRemote, name: String, args: JsonObject): JsonObject {
        val ok = when (name) {
            STATE -> return stateJson(remote.state() ?: noSession())
            PLAY -> remote.play()
            PAUSE -> remote.pause()
            TOGGLE -> remote.toggle()
            NEXT -> remote.next()
            PREV -> remote.prev()
            SEEK -> {
                val p = long(args, "positionMs")
                if (p < 0) bad("positionMs must be >= 0")
                remote.seekTo(p)
            }
            SEEK_BY -> remote.seekBy(long(args, "deltaMs"))
            VOLUME -> {
                val l = long(args, "level")
                if (l !in 0..100) bad("level must be 0..100")
                remote.setVolume(l.toInt())
            }
            else -> bad("unknown media op $name")
        }
        if (!ok) noSession()
        return stateJson(remote.state() ?: noSession())
    }

    fun stateJson(s: MediaState): JsonObject = buildJsonObject {
        put("playing", JsonPrimitive(s.playing))
        put("positionMs", JsonPrimitive(s.positionMs))
        put("durationMs", JsonPrimitive(s.durationMs))
        put("title", JsonPrimitive(s.title))
        put("source", JsonPrimitive(s.source))
    }
}
