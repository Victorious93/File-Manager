package ai.victorious.filemanager.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/** The few colours a [Palette] is built from; every other role is derived so the set stays coherent. */
internal class Seeds(
    val darkPrimary: Long,
    val darkSecondary: Long,
    val darkBackground: Long,
    val lightPrimary: Long,
    val lightSecondary: Long,
    val lightBackground: Long,
)

/**
 * A named colour theme with its own light and dark scheme, chosen independently of [ThemeMode].
 * CLASSIC is the original look and the only one the [Accent] presets recolor. The enum names are
 * persisted: renaming one silently resets that user's choice, so add entries and never rename.
 */
enum class Palette(val label: String, internal val seeds: Seeds?) {
    CLASSIC("Classic", null),
    /** Cyan and lime on near-black, taken from the app logo. */
    CYBER("Cyber", Seeds(0xFF22D3EE, 0xFFA3E635, 0xFF05080D, 0xFF0E7490, 0xFF4D7C0F, 0xFFF1F8FA)),
    /** Phosphor green and amber on black. */
    TERMINAL("Terminal", Seeds(0xFF4ADE80, 0xFFFACC15, 0xFF030803, 0xFF15803D, 0xFF8A5300, 0xFFF1F8F1)),
    OCEAN("Ocean", Seeds(0xFF60A5FA, 0xFF2DD4BF, 0xFF0A1522, 0xFF1D4ED8, 0xFF0F766E, 0xFFEFF5FC)),
    EMBER("Ember", Seeds(0xFFFB923C, 0xFFF87171, 0xFF140C08, 0xFFC2410C, 0xFFB91C1C, 0xFFFDF5EF)),
    ROSE("Rose", Seeds(0xFFF472B6, 0xFFC084FC, 0xFF130A12, 0xFFBE185D, 0xFF7E22CE, 0xFFFCF3F8)),
    GRAPHITE("Graphite", Seeds(0xFFD4D4D8, 0xFFA1A1AA, 0xFF0E0E10, 0xFF3F3F46, 0xFF63636B, 0xFFF4F4F5)),
    /** Arctic frost blues on slate. */
    NORD("Nord", Seeds(0xFF88C0D0, 0xFFB48EAD, 0xFF101620, 0xFF3B6E8F, 0xFF7B4F72, 0xFFF0F3F7)),
    /** Moss and sage greens. */
    FOREST("Forest", Seeds(0xFF86C98A, 0xFFD6B370, 0xFF0A110B, 0xFF2E6B34, 0xFF7A5A12, 0xFFF2F7F1)),
    /** Purple and pink on deep violet. */
    DRACULA("Dracula", Seeds(0xFFBD93F9, 0xFFFF79C6, 0xFF120E1A, 0xFF6D3FC0, 0xFFAD1F75, 0xFFF6F2FC)),
    /** Warm yellow and cyan on deep teal, after Solarized. */
    SOLARIZED("Solarized", Seeds(0xFFE0B341, 0xFF4FB3C8, 0xFF001A21, 0xFF8A6100, 0xFF136C7F, 0xFFFBF6E9)),
    /** Soft lilac and periwinkle. */
    LAVENDER("Lavender", Seeds(0xFFC4B5FD, 0xFF93C5FD, 0xFF100D18, 0xFF6246C8, 0xFF2F5FB0, 0xFFF6F3FD)),
    /** Coral and gold, a sunset. */
    SUNSET("Sunset", Seeds(0xFFFF8A65, 0xFFFFD166, 0xFF160A0B, 0xFFB4401C, 0xFF8A5D00, 0xFFFDF4F0));

    /** Colour of this theme's chip in Settings. */
    val swatch: Color get() = Color(seeds?.darkPrimary ?: Accent.BLUE.dark)
}

/** Scheme for [palette] in light or dark. CLASSIC returns the original schemes untouched. */
internal fun paletteScheme(palette: Palette, dark: Boolean): ColorScheme {
    val s = palette.seeds ?: return if (dark) DarkColors else LightColors
    return if (dark) {
        darkPaletteScheme(Color(s.darkPrimary), Color(s.darkSecondary), Color(s.darkBackground), Color(s.lightPrimary))
    } else {
        lightPaletteScheme(Color(s.lightPrimary), Color(s.lightSecondary), Color(s.lightBackground), Color(s.darkPrimary))
    }
}

/** WCAG contrast ratio between two opaque colours, 1 (identical) to 21 (black on white). */
internal fun contrastRatio(a: Color, b: Color): Float {
    val l1 = a.luminance()
    val l2 = b.luminance()
    return (maxOf(l1, l2) + 0.05f) / (minOf(l1, l2) + 0.05f)
}

/** Black or white, whichever reads better on [background] (always at least 4.5:1). */
private fun readableOn(background: Color): Color =
    if (contrastRatio(Color.White, background) >= contrastRatio(Color.Black, background)) Color.White else Color.Black

/** [candidate] when it is readable on [background], else black or white. */
private fun readableTint(candidate: Color, background: Color): Color =
    if (contrastRatio(candidate, background) >= 4.5f) candidate else readableOn(background)

private fun darkPaletteScheme(primary: Color, secondary: Color, bg: Color, inversePrimary: Color): ColorScheme {
    val onSurface = lerp(bg, Color.White, 0.92f)
    val error = Color(0xFFFFB4AB)
    val primaryContainer = lerp(bg, primary, 0.32f)
    val secondaryContainer = lerp(bg, secondary, 0.32f)
    val errorContainer = lerp(bg, error, 0.32f)
    return darkColorScheme(
        primary = primary,
        onPrimary = readableOn(primary),
        primaryContainer = primaryContainer,
        onPrimaryContainer = readableTint(lerp(primary, Color.White, 0.85f), primaryContainer),
        inversePrimary = inversePrimary,
        secondary = secondary,
        onSecondary = readableOn(secondary),
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = readableTint(lerp(secondary, Color.White, 0.85f), secondaryContainer),
        tertiary = secondary,
        onTertiary = readableOn(secondary),
        tertiaryContainer = secondaryContainer,
        onTertiaryContainer = readableTint(lerp(secondary, Color.White, 0.85f), secondaryContainer),
        background = bg,
        onBackground = onSurface,
        surface = lerp(bg, onSurface, 0.04f),
        onSurface = onSurface,
        surfaceVariant = lerp(bg, onSurface, 0.10f),
        onSurfaceVariant = lerp(bg, Color.White, 0.72f),
        surfaceTint = primary,
        inverseSurface = onSurface,
        inverseOnSurface = bg,
        error = error,
        onError = Color(0xFF690005),
        errorContainer = errorContainer,
        onErrorContainer = readableTint(Color(0xFFFFDAD6), errorContainer),
        outline = lerp(bg, onSurface, 0.38f),
        outlineVariant = lerp(bg, onSurface, 0.16f),
        scrim = Color.Black,
        surfaceBright = lerp(bg, onSurface, 0.16f),
        surfaceDim = lerp(bg, Color.Black, 0.30f),
        surfaceContainerLowest = lerp(bg, Color.Black, 0.35f),
        surfaceContainerLow = lerp(bg, onSurface, 0.03f),
        surfaceContainer = lerp(bg, onSurface, 0.06f),
        surfaceContainerHigh = lerp(bg, onSurface, 0.09f),
        surfaceContainerHighest = lerp(bg, onSurface, 0.12f),
    )
}

private fun lightPaletteScheme(primary: Color, secondary: Color, bg: Color, inversePrimary: Color): ColorScheme {
    val onSurface = lerp(bg, Color.Black, 0.90f)
    val error = Color(0xFFBA1A1A)
    val primaryContainer = lerp(Color.White, primary, 0.20f)
    val secondaryContainer = lerp(Color.White, secondary, 0.20f)
    val errorContainer = lerp(Color.White, error, 0.14f)
    return lightColorScheme(
        primary = primary,
        onPrimary = readableOn(primary),
        primaryContainer = primaryContainer,
        onPrimaryContainer = readableTint(lerp(primary, Color.Black, 0.72f), primaryContainer),
        inversePrimary = inversePrimary,
        secondary = secondary,
        onSecondary = readableOn(secondary),
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = readableTint(lerp(secondary, Color.Black, 0.72f), secondaryContainer),
        tertiary = secondary,
        onTertiary = readableOn(secondary),
        tertiaryContainer = secondaryContainer,
        onTertiaryContainer = readableTint(lerp(secondary, Color.Black, 0.72f), secondaryContainer),
        background = bg,
        onBackground = onSurface,
        surface = Color.White,
        onSurface = onSurface,
        surfaceVariant = lerp(bg, onSurface, 0.07f),
        onSurfaceVariant = lerp(bg, Color.Black, 0.72f),
        surfaceTint = primary,
        inverseSurface = onSurface,
        inverseOnSurface = bg,
        error = error,
        onError = Color.White,
        errorContainer = errorContainer,
        onErrorContainer = readableTint(Color(0xFF410002), errorContainer),
        outline = lerp(bg, Color.Black, 0.28f),
        outlineVariant = lerp(bg, Color.Black, 0.14f),
        scrim = Color.Black,
        surfaceBright = Color.White,
        surfaceDim = lerp(bg, Color.Black, 0.10f),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = lerp(bg, Color.White, 0.50f),
        surfaceContainer = bg,
        surfaceContainerHigh = lerp(bg, onSurface, 0.04f),
        surfaceContainerHighest = lerp(bg, onSurface, 0.08f),
    )
}
