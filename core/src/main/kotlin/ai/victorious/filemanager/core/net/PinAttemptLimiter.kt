package ai.victorious.filemanager.core.net

/**
 * Throttles wrong-PIN guesses per client. The Wi-Fi transfer PIN is only four digits, so without a
 * limit anyone on the same network can try all 10,000 in minutes.
 *
 * After [maxFailures] wrong PINs in a row a client is locked out for [baseLockoutMs]; each further
 * lockout doubles, up to [maxLockoutMs]. A correct PIN clears the client's record. Only clients are
 * limited, never the server as a whole, so someone else on the network cannot lock the owner out.
 */
class PinAttemptLimiter(
    private val maxFailures: Int = 5,
    private val baseLockoutMs: Long = 60_000,
    private val maxLockoutMs: Long = 15 * 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class State(var failures: Int = 0, var lockouts: Int = 0, var lockedUntil: Long = 0, var lastSeen: Long = 0)

    private val clients = HashMap<String, State>()

    /** Whole seconds until [client] may try again; 0 when it is not locked out. */
    @Synchronized
    fun retryAfterSeconds(client: String): Long {
        val remaining = (clients[client]?.lockedUntil ?: return 0) - clock()
        return if (remaining > 0) (remaining + 999) / 1000 else 0
    }

    /** Records one wrong PIN. Guesses made while already locked out neither extend nor escalate the lock. */
    @Synchronized
    fun recordFailure(client: String) {
        val now = clock()
        prune(now)
        val s = clients.getOrPut(client) { State() }
        s.lastSeen = now
        if (s.lockedUntil > now) return
        if (++s.failures < maxFailures) return
        s.failures = 0
        s.lockouts++
        s.lockedUntil = now + minOf(maxLockoutMs, baseLockoutMs shl minOf(s.lockouts - 1, 20))
    }

    @Synchronized
    fun recordSuccess(client: String) {
        clients.remove(client)
    }

    /** Forgets clients that went quiet an hour ago, and bounds the table if many addresses show up. */
    private fun prune(now: Long) {
        if (clients.size < 64) return
        clients.values.removeAll { it.lockedUntil <= now && now - it.lastSeen > IDLE_MS }
        if (clients.size > MAX_CLIENTS) clients.values.removeAll { it.lockedUntil <= now }
    }

    private companion object {
        const val IDLE_MS = 60 * 60_000L
        const val MAX_CLIENTS = 1024
    }
}
