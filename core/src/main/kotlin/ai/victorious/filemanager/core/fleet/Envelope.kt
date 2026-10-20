package ai.victorious.filemanager.core.fleet

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import java.util.Base64

/**
 * Frozen fleet remote-control envelope v1 (DroidCommand AI
 * `docs/specs/REMOTE_CONTROL.md` + `docs/specs/STATUS_TRAY.md`).
 *
 * Outer framing stays [BridgeRequest] (snake_case). The envelope fields are
 * flat camelCase keys at the top of `parameters`. `requestId` IS the outer
 * `request_id` and is not repeated. Nothing in here is ever used to
 * authorize a caller: identity is package + signing cert only.
 */
object EnvelopeOp {
    const val CAPABILITIES_GET = "capabilities.get"
    const val REMOTE_COMMAND = "remote.command"
    const val MACRO_STEP = "macro.step"
    const val MACRO_RUN = "macro.run"
    const val NODE_STATUS_GET = "node.status.get"

    val ALL: Set<String> = setOf(CAPABILITIES_GET, REMOTE_COMMAND, MACRO_STEP, MACRO_RUN, NODE_STATUS_GET)

    /** Standalone top-level ops routed to the v1 executor (envelope ops + UNIFIED_BACKUP config ops). */
    val ROUTED: Set<String> = ALL + setOf("config.export", "config.import")

    /** Ops that wrap other ops; they may not be nested inside remote.command / macros. */
    val META: Set<String> = setOf(REMOTE_COMMAND, MACRO_STEP, MACRO_RUN)
}

/** Frozen `RemoteErrorCategory` wire names. */
object RemoteError {
    const val INVALID_PARAMETERS = "invalid_parameters"
    const val NOT_PAIRED = "not_paired"
    const val NOT_FOUND = "not_found"
    const val INTERNAL = "internal"
    const val UNAUTHORIZED = "unauthorized"
    const val REPLAY_DETECTED = "replay_detected"
    const val EXPIRED = "expired"
    const val EXTERNAL_CONTROL_DISABLED = "external_control_disabled"
    const val CONFIRMATION_REQUIRED = "confirmation_required"
    const val UNSUPPORTED_OP = "unsupported_op"
    const val CAPABILITY_OFF = "capability_off"

    val ALL = setOf(
        INVALID_PARAMETERS, NOT_PAIRED, NOT_FOUND, INTERNAL, UNAUTHORIZED, REPLAY_DETECTED,
        EXPIRED, EXTERNAL_CONTROL_DISABLED, CONFIRMATION_REQUIRED, UNSUPPORTED_OP, CAPABILITY_OFF,
    )
}

object EnvelopeLimits {
    const val DEFAULT_TTL_MS = 120_000L
    const val MAX_TTL_MS = 300_000L
    const val MAX_SKEW_MS = 120_000L
    /** Seen-nonce retention floor (spec: at least 10 minutes). */
    const val REPLAY_RETENTION_MS = 600_000L
    const val MAX_MACRO_STEPS = 32
}

enum class OnError(val wire: String) {
    ABORT("abort"), CONTINUE("continue");

    companion object {
        fun parse(s: String?): OnError? = when (s) {
            null -> ABORT
            "abort" -> ABORT
            "continue" -> CONTINUE
            else -> null
        }
    }
}

data class MacroStepSpec(
    val op: String,
    val params: JsonObject,
    val onError: OnError,
    val timeoutMs: Long?,
)

/** Parsed v1 envelope (fields at the top of BridgeRequest.parameters). */
data class EnvelopeV1(
    val op: String,
    val requestId: String,
    val targetNodeRef: String,
    val actionRef: String,
    val params: JsonObject,
    val nonce: String,
    val timestampMs: Long,
    val ttlMs: Long,
    /** ADVISORY ONLY. Never used for gating; ops are classified independently. */
    val destructive: Boolean,
    /** Informational only (e.g. VictorSMS sourceNumber / matchedKeyword). Never used for auth. */
    val trigger: JsonObject,
    val macroId: String? = null,
    val stepIndex: Int? = null,
    val onError: OnError = OnError.ABORT,
    val steps: List<MacroStepSpec> = emptyList(),
    val dryRun: Boolean = false,
)

class EnvelopeException(val category: String, message: String) : Exception(message)

object EnvelopeCodec {
    private val HEX_128 = Regex("^[0-9a-fA-F]{32}$")

    /** 128-bit nonce: 32 hex chars, or base64/base64url that decodes to exactly 16 bytes. */
    fun isValidNonce(nonce: String): Boolean {
        if (HEX_128.matches(nonce)) return true
        val decoders = listOf(Base64.getDecoder(), Base64.getUrlDecoder())
        return decoders.any { d -> runCatching { d.decode(nonce).size == 16 }.getOrDefault(false) }
    }

    fun parse(op: String, requestId: String, p: JsonObject): EnvelopeV1 {
        val nonce = p.string("nonce") ?: bad("nonce is required (128-bit, 32 hex chars)")
        if (!isValidNonce(nonce)) bad("nonce must be 128-bit (32 hex chars or base64 of 16 bytes)")
        val ts = p.long("timestampMs") ?: bad("timestampMs (epoch ms) is required")
        val ttl = if (p.containsKey("ttlMs") && p["ttlMs"] !is JsonNull) {
            p.long("ttlMs") ?: bad("ttlMs must be a number")
        } else {
            EnvelopeLimits.DEFAULT_TTL_MS
        }
        if (ttl <= 0 || ttl > EnvelopeLimits.MAX_TTL_MS) bad("ttlMs must be in 1..${EnvelopeLimits.MAX_TTL_MS}")
        val destructive = p.bool("destructive") ?: false
        val args = p.obj("args") ?: p.obj("params") ?: JsonObject(emptyMap())
        val trigger = p.obj("trigger") ?: JsonObject(emptyMap())
        val base = EnvelopeV1(
            op = op,
            requestId = requestId,
            targetNodeRef = p.string("targetNodeRef").orEmpty(),
            actionRef = p.string("actionRef").orEmpty(),
            params = args,
            nonce = nonce,
            timestampMs = ts,
            ttlMs = ttl,
            destructive = destructive,
            trigger = trigger,
            dryRun = p.bool("dryRun") ?: (p["dryRun"] as? JsonPrimitive)?.content.equals("true", ignoreCase = true),
        )
        return when (op) {
            EnvelopeOp.REMOTE_COMMAND -> {
                if (base.actionRef.isBlank()) bad("remote.command requires actionRef")
                base
            }
            EnvelopeOp.MACRO_STEP -> {
                val stepOp = p.string("op") ?: base.actionRef.ifBlank { null } ?: bad("macro.step requires op")
                val idx = p["stepIndex"]?.let { (it as? JsonPrimitive)?.longOrNull?.toInt() ?: bad("stepIndex must be an integer") }
                if (idx != null && idx < 0) bad("stepIndex must be >= 0")
                val onError = OnError.parse(p.string("onError")) ?: bad("onError must be abort|continue")
                base.copy(actionRef = stepOp, macroId = p.string("macroId"), stepIndex = idx, onError = onError)
            }
            EnvelopeOp.MACRO_RUN -> {
                val raw = p["steps"] as? JsonArray ?: bad("macro.run requires steps[]")
                if (raw.isEmpty()) bad("macro.run steps[] is empty")
                if (raw.size > EnvelopeLimits.MAX_MACRO_STEPS) bad("macro.run allows at most ${EnvelopeLimits.MAX_MACRO_STEPS} steps")
                val steps = raw.mapIndexed { i, el ->
                    val o = el as? JsonObject ?: bad("steps[$i] must be an object")
                    val sop = o.string("op") ?: bad("steps[$i].op is required")
                    val onErr = OnError.parse(o.string("onError")) ?: bad("steps[$i].onError must be abort|continue")
                    MacroStepSpec(sop, o.obj("params") ?: o.obj("args") ?: JsonObject(emptyMap()), onErr, o.long("timeoutMs"))
                }
                base.copy(macroId = p.string("macroId"), steps = steps, dryRun = base.dryRun)
            }
            else -> base
        }
    }

    private fun bad(msg: String): Nothing = throw EnvelopeException(RemoteError.INVALID_PARAMETERS, msg)

    private fun JsonObject.string(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
    private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
    private fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject

    @Suppress("unused")
    private fun JsonElement?.isAbsent() = this == null || this is JsonNull
}

/**
 * Freshness rule: reject when `now > timestampMs + ttlMs` OR
 * `|now - timestampMs| > 120000` (both → `expired`).
 */
object EnvelopeClock {
    fun check(env: EnvelopeV1, nowMs: Long): String? {
        if (nowMs > env.timestampMs + env.ttlMs) return "envelope expired (ttlMs ${env.ttlMs})"
        if (kotlin.math.abs(nowMs - env.timestampMs) > EnvelopeLimits.MAX_SKEW_MS) {
            return "clock skew exceeds ${EnvelopeLimits.MAX_SKEW_MS} ms"
        }
        return null
    }
}

/**
 * Seen-nonce cache keyed by (sourceNodeRef, nonce). `sourceNodeRef` is derived
 * from the authenticated caller (package + cert), never from the payload.
 * Entries are kept for at least [EnvelopeLimits.REPLAY_RETENTION_MS].
 */
class ReplayGuard(
    private val retentionMs: Long = EnvelopeLimits.REPLAY_RETENTION_MS,
    private val maxEntries: Int = 8192,
) {
    init {
        require(retentionMs >= EnvelopeLimits.REPLAY_RETENTION_MS) { "replay retention must be >= 10 minutes" }
    }

    private val seen = LinkedHashMap<Pair<String, String>, Long>()

    enum class Result { FRESH, REPLAY, FULL }

    @Synchronized
    fun checkAndRecord(sourceNodeRef: String, nonce: String, nowMs: Long): Result {
        prune(nowMs)
        val key = sourceNodeRef to nonce.lowercase()
        if (seen.containsKey(key)) return Result.REPLAY
        // Fail closed rather than evict a live nonce (eviction would reopen replay).
        if (seen.size >= maxEntries) return Result.FULL
        seen[key] = nowMs + retentionMs
        return Result.FRESH
    }

    @Synchronized
    fun size(): Int = seen.size

    private fun prune(nowMs: Long) {
        val it = seen.entries.iterator()
        while (it.hasNext()) if (it.next().value <= nowMs) it.remove()
    }
}
