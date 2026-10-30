package ai.victorious.filemanager.ui

import ai.victorious.filemanager.ui.theme.FleetDarkColors
import ai.victorious.filemanager.ui.theme.contrastRatio
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FleetDarkSchemeTest {
    @Test fun usesFleetColours() {
        assertEquals(Color(0xFF0B0F1A), FleetDarkColors.background)
        assertEquals(Color(0xFF0B0F1A), FleetDarkColors.surface)
        assertEquals(Color(0xFF00E5FF), FleetDarkColors.primary)
        assertEquals(Color(0xFFFF2BD6), FleetDarkColors.secondary)
    }

    @Test fun nothingIsWhiteAndTextIsReadable() {
        val s = FleetDarkColors
        listOf(s.background, s.surface, s.surfaceVariant, s.surfaceContainer, s.surfaceContainerHigh, s.surfaceContainerHighest)
            .forEach { assertTrue("$it is too light", contrastRatio(Color.White, it) >= 12f) }
        assertTrue(contrastRatio(s.onBackground, s.background) >= 7f)
        assertTrue(contrastRatio(s.onSurfaceVariant, s.background) >= 4.5f)
        assertTrue(contrastRatio(s.onPrimary, s.primary) >= 4.5f)
        assertTrue(contrastRatio(s.onSecondary, s.secondary) >= 4.5f)
    }
}
