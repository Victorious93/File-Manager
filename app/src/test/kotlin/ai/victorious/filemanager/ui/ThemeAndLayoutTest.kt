package ai.victorious.filemanager.ui

import ai.victorious.filemanager.ui.theme.Accent
import ai.victorious.filemanager.ui.theme.ThemeMode
import ai.victorious.filemanager.ui.theme.ThemePrefs
import ai.victorious.filemanager.ui.theme.LightColors
import ai.victorious.filemanager.ui.theme.isDark
import ai.victorious.filemanager.ui.theme.schemeFor
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeAndLayoutTest {
    @Test fun modeResolution() {
        assertTrue(isDark(ThemeMode.SYSTEM, systemDark = true))
        assertFalse(isDark(ThemeMode.SYSTEM, systemDark = false))
        assertFalse(isDark(ThemeMode.LIGHT, systemDark = true))
        assertTrue(isDark(ThemeMode.DARK, systemDark = false))
        assertTrue(isDark(ThemeMode.AMOLED, systemDark = false))
    }

    @Test fun accentRecolorsPrimaryAndAmoledIsBlack() {
        val light = schemeFor(ThemePrefs(ThemeMode.LIGHT, accent = Accent.PURPLE), systemDark = false, dynamic = null)
        assertEquals(Color(Accent.PURPLE.light), light.primary)
        val dark = schemeFor(ThemePrefs(ThemeMode.DARK, accent = Accent.TEAL), systemDark = false, dynamic = null)
        assertEquals(Color(Accent.TEAL.dark), dark.primary)
        assertNotEquals(Color.Black, dark.background)
        val amoled = schemeFor(ThemePrefs(ThemeMode.AMOLED), systemDark = false, dynamic = null)
        assertEquals(Color.Black, amoled.background)
        assertEquals(Color.Black, amoled.surface)
    }

    @Test fun dynamicSchemeWinsOverAccentButAmoledStillApplies() {
        val wallpaper = LightColors.copy(primary = Color(0xFF123456))
        assertEquals(Color(0xFF123456), schemeFor(ThemePrefs(ThemeMode.LIGHT, dynamicColor = true, accent = Accent.PINK), false, wallpaper).primary)
        val amoled = schemeFor(ThemePrefs(ThemeMode.AMOLED, dynamicColor = true), false, wallpaper)
        assertEquals(Color(0xFF123456), amoled.primary)
        assertEquals(Color.Black, amoled.background)
    }

    @Test fun adaptiveBreakpoints() {
        assertFalse(isWideWindow(411f)) // phone portrait
        assertTrue(isWideWindow(600f)) // small tablet / unfolded foldable
        assertTrue(isWideWindow(841f)) // phone landscape
        assertFalse(panesSideBySide(411f, 700f)) // phone portrait: stacked
        assertTrue(panesSideBySide(761f, 330f)) // phone landscape (rail takes 80dp)
        assertTrue(panesSideBySide(673f, 841f)) // unfolded foldable / tablet portrait
        assertFalse(panesSideBySide(350f, 300f)) // tiny split-screen window
    }
}
