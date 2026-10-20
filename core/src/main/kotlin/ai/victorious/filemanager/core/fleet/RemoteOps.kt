package ai.victorious.filemanager.core.fleet

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

enum class OpKind(val wire: String) { READ("read"), ACTION("action"), DESTRUCTIVE("destructive") }

/**
 * One op advertised by this node in `capabilities.get`.
 * [enabled] = capability switched on; a disabled op answers `capability_off`.
 * [handler] is null for placeholders that have no implementation yet.
 */
data class NodeOp(
    val name: String,
    val kind: OpKind,
    val remoteAllowed: Boolean,
    val requiresConfirm: Boolean,
    val enabled: Boolean = true,
    val handler: ((JsonObject) -> JsonObject)? = null,
) {
    init {
        // Destructive ops ALWAYS need on-device confirmation; never configurable off.
        require(kind != OpKind.DESTRUCTIVE || requiresConfirm) { "destructive op $name must require confirmation" }
    }

    fun descriptor(): JsonObject = buildJsonObject {
        put("name", JsonPrimitive(name))
        put("kind", JsonPrimitive(kind.wire))
        put("remoteAllowed", JsonPrimitive(remoteAllowed))
        put("requiresConfirm", JsonPrimitive(requiresConfirm))
    }
}

/** Victorious Keyboard media.* surface. Not implemented in VictorFiles yet → capability_off placeholders. */
object MediaOps {
    const val PLAY_PAUSE = "media.playPause"
    const val NEXT = "media.next"
    const val PREVIOUS = "media.previous"
    const val STATUS_GET = "media.status.get"

    val placeholders: List<NodeOp> = listOf(
        NodeOp(PLAY_PAUSE, OpKind.ACTION, remoteAllowed = true, requiresConfirm = false, enabled = false),
        NodeOp(NEXT, OpKind.ACTION, remoteAllowed = true, requiresConfirm = false, enabled = false),
        NodeOp(PREVIOUS, OpKind.ACTION, remoteAllowed = true, requiresConfirm = false, enabled = false),
        NodeOp(STATUS_GET, OpKind.READ, remoteAllowed = true, requiresConfirm = false, enabled = false),
    )
}

/**
 * Seam for on-device confirmation of destructive / requiresConfirm ops.
 * The default never approves: there is no confirmation UI wired to the
 * remote path yet, and a remote trigger is NEVER auto-approved.
 */
fun interface RemoteConfirmation {
    /** Returns true only if the user explicitly approved on this device. */
    fun confirmedOnDevice(op: NodeOp, env: EnvelopeV1): Boolean

    companion object {
        val NEVER = RemoteConfirmation { _, _ -> false }
    }
}
