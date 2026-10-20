package ai.victorious.filemanager.core.fleet

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/**
 * Holds remote requests that need on-device confirmation until the user
 * decides. Rules:
 * - never auto-approved; timeout (the envelope's own `timestampMs + ttlMs`) = DENIED;
 * - a decision is single-use and bound to the request's (sourceNodeRef, nonce);
 * - a second decision, a decision for an unknown/expired id, or a nonce mismatch is ignored.
 */
class ConfirmationBroker(
    private val clock: () -> Long = System::currentTimeMillis,
    /** Schedules a deadline check; the app passes a Handler.postDelayed. */
    private val schedule: (delayMs: Long, block: () -> Unit) -> Unit = { _, _ -> },
    private val maxPending: Int = 8,
) {
    enum class Decision { APPROVED, DENIED, TIMED_OUT }

    data class Pending(
        val id: String,
        val sourceNodeRef: String,
        val nonce: String,
        val callerPackage: String,
        val op: String,
        val actionRef: String,
        val paramsSummary: List<String>,
        val deadlineMs: Long,
        val createdAtMs: Long,
    )

    private class Slot(val pending: Pending, val onDecision: (Decision) -> Unit)

    private val slots = LinkedHashMap<String, Slot>()
    private val listeners = mutableListOf<(List<Pending>) -> Unit>()

    @Synchronized
    fun pending(): List<Pending> = slots.values.map { it.pending }

    fun addListener(l: (List<Pending>) -> Unit) {
        synchronized(this) { listeners += l }
        l(pending())
    }

    /** Returns the pending record, or null if it cannot be held (already expired / too many waiting). */
    fun submit(
        sourceNodeRef: String,
        nonce: String,
        callerPackage: String,
        op: String,
        actionRef: String,
        params: JsonObject,
        deadlineMs: Long,
        onDecision: (Decision) -> Unit,
    ): Pending? {
        val now = clock()
        val p: Pending
        synchronized(this) {
            if (deadlineMs <= now || slots.size >= maxPending) return null
            if (slots.values.any { it.pending.sourceNodeRef == sourceNodeRef && it.pending.nonce == nonce }) return null
            p = Pending(UUID.randomUUID().toString(), sourceNodeRef, nonce, callerPackage, op, actionRef, summarize(params), deadlineMs, now)
            slots[p.id] = Slot(p, onDecision)
        }
        schedule(deadlineMs - now) { expire() }
        notifyListeners()
        return p
    }

    /** User decision. [nonce] must match the held request. Returns true if this call decided it. */
    fun decide(id: String, nonce: String, approve: Boolean): Boolean {
        val slot: Slot
        synchronized(this) {
            val s = slots[id] ?: return false
            if (s.pending.nonce != nonce) return false
            slots.remove(id)
            slot = s
        }
        val late = clock() > slot.pending.deadlineMs
        slot.onDecision(if (late) Decision.TIMED_OUT else if (approve) Decision.APPROVED else Decision.DENIED)
        notifyListeners()
        return true
    }

    /** Denies everything past its deadline. Safe to call at any time. */
    fun expire() {
        val now = clock()
        val dead: List<Slot>
        synchronized(this) {
            dead = slots.values.filter { now >= it.pending.deadlineMs }
            dead.forEach { slots.remove(it.pending.id) }
        }
        dead.forEach { it.onDecision(Decision.TIMED_OUT) }
        if (dead.isNotEmpty()) notifyListeners()
    }

    private fun notifyListeners() {
        val snapshot = pending()
        val ls = synchronized(this) { listeners.toList() }
        ls.forEach { it(snapshot) }
    }

    companion object {
        const val MAX_LINES = 8
        const val MAX_VALUE = 40
        private val PATH_LIKE = Regex("""[/\\]|^[a-zA-Z]:|file:|content:""")

        /** `key: value` lines with secrets, paths and long values redacted. Never the raw params. */
        fun summarize(params: JsonObject): List<String> {
            val lines = params.entries.take(MAX_LINES).map { (k, v) ->
                val keySecret = UnifiedFleetBackupCodec.secretScan(JsonPrimitive(k)) != null
                "$k: " + if (keySecret) "[redacted]" else value(v)
            }
            return if (params.size > MAX_LINES) lines + "… ${params.size - MAX_LINES} more" else lines
        }

        private fun value(v: JsonElement): String = when (v) {
            is JsonPrimitive -> when {
                !v.isString -> v.content
                UnifiedFleetBackupCodec.secretScan(v) != null -> "[redacted]"
                PATH_LIKE.containsMatchIn(v.content) -> "[path hidden]"
                v.content.length > MAX_VALUE -> "[text, ${v.content.length} chars]"
                else -> v.content
            }
            is JsonObject -> "{${v.size} fields}"
            is JsonArray -> "[${v.size} items]"
            JsonNull -> "null"
        }
    }
}
