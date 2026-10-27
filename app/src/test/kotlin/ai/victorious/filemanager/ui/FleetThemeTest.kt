package ai.victorious.filemanager.ui

import ai.victorious.filemanager.ui.theme.ThemePrefs
import ai.victorious.filemanager.ui.theme.contrastRatio
import ai.victorious.filemanager.ui.theme.fleet.FleetThemeContract
import ai.victorious.filemanager.ui.theme.fleet.FleetThemeResult
import ai.victorious.filemanager.ui.theme.fleet.FleetThemeState
import ai.victorious.filemanager.ui.theme.fleet.fleetStateFor
import ai.victorious.filemanager.ui.theme.schemeFor
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FleetThemeTest {
    private val specJson = """
        {"schemaVersion":1,"mode":"NEON_FLEET","iconStyle":"SYSTEM","iconPackPackage":"","updatedAtMs":42,
         "palette":{"background":"#FF0B0C12","surface":"#FF161821","primary":"#FF00E5FF","secondary":"#FFE840FF",
         "onBackground":"#FFF5F7FF","muted":"#FF9AA3B5","danger":"#FFFF6B8A","stale":"#FFFFC14D","tile":"#CC1A1D2B"}}
    """.trimIndent()

    private fun ok(r: FleetThemeResult) = (r as FleetThemeResult.Ok).palette

    @Test fun parsesSpecExampleJson() {
        val p = ok(FleetThemeContract.parseJson(specJson))
        assertEquals("NEON_FLEET", p.mode)
        assertEquals(0xFF0B0C12, p.background)
        assertEquals(0xFF00E5FF, p.primary)
        assertEquals(0xFFE840FF, p.secondary)
        assertEquals(0xFFFF6B8A, p.danger)
        assertEquals(42L, p.updatedAtMs)
    }

    @Test fun columnsWinOverJsonAndUnknownColumnsAreIgnored() {
        val p = ok(FleetThemeContract.parseRow(mapOf(
            "schemaVersion" to 1L, "mode" to "LIGHT", "primary" to "#006064",
            "futureColumn" to "whatever", "json" to specJson,
        )))
        assertEquals("LIGHT", p.mode)
        assertEquals(0xFF006064, p.primary) // #RRGGBB gets opaque alpha
        assertEquals(0xFF161821, p.surface) // filled from json
    }

    @Test fun hexFormats() {
        assertEquals(0xFF112233, FleetThemeContract.parseHex("#112233"))
        assertEquals(0xCC1A1D2B, FleetThemeContract.parseHex("#CC1A1D2B"))
        assertNull(FleetThemeContract.parseHex("112233"))
        assertNull(FleetThemeContract.parseHex("#12345"))
        assertNull(FleetThemeContract.parseHex("#GG0000"))
    }

    @Test fun rejectsOtherSchemaVersions() {
        for (v in listOf(0, 2, 99)) {
            val r = FleetThemeContract.parseJson(specJson.replace("\"schemaVersion\":1", "\"schemaVersion\":$v"))
            assertEquals(FleetThemeResult.UnsupportedVersion(v), r)
        }
        assertEquals(FleetThemeResult.UnsupportedVersion(2), FleetThemeContract.parseRow(mapOf("schemaVersion" to 2L, "json" to specJson)))
        assertTrue(FleetThemeContract.parseRow(mapOf("json" to "{}")) is FleetThemeResult.Invalid)
        assertTrue(FleetThemeContract.parseJson("not json") is FleetThemeResult.Invalid)
    }

    @Test fun missingRequiredColourIsInvalid() {
        assertTrue(FleetThemeContract.parseJson(specJson.replace("\"primary\":\"#FF00E5FF\",", "")) is FleetThemeResult.Invalid)
    }

    @Test fun fallbackStatesNeverThrow() {
        assertTrue(fleetStateFor(Result.failure(SecurityException("not pinned"))) is FleetThemeState.Unavailable)
        assertTrue(fleetStateFor(Result.failure(IllegalStateException())) is FleetThemeState.Unavailable)
        assertTrue(fleetStateFor(Result.success(null)) is FleetThemeState.Unavailable) // provider missing
        assertTrue(fleetStateFor(Result.success(FleetThemeResult.UnsupportedVersion(2))) is FleetThemeState.Unavailable)
        assertTrue(fleetStateFor(Result.success(FleetThemeContract.parseJson(specJson))) is FleetThemeState.Applied)
    }

    @Test fun schemeFallsBackToUsersThemeWithoutFleetPalette() {
        val prefs = ThemePrefs(fleetTheme = true)
        assertEquals(schemeFor(prefs.copy(fleetTheme = false), true, null), schemeFor(prefs, true, null, null))
        val fleet = ok(FleetThemeContract.parseJson(specJson))
        // Off: the fleet palette is ignored even when available.
        assertEquals(schemeFor(ThemePrefs(), true, null), schemeFor(ThemePrefs(), true, null, fleet))
        val s = schemeFor(prefs, false, null, fleet)
        assertEquals(Color(0xFF0B0C12), s.background)
        assertEquals(Color(0xFF00E5FF), s.primary)
        assertEquals(Color(0xFF161821), s.surface)
        assertTrue(contrastRatio(s.onBackground, s.background) >= 4.5f)
        assertTrue(contrastRatio(s.onSurface, s.surface) >= 4.5f)
    }

    @Test fun providerVerificationNeedsPackageAndPinnedCert() {
        val pin = FleetThemeContract.PROVIDER_CERT_PINS.first()
        assertTrue(FleetThemeContract.isTrustedProvider("com.victorious.launch", listOf(pin)))
        assertTrue(FleetThemeContract.isTrustedProvider("com.victorious.launch", listOf(pin.replace(":", "").lowercase())))
        assertFalse(FleetThemeContract.isTrustedProvider("com.evil.launch", listOf(pin)))
        assertFalse(FleetThemeContract.isTrustedProvider("com.victorious.launch", listOf("00:11")))
        assertFalse(FleetThemeContract.isTrustedProvider("com.victorious.launch", emptyList()))
        assertFalse(FleetThemeContract.isTrustedProvider("com.victorious.launch", listOf(pin, "AB:CD")))
        assertFalse(FleetThemeContract.isTrustedProvider(null, listOf(pin)))
    }
}
