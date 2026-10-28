package ai.victorious.filemanager.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.unit.dp

private val Teal = Color(0xFF2BB5A0)

internal val DarkColors = darkColorScheme(
    primary = Color(0xFF7AB8FF),
    onPrimary = Color(0xFF001B3D),
    secondary = Teal,
    onSecondary = Color(0xFF00332C),
    background = Color(0xFF0F1419),
    onBackground = Color(0xFFE7EEF6),
    surface = Color(0xFF161C22),
    onSurface = Color(0xFFE7EEF6),
    surfaceVariant = Color(0xFF1E262E),
    onSurfaceVariant = Color(0xFFB7C4D0),
    error = Color(0xFFFFB4AB),
    outline = Color(0xFF3D4A57),
)

internal val LightColors = lightColorScheme(
    primary = Color(0xFF3D7DFF),
    onPrimary = Color.White,
    secondary = Color(0xFF0F766E),
    onSecondary = Color.White,
    background = Color(0xFFF4F7FB),
    onBackground = Color(0xFF14202B),
    surface = Color.White,
    onSurface = Color(0xFF14202B),
    surfaceVariant = Color(0xFFE8EEF5),
    onSurfaceVariant = Color(0xFF3E4C59),
    error = Color(0xFFBA1A1A),
    outline = Color(0xFFC5D0DA),
)

/** True dark: black background/surfaces for OLED panels, keeping the dark-scheme contrast. */
internal fun ColorScheme.amoled(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceVariant = Color(0xFF101418),
    surfaceContainer = Color(0xFF080A0C),
    surfaceContainerLow = Color(0xFF050607),
    surfaceContainerLowest = Color.Black,
    surfaceContainerHigh = Color(0xFF0E1114),
    surfaceContainerHighest = Color(0xFF15191D),
)

/** Slightly rounder than the M3 defaults, used consistently for cards, chips and sheets. */
internal val AppShapes = androidx.compose.material3.Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(32.dp),
)

/** The app's spacing scale (4dp grid). Use these instead of ad-hoc values in new UI. */
object Spacing {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
}

internal fun isDark(mode: ThemeMode, systemDark: Boolean) = when (mode) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK, ThemeMode.AMOLED -> true
}

/**
 * Pure scheme selection (unit-tested). [dynamic] is the wallpaper scheme when the platform
 * supports it (API 31+) and the user enabled it; otherwise the accent preset recolors primary.
 */
internal fun schemeFor(
    prefs: ThemePrefs,
    systemDark: Boolean,
    dynamic: ColorScheme?,
    fleet: ai.victorious.filemanager.ui.theme.fleet.FleetPalette? = null,
): ColorScheme {
    // The fleet theme wins only while it is on and actually readable; otherwise fall back to the user's theme.
    if (prefs.fleetTheme && fleet != null) return fleetScheme(fleet)
    val dark = isDark(prefs.mode, systemDark)
    val base = dynamic ?: when {
        // Named themes bring their own primary; the accent presets only recolor Classic.
        prefs.palette != Palette.CLASSIC -> paletteScheme(prefs.palette, dark)
        dark -> DarkColors.copy(primary = Color(prefs.accent.dark), onPrimary = Color(0xFF0B1220))
        else -> LightColors.copy(primary = Color(prefs.accent.light), onPrimary = Color.White)
    }
    return if (prefs.mode == ThemeMode.AMOLED) base.amoled() else base
}

@Composable
fun VictoriousTheme(
    prefs: ThemePrefs = ThemePrefs(),
    fleet: ai.victorious.filemanager.ui.theme.fleet.FleetPalette? = currentFleetPalette(),
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val dark = if (prefs.fleetTheme && fleet != null) fleetIsDark(fleet) else isDark(prefs.mode, systemDark)
    val dynamic = if (prefs.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val ctx = LocalContext.current
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else null
    // enableEdgeToEdge() picks status/navigation bar icon colours from the *system* dark mode. The app's own
    // Light/Dark/AMOLED setting can differ from it (Dark on a light-mode phone), which left dark icons on a
    // dark screen, so follow the mode actually shown.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(
        colorScheme = schemeFor(prefs, systemDark, dynamic, fleet),
        shapes = AppShapes,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(LocalIconStyle provides prefs.iconStyle, content = content)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** The live fleet palette, or null when VictorLaunch's theme is not readable (then the user's theme is used). */
@Composable
fun currentFleetPalette(): ai.victorious.filemanager.ui.theme.fleet.FleetPalette? {
    val app = LocalContext.current.applicationContext as? ai.victorious.filemanager.FileManagerApplication ?: return null
    if (!app.isFleetThemeReady()) return null
    val state by app.fleetTheme.state.collectAsState()
    return (state as? ai.victorious.filemanager.ui.theme.fleet.FleetThemeState.Applied)?.palette
}
