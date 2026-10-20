package ai.victorious.filemanager.priv

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Root / Shizuku toggles. Both default OFF. */
class PrivilegeSettings(context: Context) {
    private val prefs = context.getSharedPreferences("privilege", Context.MODE_PRIVATE)
    private val _root = MutableStateFlow(prefs.getBoolean(KEY_ROOT, false))
    private val _shizuku = MutableStateFlow(prefs.getBoolean(KEY_SHIZUKU, false))
    val rootEnabled: StateFlow<Boolean> = _root.asStateFlow()
    val shizukuEnabled: StateFlow<Boolean> = _shizuku.asStateFlow()

    fun isRootEnabled() = _root.value
    fun isShizukuEnabled() = _shizuku.value

    fun setRootEnabled(v: Boolean) {
        prefs.edit().putBoolean(KEY_ROOT, v).apply()
        _root.value = v
    }

    fun setShizukuEnabled(v: Boolean) {
        prefs.edit().putBoolean(KEY_SHIZUKU, v).apply()
        _shizuku.value = v
    }

    companion object {
        const val KEY_ROOT = "root_enabled"
        const val KEY_SHIZUKU = "shizuku_enabled"
    }
}
