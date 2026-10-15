package ai.victorious.filemanager.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Teal = Color(0xFF2BB5A0)
private val Blue = Color(0xFF3D7DFF)

private val DarkColors = darkColorScheme(
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

private val LightColors = lightColorScheme(
    primary = Blue,
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

@Composable
fun VictoriousTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
