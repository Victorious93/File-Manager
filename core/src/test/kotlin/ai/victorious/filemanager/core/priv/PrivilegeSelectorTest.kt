package ai.victorious.filemanager.core.priv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegeSelectorTest {
    private fun a(
        rootAvailable: Boolean = false,
        rootEnabled: Boolean = false,
        shizukuAvailable: Boolean = false,
        shizukuPermission: Boolean = false,
        shizukuEnabled: Boolean = false,
    ) = PrivilegeAvailability(rootAvailable, rootEnabled, shizukuAvailable, shizukuPermission, shizukuEnabled)

    @Test fun prefersRootThenShizukuThenNormal() {
        assertEquals(PrivilegeBackend.ROOT, PrivilegeSelector.select(a(rootAvailable = true, rootEnabled = true, shizukuAvailable = true, shizukuPermission = true, shizukuEnabled = true)))
        assertEquals(PrivilegeBackend.SHIZUKU, PrivilegeSelector.select(a(shizukuAvailable = true, shizukuPermission = true, shizukuEnabled = true)))
        assertEquals(PrivilegeBackend.NORMAL, PrivilegeSelector.select(a(rootEnabled = true))) // enabled but not available
        assertEquals(PrivilegeBackend.NORMAL, PrivilegeSelector.select(a()))
    }

    @Test fun shizukuNeedsPermission() {
        assertEquals(PrivilegeBackend.NORMAL, PrivilegeSelector.select(a(shizukuAvailable = true, shizukuEnabled = true, shizukuPermission = false)))
    }

    @Test fun needsElevationPaths() {
        assertTrue(PrivilegeSelector.needsElevation("/data/data/com.a"))
        assertTrue(PrivilegeSelector.needsElevation("/system/app"))
        assertTrue(PrivilegeSelector.needsElevation("/storage/emulated/0/Android/data/com.a"))
        assertTrue(PrivilegeSelector.needsElevation("/storage/emulated/0/Android/obb/com.a"))
        assertFalse(PrivilegeSelector.needsElevation("/storage/emulated/0/Download"))
        assertFalse(PrivilegeSelector.needsElevation("/sdcard/Pictures"))
    }

    @Test fun missingReasonMessages() {
        assertTrue(PrivilegeSelector.missingReason(a(rootEnabled = true))!!.contains("su"))
        assertTrue(PrivilegeSelector.missingReason(a(shizukuEnabled = true))!!.contains("not running"))
        assertTrue(PrivilegeSelector.missingReason(a(shizukuEnabled = true, shizukuAvailable = true))!!.contains("permission"))
        assertNull(PrivilegeSelector.missingReason(a(rootAvailable = true, rootEnabled = true)))
    }
}
