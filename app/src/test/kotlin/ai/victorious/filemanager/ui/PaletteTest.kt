package ai.victorious.filemanager.ui

import ai.victorious.filemanager.ui.theme.Accent
import ai.victorious.filemanager.ui.theme.DarkColors
import ai.victorious.filemanager.ui.theme.LightColors
import ai.victorious.filemanager.ui.theme.Palette
import ai.victorious.filemanager.ui.theme.ThemeMode
import ai.victorious.filemanager.ui.theme.ThemePrefs
import ai.victorious.filemanager.ui.theme.contrastRatio
import ai.victorious.filemanager.ui.theme.paletteScheme
import ai.victorious.filemanager.ui.theme.schemeFor
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteTest {
    private val named = Palette.entries.filter { it != Palette.CLASSIC }

    private val violations = mutableListOf<String>()

    private fun atLeast(min: Float, fg: Color, bg: Color, what: String) {
        val ratio = contrastRatio(fg, bg)
        if (ratio < min) violations += "$what is ${"%.2f".format(ratio)}:1, needs $min:1"
    }

    /** Reports every pair that is too faint at once, not just the first. */
    private fun assertNoViolations() = assertTrue(violations.joinToString("\n"), violations.isEmpty())

    @Test fun contrastRatioMatchesWcagReferenceValues() {
        assertEquals(21f, contrastRatio(Color.Black, Color.White), 0.01f)
        assertEquals(1f, contrastRatio(Color.White, Color.White), 0.001f)
        // Symmetric: the ratio does not depend on which colour is the text.
        assertEquals(contrastRatio(Color(0xFF3D7DFF), Color.White), contrastRatio(Color.White, Color(0xFF3D7DFF)), 0.001f)
    }

    @Test fun textIsReadableInEveryNamedThemeInBothModes() {
        for (p in named) for (dark in listOf(false, true)) {
            val s = paletteScheme(p, dark)
            val t = "${p.name} dark=$dark"
            atLeast(7f, s.onBackground, s.background, "$t onBackground/background")
            atLeast(7f, s.onSurface, s.surface, "$t onSurface/surface")
            atLeast(4.5f, s.onSurfaceVariant, s.surface, "$t onSurfaceVariant/surface")
            atLeast(4.5f, s.onSurfaceVariant, s.surfaceVariant, "$t onSurfaceVariant/surfaceVariant")
            atLeast(4.5f, s.onPrimary, s.primary, "$t onPrimary/primary")
            atLeast(4.5f, s.onSecondary, s.secondary, "$t onSecondary/secondary")
            atLeast(4.5f, s.onPrimaryContainer, s.primaryContainer, "$t onPrimaryContainer/primaryContainer")
            atLeast(4.5f, s.onSecondaryContainer, s.secondaryContainer, "$t onSecondaryContainer/secondaryContainer")
            atLeast(4.5f, s.onTertiaryContainer, s.tertiaryContainer, "$t onTertiaryContainer/tertiaryContainer")
            atLeast(4.5f, s.onError, s.error, "$t onError/error")
            atLeast(4.5f, s.onErrorContainer, s.errorContainer, "$t onErrorContainer/errorContainer")
        }
        assertNoViolations()
    }

    @Test fun accentColoursStandOutFromTheSurfaceTheyAreDrawnOn() {
        for (p in named) for (dark in listOf(false, true)) {
            val s = paletteScheme(p, dark)
            val t = "${p.name} dark=$dark"
            // Text buttons and links use primary directly as the text colour.
            atLeast(4.5f, s.primary, s.surface, "$t primary/surface")
            atLeast(4.5f, s.primary, s.background, "$t primary/background")
            atLeast(3f, s.secondary, s.surface, "$t secondary/surface")
            // Field borders and dividers must be visible; selected chips and the nav indicator must show.
            atLeast(2f, s.outline, s.surface, "$t outline/surface")
            atLeast(1.25f, s.secondaryContainer, s.surface, "$t secondaryContainer/surface")
            atLeast(1.25f, s.primaryContainer, s.surface, "$t primaryContainer/surface")
        }
        assertNoViolations()
    }

    @Test fun surfaceLayersStepAwayFromTheBackgroundInOrder() {
        for (p in named) {
            val d = paletteScheme(p, dark = true)
            val order = listOf(d.surfaceContainerLowest, d.surfaceContainerLow, d.surfaceContainer, d.surfaceContainerHigh, d.surfaceContainerHighest)
            val lum = order.map { it.red + it.green + it.blue }
            assertEquals("${p.name} dark layers must get lighter", lum.sorted(), lum)
        }
    }

    @Test fun classicIsTheOriginalSchemeUntouched() {
        assertSame(DarkColors, paletteScheme(Palette.CLASSIC, dark = true))
        assertSame(LightColors, paletteScheme(Palette.CLASSIC, dark = false))
        assertEquals(Palette.CLASSIC, ThemePrefs().palette)
    }

    @Test fun anAccentPresetDoesNotRecolorANamedTheme() {
        val cyber = schemeFor(ThemePrefs(ThemeMode.DARK, accent = Accent.PINK, palette = Palette.CYBER), systemDark = false, dynamic = null)
        assertEquals(paletteScheme(Palette.CYBER, dark = true).primary, cyber.primary)
        assertNotEquals(Color(Accent.PINK.dark), cyber.primary)
        // ...but it still recolors Classic, as before.
        val classic = schemeFor(ThemePrefs(ThemeMode.DARK, accent = Accent.PINK), systemDark = false, dynamic = null)
        assertEquals(Color(Accent.PINK.dark), classic.primary)
    }

    @Test fun modeChoosesTheLightOrDarkSchemeOfTheTheme() {
        val light = schemeFor(ThemePrefs(ThemeMode.LIGHT, palette = Palette.OCEAN), systemDark = true, dynamic = null)
        assertEquals(paletteScheme(Palette.OCEAN, dark = false).background, light.background)
        val dark = schemeFor(ThemePrefs(ThemeMode.DARK, palette = Palette.OCEAN), systemDark = false, dynamic = null)
        assertEquals(paletteScheme(Palette.OCEAN, dark = true).background, dark.background)
        val system = schemeFor(ThemePrefs(ThemeMode.SYSTEM, palette = Palette.OCEAN), systemDark = true, dynamic = null)
        assertEquals(dark.background, system.background)
    }

    @Test fun amoledStillGivesBlackUnderANamedTheme() {
        val s = schemeFor(ThemePrefs(ThemeMode.AMOLED, palette = Palette.EMBER), systemDark = false, dynamic = null)
        assertEquals(Color.Black, s.background)
        assertEquals(Color.Black, s.surface)
        assertEquals(paletteScheme(Palette.EMBER, dark = true).primary, s.primary)
    }

    @Test fun dynamicColourStillWinsOverANamedTheme() {
        val wallpaper = LightColors.copy(primary = Color(0xFF123456))
        val s = schemeFor(ThemePrefs(ThemeMode.LIGHT, dynamicColor = true, palette = Palette.TERMINAL), false, wallpaper)
        assertEquals(Color(0xFF123456), s.primary)
    }

    @Test fun themeNamesAreStableBecauseTheyArePersisted() {
        // Renaming an entry silently resets that user's saved choice to Classic.
        assertEquals(
            listOf("CLASSIC", "CYBER", "TERMINAL", "OCEAN", "EMBER", "ROSE", "GRAPHITE"),
            Palette.entries.map { it.name },
        )
    }

    @Test fun everyThemeHasADistinctLabelAndChipColour() {
        assertEquals(Palette.entries.size, Palette.entries.map { it.label }.toSet().size)
        assertTrue(Palette.entries.all { it.label.isNotBlank() })
        assertEquals(Palette.entries.size, Palette.entries.map { it.swatch }.toSet().size)
    }
}
