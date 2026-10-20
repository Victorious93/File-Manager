package ai.victorious.filemanager.core.appmgr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileEditTest {
    @Test fun reorderAndSummarize() {
        val a = ProfileAction.ForceStop("a")
        val b = ProfileAction.DisablePackage("b")
        val c = ProfileAction.EnablePackage("c")
        val moved = ProfileActions.move(listOf(a, b, c), 0, 2)
        assertEquals(listOf(b, c, a), moved)
        assertEquals(listOf(a, c), ProfileActions.removeAt(listOf(a, b, c), 1))
        assertTrue(ProfileActions.summarize(a).contains("Force-stop"))
    }

    @Test fun scheduleRoundTripInCodec() {
        val p = AppProfile(
            id = "1", name = "night",
            actions = listOf(ProfileAction.ForceStop("com.x")),
            schedule = ProfileSchedule(mode = "daily", hourOfDay = 23, minuteOfHour = 30),
        )
        val again = ProfileCodec.decode(ProfileCodec.encode(listOf(p))).single()
        assertEquals("daily", again.schedule.mode)
        assertEquals(23, again.schedule.hourOfDay)
        assertEquals(30, again.schedule.minuteOfHour)
    }
}
