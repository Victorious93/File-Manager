package ai.victorious.filemanager.core.priv

import ai.victorious.filemanager.core.ops.OpKind
import java.util.UUID

/**
 * File operations that failed only because the path needs Root or Shizuku and neither was usable
 * yet. They are parked here so that, once access is granted, the user can finish them with one tap
 * instead of selecting everything again.
 *
 * Safety rules, matching [ConfirmGate]:
 *  - Nothing runs by itself. [resume] and [resumeAll] are for a tap handler; the type of work
 *    accepted is limited to [OpKind] file operations the user already asked for, never a
 *    [PowerAction] (chmod, remount, install ...), which keep their own single-use confirmation.
 *  - Parked work expires after [ttlMs], and at most [maxEntries] are kept, so a stale request from
 *    long ago cannot run against a changed folder.
 *  - An entry is removed before it runs, so a double tap cannot run it twice, and a failed entry is
 *    reported and dropped rather than retried in a loop.
 */
class PendingElevatedOps(
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = 10 * 60 * 1000L,
    private val maxEntries: Int = 20,
    private val ids: () -> String = { UUID.randomUUID().toString() },
) {
    data class Entry(val id: String, val kind: OpKind, val label: String, val path: String, val parkedAt: Long)

    sealed interface Outcome {
        val entry: Entry

        data class Done(override val entry: Entry) : Outcome
        data class Failed(override val entry: Entry, val message: String) : Outcome
        /** Elevated access still is not usable; the entry stays parked. */
        data class Blocked(override val entry: Entry, val reason: String) : Outcome
    }

    private class Parked(val entry: Entry, val action: () -> Unit)

    private val parked = LinkedHashMap<String, Parked>()

    /**
     * Parks [action] for [path]. Only paths that really need elevation are accepted, so this cannot
     * become a general deferred-work queue.
     */
    @Synchronized
    fun park(kind: OpKind, label: String, path: String, action: () -> Unit): Entry {
        require(PrivilegeSelector.needsElevation(path)) { "Not an elevated path: $path" }
        prune()
        val entry = Entry(ids(), kind, label, path, clock())
        parked[entry.id] = Parked(entry, action)
        while (parked.size > maxEntries) parked.remove(parked.keys.first())
        return entry
    }

    /** What is waiting (oldest first), for a "N operations are waiting for Root/Shizuku" banner. */
    @Synchronized
    fun waiting(): List<Entry> {
        prune()
        return parked.values.map { it.entry }
    }

    /** True when something is waiting and [availability] now allows elevated access. */
    @Synchronized
    fun canResume(availability: PrivilegeAvailability): Boolean {
        prune()
        return parked.isNotEmpty() && PrivilegeSelector.select(availability) != PrivilegeBackend.NORMAL
    }

    /** Runs one parked entry. Call only from a user tap. Returns null if it is gone or expired. */
    fun resume(availability: PrivilegeAvailability, id: String): Outcome? {
        val item = synchronized(this) {
            prune()
            val p = parked[id] ?: return null
            blockedReason(availability)?.let { return Outcome.Blocked(p.entry, it) }
            parked.remove(id)
        } ?: return null
        return run(item)
    }

    /** Runs everything parked, oldest first. Call only from a user tap. */
    fun resumeAll(availability: PrivilegeAvailability): List<Outcome> {
        val items = synchronized(this) {
            prune()
            blockedReason(availability)?.let { reason ->
                return parked.values.map { Outcome.Blocked(it.entry, reason) }
            }
            parked.values.toList().also { parked.clear() }
        }
        return items.map(::run)
    }

    @Synchronized
    fun discard(id: String): Boolean = parked.remove(id) != null

    @Synchronized
    fun clear() = parked.clear()

    private fun blockedReason(a: PrivilegeAvailability): String? =
        if (PrivilegeSelector.select(a) == PrivilegeBackend.NORMAL) {
            PrivilegeSelector.missingReason(a) ?: "Elevated access unavailable."
        } else null

    private fun run(p: Parked): Outcome = try {
        p.action()
        Outcome.Done(p.entry)
    } catch (t: Throwable) {
        Outcome.Failed(p.entry, t.message ?: t::class.simpleName ?: "failed")
    }

    private fun prune() {
        val cutoff = clock() - ttlMs
        parked.values.removeAll { it.entry.parkedAt < cutoff }
    }
}
