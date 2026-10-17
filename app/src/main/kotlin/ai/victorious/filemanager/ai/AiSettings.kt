package ai.victorious.filemanager.ai

import ai.victorious.filemanager.core.ai.AiEndpoint
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * AI assistant configuration. Everything (including base URL and model) lives in
 * EncryptedSharedPreferences backed by an Android Keystore master key. Off by default;
 * nothing is bundled. The key is only read when a request is about to be sent.
 */
class AiSettings(context: Context) {
    private val app = context.applicationContext
    private val prefs: SharedPreferences by lazy {
        val master = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            app,
            "fm_ai_settings",
            master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun load() {
        _enabled.value = runCatching { prefs.getBoolean(KEY_ENABLED, false) }.getOrDefault(false)
    }

    fun setEnabled(v: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, v).apply()
        _enabled.value = v
    }

    val baseUrl: String get() = prefs.getString(KEY_URL, "").orEmpty()
    val model: String get() = prefs.getString(KEY_MODEL, "").orEmpty()
    val hasApiKey: Boolean get() = !prefs.getString(KEY_API, null).isNullOrEmpty()

    fun apiKey(): String = prefs.getString(KEY_API, "").orEmpty()

    fun save(baseUrl: String, model: String, apiKey: String?) {
        prefs.edit().apply {
            putString(KEY_URL, baseUrl.trim())
            putString(KEY_MODEL, model.trim())
            // Blank = keep the existing key; the UI never displays the stored key.
            if (!apiKey.isNullOrBlank()) putString(KEY_API, apiKey.trim())
        }.apply()
    }

    fun clearKey() {
        prefs.edit().remove(KEY_API).apply()
    }

    /** Null when not configured; throws for a malformed URL so the UI can show the reason. */
    fun endpoint(): AiEndpoint? {
        if (!enabled.value || baseUrl.isBlank() || model.isBlank()) return null
        return AiEndpoint(baseUrl, model).also { it.chatCompletionsUrl }
    }

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_URL = "base_url"
        private const val KEY_MODEL = "model"
        private const val KEY_API = "api_key"
    }
}
