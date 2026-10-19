package ai.victorious.victorfiles.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark"), AMOLED("AMOLED black") }

/** Accent presets used when dynamic color is off (or unavailable below Android 12). */
enum class Accent(val label: String, val light: Long, val dark: Long) {
    BLUE("Blue", 0xFF3D7DFF, 0xFF7AB8FF),
    TEAL("Teal", 0xFF0F766E, 0xFF5EEAD4),
    PURPLE("Purple", 0xFF7C3AED, 0xFFC4B5FD),
    ORANGE("Orange", 0xFFC2410C, 0xFFFDBA74),
    PINK("Pink", 0xFFBE185D, 0xFFF9A8D4),
    GREEN("Green", 0xFF15803D, 0xFF86EFAC),
}

data class ThemePrefs(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val accent: Accent = Accent.BLUE,
)

/** Persists [ThemePrefs] in plain SharedPreferences (nothing secret) and exposes them as a flow. */
class ThemeSettings(context: Context) {
    private val prefs = context.getSharedPreferences("fm_theme", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<ThemePrefs> = _state

    private fun read() = ThemePrefs(
        mode = enumOr(prefs.getString("mode", null), ThemeMode.SYSTEM),
        dynamicColor = prefs.getBoolean("dynamic", false),
        accent = enumOr(prefs.getString("accent", null), Accent.BLUE),
    )

    fun update(transform: (ThemePrefs) -> ThemePrefs) {
        val next = transform(_state.value)
        prefs.edit()
            .putString("mode", next.mode.name)
            .putBoolean("dynamic", next.dynamicColor)
            .putString("accent", next.accent.name)
            .apply()
        _state.value = next
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default
}
