package ai.victorious.filemanager.core.fleet

import ai.victorious.filemanager.core.priv.ShizukuState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Frozen STATUS-TRAY model (DroidCommand AI `docs/specs/STATUS_TRAY.md`).
 * VictorFiles answers `node.status.get` with ONE [NodeStatusSnapshot] for itself.
 * A snapshot never contains file paths, file names or file contents.
 */
enum class StatusTrayDomain(val wire: String) {
    MESH("mesh"), VPN("vpn"), BROWSER("browser"), SMS("sms"), NODE_HEALTH("node_health"), OTHER("other");

    companion object {
        fun fromWire(s: String): StatusTrayDomain = entries.firstOrNull { it.wire == s } ?: OTHER
    }
}

enum class StatusTrayState(val wire: String) { OK("ok"), DEGRADED("degraded"), DOWN("down"), UNKNOWN("unknown"), OFF("off") }

data class StatusTrayDomainStatus(val name: StatusTrayDomain, val state: StatusTrayState, val detail: String? = null)

data class NodeStatusSnapshot(
    val nodeId: String,
    val packageName: String?,
    val connectionClass: String,
    val updatedAtMs: Long,
    val domains: List<StatusTrayDomainStatus>,
)

object NodeStatusSnapshotCodec {
    const val CONNECTION_CLASS_EXTERNAL_NODE = "EXTERNAL_NODE"
    const val MAX_DETAIL = 200
    val CONNECTION_CLASSES = setOf("EXTERNAL_NODE", "BOUND_COMPANION", "TERMUX_IPC", "HOST_PROCESS_TOOL")

    /** Spec §6 forbidden-name list (case-insensitive substring match on detail). */
    val FORBIDDEN = listOf(
        "password", "apikey", "api_key", "api_token", "token", "secret", "codeword", "code_word",
        "whitelist", "signingkey", "signing_key", "privatekey", "private_key", "cookie", "keystore",
    )

    /** VictorFiles extra rule: nothing that looks like a path or a file reference. */
    private val PATH_LIKE = Regex("""[/\\]|\b[a-z]:|\.(txt|pdf|jpg|jpeg|png|apk|zip|mp3|mp4|db|json|xml)\b|content:|file:""", RegexOption.IGNORE_CASE)

    /** Returns a reason string if the snapshot may not be sent, else null. */
    fun privacyViolation(s: NodeStatusSnapshot): String? {
        if (s.nodeId.isBlank()) return "nodeId is required"
        if (s.connectionClass !in CONNECTION_CLASSES) return "bad connectionClass"
        for (d in s.domains) {
            val detail = d.detail ?: continue
            if (detail.length > MAX_DETAIL) return "detail longer than $MAX_DETAIL chars"
            val low = detail.lowercase()
            FORBIDDEN.firstOrNull { low.contains(it) }?.let { return "detail contains forbidden term '$it'" }
            if (PATH_LIKE.containsMatchIn(detail)) return "detail looks like a file path or file reference"
        }
        return null
    }

    /** Throws [EnvelopeException] (`invalid_parameters`) instead of silently stripping. */
    fun encode(s: NodeStatusSnapshot): JsonObject {
        privacyViolation(s)?.let { throw EnvelopeException(RemoteError.INVALID_PARAMETERS, "refusing snapshot: $it") }
        return buildJsonObject {
            put("nodeId", JsonPrimitive(s.nodeId))
            put("packageName", JsonPrimitive(s.packageName))
            put("connectionClass", JsonPrimitive(s.connectionClass))
            put("updatedAtMs", JsonPrimitive(s.updatedAtMs))
            put(
                "domains",
                JsonArray(
                    s.domains.map { d ->
                        buildJsonObject {
                            put("name", JsonPrimitive(d.name.wire))
                            put("state", JsonPrimitive(d.state.wire))
                            d.detail?.let { put("detail", JsonPrimitive(it)) }
                        }
                    },
                ),
            )
        }
    }
}

/** Builds VictorFiles' own snapshot: node_health + an `other` row for Shizuku. */
object FileManagerStatus {
    fun shizukuDomain(state: ShizukuState?): StatusTrayDomainStatus = when (state) {
        ShizukuState.GRANTED -> StatusTrayDomainStatus(StatusTrayDomain.OTHER, StatusTrayState.OK, "shizuku: connected")
        ShizukuState.DENIED -> StatusTrayDomainStatus(StatusTrayDomain.OTHER, StatusTrayState.DEGRADED, "shizuku: permission needed")
        ShizukuState.NOT_RUNNING -> StatusTrayDomainStatus(StatusTrayDomain.OTHER, StatusTrayState.DOWN, "shizuku: not running")
        ShizukuState.NOT_INSTALLED -> StatusTrayDomainStatus(StatusTrayDomain.OTHER, StatusTrayState.OFF, "shizuku: not installed")
        null -> StatusTrayDomainStatus(StatusTrayDomain.OTHER, StatusTrayState.UNKNOWN, "shizuku: unknown")
    }

    fun snapshot(nodeId: String, packageName: String, nowMs: Long, shizuku: ShizukuState?, externalControl: Boolean) =
        NodeStatusSnapshot(
            nodeId = nodeId,
            packageName = packageName,
            connectionClass = NodeStatusSnapshotCodec.CONNECTION_CLASS_EXTERNAL_NODE,
            updatedAtMs = nowMs,
            domains = listOf(
                StatusTrayDomainStatus(
                    StatusTrayDomain.NODE_HEALTH,
                    StatusTrayState.OK,
                    "alive; external control ${if (externalControl) "on" else "off"}",
                ),
                shizukuDomain(shizuku),
            ),
        )
}
