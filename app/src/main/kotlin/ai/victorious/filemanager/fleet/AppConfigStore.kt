package ai.victorious.filemanager.fleet

import ai.victorious.filemanager.core.bookmarks.Bookmark
import ai.victorious.filemanager.core.bookmarks.BookmarkStore
import ai.victorious.filemanager.core.fleet.FleetBackupAead
import ai.victorious.filemanager.core.fleet.JceFleetBackupAead
import ai.victorious.filemanager.core.fleet.NodeConfigStore
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Real config store for `config.export` / `config.import`: appearance preferences
 * (SharedPreferences `fm_theme`) + the bookmark repository. Deliberately excludes the
 * privilege, AI (API key), cloud, connection and fleet preference files.
 */
class AppConfigStore(
    context: Context,
    private val bookmarksStore: BookmarkStore,
    private val fleet: FleetSettings,
    private val onPrefsChanged: () -> Unit,
) : NodeConfigStore {
    private val theme = context.getSharedPreferences(THEME_FILE, Context.MODE_PRIVATE)

    override fun preferences(): Map<String, JsonPrimitive> = theme.all.mapNotNull { (k, v) ->
        when (v) {
            is String -> "$THEME_FILE.$k" to JsonPrimitive(v)
            is Boolean -> "$THEME_FILE.$k" to JsonPrimitive(v)
            is Int -> "$THEME_FILE.$k" to JsonPrimitive(v)
            is Long -> "$THEME_FILE.$k" to JsonPrimitive(v)
            is Float -> "$THEME_FILE.$k" to JsonPrimitive(v)
            else -> null
        }
    }.toMap()

    override fun bookmarks(): List<Bookmark> = bookmarksStore.list()

    override fun replacePreferences(prefs: Map<String, JsonPrimitive>) {
        val e = theme.edit()
        for ((key, v) in prefs) {
            if (!key.startsWith("$THEME_FILE.")) continue // only known files are ever written
            val k = key.removePrefix("$THEME_FILE.")
            when {
                v.isString -> e.putString(k, v.content)
                v.booleanOrNull != null -> e.putBoolean(k, v.booleanOrNull!!)
                v.content.toIntOrNull() != null -> e.putInt(k, v.content.toInt())
                v.content.toFloatOrNull() != null -> e.putFloat(k, v.content.toFloat())
            }
        }
        e.apply()
        onPrefsChanged()
    }

    override fun replaceBookmarks(bookmarks: List<Bookmark>) = bookmarksStore.replaceAll(bookmarks)

    override fun setExternalControlEnabled(enabled: Boolean) = fleet.setExternalControlEnabled(enabled)

    companion object {
        const val THEME_FILE = "fm_theme"
    }
}

/**
 * AES-256-GCM key in the Android Keystore (`android-keystore` wrapping). The key never leaves
 * the device and its alias is not written into bundles, so an encrypted export restores on
 * this device/install only.
 */
object BackupKeystore {
    private const val ALIAS = "victorfiles_config_backup_v1"

    fun aead(): FleetBackupAead = JceFleetBackupAead(key())

    @Synchronized
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // The frozen record format carries our own random 12-byte IV.
                .setRandomizedEncryptionRequired(false)
                .build(),
        )
        return gen.generateKey()
    }
}
