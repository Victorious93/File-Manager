package ai.victorious.filemanager.core.net

import ai.victorious.filemanager.core.fs.NioFileIo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PinAttemptLimiterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000_000L
    private fun limiter() = PinAttemptLimiter(maxFailures = 5, baseLockoutMs = 60_000, maxLockoutMs = 15 * 60_000, clock = { now })

    private fun fail(l: PinAttemptLimiter, client: String, times: Int) = repeat(times) { l.recordFailure(client) }

    @Test
    fun locksAfterFiveWrongPinsAndUnlocksOnceTheDelayHasPassed() {
        val l = limiter()
        repeat(4) { l.recordFailure("a"); assertEquals(0, l.retryAfterSeconds("a")) }
        l.recordFailure("a")
        assertEquals(60, l.retryAfterSeconds("a"))
        now += 59_000
        assertEquals(1, l.retryAfterSeconds("a"))
        now += 1_000
        assertEquals(0, l.retryAfterSeconds("a"))
    }

    @Test
    fun aPartialSecondRoundsUpSoTheClientIsNeverToldZeroWhileLocked() {
        val l = limiter()
        fail(l, "a", 5)
        now += 59_500
        assertEquals(1, l.retryAfterSeconds("a"))
    }

    @Test
    fun oneClientsGuessesNeverLockAnother() {
        val l = limiter()
        fail(l, "attacker", 5)
        assertEquals(60, l.retryAfterSeconds("attacker"))
        assertEquals(0, l.retryAfterSeconds("owner"))
    }

    @Test
    fun aCorrectPinClearsTheRecord() {
        val l = limiter()
        fail(l, "a", 4)
        l.recordSuccess("a")
        fail(l, "a", 4)
        assertEquals(0, l.retryAfterSeconds("a"))
    }

    @Test
    fun eachRepeatLockoutDoublesUpToTheCap() {
        val l = limiter()
        val seen = mutableListOf<Long>()
        repeat(7) {
            fail(l, "a", 5)
            seen += l.retryAfterSeconds("a")
            now += l.retryAfterSeconds("a") * 1000
        }
        assertEquals(listOf(60L, 120L, 240L, 480L, 900L, 900L, 900L), seen)
    }

    @Test
    fun guessesMadeWhileLockedNeitherExtendNorEscalateTheLock() {
        val l = limiter()
        fail(l, "a", 5)
        now += 10_000
        fail(l, "a", 50)
        assertEquals(50, l.retryAfterSeconds("a"))
        now += 50_000
        assertEquals(0, l.retryAfterSeconds("a"))
        fail(l, "a", 5) // still only the second lockout, not the 52nd
        assertEquals(120, l.retryAfterSeconds("a"))
    }

    @Test
    fun manyDistinctAddressesDoNotGrowTheTableWithoutBound() {
        val l = limiter()
        repeat(5_000) { i -> l.recordFailure("10.0.${i / 250}.${i % 250}"); now += 1 }
        now += 2 * 60 * 60_000 // everyone has gone quiet for two hours
        l.recordFailure("fresh") // triggers pruning
        assertEquals(0, l.retryAfterSeconds("10.0.0.0"))
        fail(l, "fresh", 4)
        assertEquals(60, l.retryAfterSeconds("fresh"))
    }

    // ---- router behaviour ----

    private fun router(dir: java.io.File) = WifiTransferRouter(NioFileIo(), dir.absolutePath, pin = "1234", limiter = limiter())
    private fun req(pin: String?, client: String = "10.0.0.5") =
        WifiHttpRequest("GET", "/a.txt", emptyMap(), pinHeader = pin, clientId = client)

    @Test
    fun routerRefusesALockedOutClientEvenWithTheRightPin() {
        val dir = tmp.newFolder("lock").also { java.io.File(it, "a.txt").writeText("data") }
        val r = router(dir)
        repeat(5) { assertEquals(401, r.handle(req("0000")).status) }

        val locked = r.handle(req("1234"))
        assertEquals(429, locked.status)
        assertEquals("60", locked.headers["Retry-After"])
        assertEquals(200, r.handle(req("1234", client = "10.0.0.9")).status) // someone else is unaffected

        now += 60_000
        assertEquals(200, r.handle(req("1234")).status)
    }

    @Test
    fun requestsWithoutAnyPinAreNotCountedAsGuesses() {
        val dir = tmp.newFolder("nopin").also { java.io.File(it, "a.txt").writeText("data") }
        val r = router(dir)
        repeat(30) { assertEquals(401, r.handle(req(null)).status) }
        assertEquals(200, r.handle(req("1234")).status)
    }

    @Test
    fun aSuccessfulRequestResetsTheCount() {
        val dir = tmp.newFolder("reset").also { java.io.File(it, "a.txt").writeText("data") }
        val r = router(dir)
        repeat(4) { r.handle(req("0000")) }
        assertEquals(200, r.handle(req("1234")).status)
        repeat(4) { assertEquals(401, r.handle(req("0000")).status) }
        assertEquals(200, r.handle(req("1234")).status)
    }

    @Test
    fun noPinConfiguredMeansNothingToLimit() {
        val dir = tmp.newFolder("open").also { java.io.File(it, "a.txt").writeText("data") }
        val r = WifiTransferRouter(NioFileIo(), dir.absolutePath, pin = null, limiter = limiter())
        repeat(50) { assertEquals(200, r.handle(WifiHttpRequest("GET", "/a.txt", mapOf("pin" to "wrong"), null, clientId = "x")).status) }
    }
}
