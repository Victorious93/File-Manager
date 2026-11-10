package ai.victorious.filemanager.ui

import ai.victorious.filemanager.ui.theme.Palette
import ai.victorious.filemanager.ui.theme.ThemeMode
import ai.victorious.filemanager.ui.theme.ThemePrefs
import ai.victorious.filemanager.ui.theme.contrastRatio
import ai.victorious.filemanager.ui.theme.darkDefaultMigration
import ai.victorious.filemanager.ui.theme.paletteScheme
import ai.victorious.filemanager.ui.theme.schemeFor
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Don dislikes white screens: the default look must be the dark fleet neon theme on any phone. */
class DarkByDefaultTest {
    @Test fun defaultPrefsAreDarkFleetNeon() {
        val d = ThemePrefs()
        assertEquals(ThemeMode.DARK, d.mode)
        assertEquals(Palette.FLEET_NEON, d.palette)
    }

    @Test fun defaultSchemeIsTheFleetPaletteEvenOnALightModePhone() {
        val s = schemeFor(ThemePrefs(), systemDark = false, dynamic = null)
        assertEquals(Color(0xFF0B0F1A), s.background)
        assertEquals(Color(0xFF00E5FF), s.primary)
        assertEquals(Color(0xFFFF2BD6), s.secondary)
        listOf(s.background, s.surface, s.surfaceVariant, s.surfaceContainerLowest, s.surfaceContainerLow, s.surfaceContainer,
            s.surfaceContainerHigh, s.surfaceContainerHighest, s.primaryContainer, s.secondaryContainer)
            .forEach { assertTrue("$it is too light for a fleet surface", contrastRatio(Color.White, it) >= 10f) }
        assertTrue(contrastRatio(s.onBackground, s.background) >= 7f)
        assertTrue(contrastRatio(s.onSurface, s.surface) >= 7f)
    }

    @Test fun lightIsStillAnExplicitChoice() {
        val light = schemeFor(ThemePrefs(mode = ThemeMode.LIGHT), systemDark = false, dynamic = null)
        assertEquals(paletteScheme(Palette.FLEET_NEON, dark = false).background, light.background)
        assertTrue(contrastRatio(light.primary, light.surface) >= 4.5f)
    }

    @Test fun oldUntouchedDefaultsMigrateButExplicitChoicesStay() {
        assertEquals(ThemeMode.DARK.name to Palette.FLEET_NEON.name, darkDefaultMigration(null, null))
        assertEquals(ThemeMode.DARK.name to Palette.FLEET_NEON.name, darkDefaultMigration("SYSTEM", "CLASSIC"))
        assertNull(darkDefaultMigration("LIGHT", "CLASSIC"))
        assertNull(darkDefaultMigration("SYSTEM", "OCEAN"))
        assertNull(darkDefaultMigration("AMOLED", null))
        assertNull(darkDefaultMigration("DARK", "CLASSIC"))
    }

    /** The platform base theme decides the colour of splash frames and system dialogs: it must be a dark parent. */
    @Test fun platformBaseThemeIsDarkInEverySystemMode() {
        val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
        for (dir in listOf("values", "values-night")) {
            val xml = File(res, "$dir/themes.xml").readText()
            val parent = Regex("""name="Base.Theme.VictoriousFileManager" parent="([^"]+)"""").find(xml)!!.groupValues[1]
            assertTrue("$dir uses $parent", !parent.contains("Light"))
        }
        val launch = File(res, "values/colors.xml").readText()
        val hex = Regex("""name="launch_background">#([0-9A-Fa-f]{6})<""").find(launch)!!.groupValues[1]
        assertTrue(contrastRatio(Color.White, Color(("FF$hex").toLong(16))) >= 12f)
    }
}
