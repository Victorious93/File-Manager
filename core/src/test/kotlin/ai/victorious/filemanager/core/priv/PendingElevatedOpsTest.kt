package ai.victorious.filemanager.core.priv

import ai.victorious.filemanager.core.ops.OpKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingElevatedOpsTest {
    private var now = 0L
    private var seq = 0
    private fun ops(ttl: Long = 1000, max: Int = 20) = PendingElevatedOps({ now }, ttl, max, { "id${seq++}" })

    private val none = PrivilegeAvailability(false, false, false, false, false)
    private val shizukuUp = PrivilegeAvailability(false, false, true, true, true)
    private val shizukuNoPerm = PrivilegeAvailability(false, false, true, false, true)
    private val path = "/storage/emulated/0/Android/data/com.x/files/a.txt"

    @Test fun parkedWorkNeverRunsOnItsOwn() {
        var ran = 0
        val p = ops()
        p.park(OpKind.COPY, "Copy a.txt", path) { ran++ }
        assertEquals(0, ran)
        assertFalse(p.canResume(none))
        assertTrue(p.canResume(shizukuUp))
        assertEquals(0, ran) // canResume only reports
    }

    @Test fun onlyElevatedPathsCanBeParked() {
        try {
            ops().park(OpKind.COPY, "x", "/storage/emulated/0/Download/a.txt") {}
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {}
    }

    @Test fun resumeRunsInParkOrderOnceAccessIsGranted() {
        val order = mutableListOf<String>()
        val p = ops()
        p.park(OpKind.COPY, "one", path) { order += "one" }
        p.park(OpKind.DELETE, "two", path) { order += "two" }
        val out = p.resumeAll(shizukuUp)
        assertEquals(listOf("one", "two"), order)
        assertTrue(out.all { it is PendingElevatedOps.Outcome.Done })
        assertTrue(p.waiting().isEmpty())
    }

    @Test fun blockedResumeKeepsEverythingParkedAndExplainsWhy() {
        var ran = 0
        val p = ops()
        p.park(OpKind.COPY, "one", path) { ran++ }
        val out = p.resumeAll(shizukuNoPerm)
        val blocked = out.single() as PendingElevatedOps.Outcome.Blocked
        assertTrue(blocked.reason.contains("no permission"))
        assertEquals(0, ran)
        assertEquals(1, p.waiting().size)
    }

    @Test fun failedEntryIsReportedAndDroppedNotRetried() {
        var calls = 0
        val p = ops()
        p.park(OpKind.MOVE, "bad", path) { calls++; throw PrivilegedOpException("denied") }
        val out = p.resumeAll(shizukuUp).single() as PendingElevatedOps.Outcome.Failed
        assertEquals("denied", out.message)
        assertTrue(p.waiting().isEmpty())
        p.resumeAll(shizukuUp)
        assertEquals(1, calls)
    }

    @Test fun entryCannotRunTwice() {
        var calls = 0
        val p = ops()
        val e = p.park(OpKind.COPY, "x", path) { calls++ }
        assertTrue(p.resume(shizukuUp, e.id) is PendingElevatedOps.Outcome.Done)
        assertNull(p.resume(shizukuUp, e.id))
        assertEquals(1, calls)
    }

    @Test fun staleEntriesExpire() {
        var ran = 0
        val p = ops(ttl = 1000)
        p.park(OpKind.COPY, "old", path) { ran++ }
        now += 1001
        assertTrue(p.waiting().isEmpty())
        assertFalse(p.canResume(shizukuUp))
        assertTrue(p.resumeAll(shizukuUp).isEmpty())
        assertEquals(0, ran)
    }

    @Test fun oldestIsDroppedBeyondTheLimit() {
        val p = ops(max = 2)
        p.park(OpKind.COPY, "1", path) {}
        p.park(OpKind.COPY, "2", path) {}
        p.park(OpKind.COPY, "3", path) {}
        assertEquals(listOf("2", "3"), p.waiting().map { it.label })
    }

    @Test fun discardRemovesAnEntry() {
        val p = ops()
        val e = p.park(OpKind.COPY, "x", path) {}
        assertTrue(p.discard(e.id))
        assertFalse(p.discard(e.id))
        assertTrue(p.waiting().isEmpty())
    }
}
