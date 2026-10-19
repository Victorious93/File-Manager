package ai.victorious.victorfiles.fleet

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Persisted fleet settings. External control defaults to OFF. */
class FleetSettings(context: Context) {
    private val prefs = context.getSharedPreferences("fleet", Context.MODE_PRIVATE)
    private val _externalControl = MutableStateFlow(prefs.getBoolean(KEY_EXTERNAL_CONTROL, false))
    val externalControl: StateFlow<Boolean> = _externalControl.asStateFlow()

    fun isExternalControlEnabled(): Boolean = _externalControl.value

    fun setExternalControlEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_EXTERNAL_CONTROL, enabled).apply()
        _externalControl.value = enabled
    }

    companion object {
        const val KEY_EXTERNAL_CONTROL = "external_control_enabled"
    }
}
