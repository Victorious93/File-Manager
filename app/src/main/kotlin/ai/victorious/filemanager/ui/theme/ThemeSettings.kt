package ai.victorious.filemanager.ui.theme

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
    /** Show two file panes at once. Off by default: one list on screen, Copy/Move use Paste. */
    val dualPane: Boolean = false,
    /** Colour theme. [Accent] only applies to [Palette.CLASSIC]; dynamic color overrides every palette. */
    val palette: Palette = Palette.CLASSIC,
    /** File icon and navigation icon family. */
    val iconStyle: IconStyle = IconStyle.OUTLINED,
    /** Show folders as a grid of tiles instead of a list. */
    val gridView: Boolean = false,
    /** On expanded-width windows (tablets, unfolded foldables) show both panes side by side automatically. */
    val autoDualOnWide: Boolean = true,
    /** Open on the Home dashboard instead of the file list. */
    val startOnHome: Boolean = false,
)

/** Both file panes are on screen: the user turned them on, or the window is wide enough and they allow it. */
fun ThemePrefs.effectiveDualPane(expandedWindow: Boolean): Boolean = dualPane || (autoDualOnWide && expandedWindow)

/** Persists [ThemePrefs] (appearance and layout) in plain SharedPreferences (nothing secret) and exposes them as a flow. */
class ThemeSettings(context: Context) {
    private val prefs = context.getSharedPreferences("fm_theme", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<ThemePrefs> = _state

    /** Set by the UI from the current window size class (not persisted). The ViewModel reads it for "the other pane". */
    val expandedWindow = MutableStateFlow(false)

    val dualPaneNow: Boolean get() = _state.value.effectiveDualPane(expandedWindow.value)

    private fun read() = ThemePrefs(
        mode = enumOr(prefs.getString("mode", null), ThemeMode.SYSTEM),
        dynamicColor = prefs.getBoolean("dynamic", false),
        accent = enumOr(prefs.getString("accent", null), Accent.BLUE),
        dualPane = prefs.getBoolean("dual_pane", false),
        palette = enumOr(prefs.getString("palette", null), Palette.CLASSIC),
        iconStyle = enumOr(prefs.getString("icon_style", null), IconStyle.OUTLINED),
        gridView = prefs.getBoolean("grid_view", false),
        autoDualOnWide = prefs.getBoolean("auto_dual_wide", true),
        startOnHome = prefs.getBoolean("start_home", false),
    )

    /** Re-reads SharedPreferences (after a settings import). */
    fun reload() {
        _state.value = read()
    }

    fun update(transform: (ThemePrefs) -> ThemePrefs) {
        val next = transform(_state.value)
        prefs.edit()
            .putString("mode", next.mode.name)
            .putBoolean("dynamic", next.dynamicColor)
            .putString("accent", next.accent.name)
            .putBoolean("dual_pane", next.dualPane)
            .putString("palette", next.palette.name)
            .putString("icon_style", next.iconStyle.name)
            .putBoolean("grid_view", next.gridView)
            .putBoolean("auto_dual_wide", next.autoDualOnWide)
            .putBoolean("start_home", next.startOnHome)
            .apply()
        _state.value = next
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default
}
