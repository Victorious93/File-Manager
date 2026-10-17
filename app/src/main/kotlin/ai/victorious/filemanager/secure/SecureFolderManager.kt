package ai.victorious.filemanager.secure

import ai.victorious.filemanager.core.secure.SecureVault
import ai.victorious.filemanager.core.secure.VaultCrypto
import ai.victorious.filemanager.core.secure.VaultEntry
import ai.victorious.filemanager.core.secure.VaultLock
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Secure folder: files are AES-256-GCM encrypted at rest with a non-exportable Android
 * Keystore key that requires user authentication (biometric or device credential) to use.
 * Blobs live in app-private storage (no other app or the media scanner can reach them) under
 * random names, with an encrypted index for real names.
 */
class SecureFolderManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("secure_folder", Context.MODE_PRIVATE)
    private val root = File(context.noBackupFilesDir, "secure_vault")

    // Hard cap a little under the Keystore auth window so the UI re-prompts before the key
    // itself starts throwing UserNotAuthenticatedException mid-operation.
    val lock = VaultLock(timeoutMs = { timeoutSec * 1000L }, maxSessionMs = { (KEY_VALIDITY_SEC - 10) * 1000L })

    var timeoutSec: Int
        get() = prefs.getInt("timeout_sec", 60)
        set(v) { prefs.edit().putInt("timeout_sec", v.coerceIn(0, 3600)).apply() }

    private val vault = SecureVault(root, ::key, factory = { k ->
        // Keystore keys require the provider to choose the IV (randomized encryption).
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, k) }
    })

    fun canAuthenticate(): Int = BiometricManager.from(context).canAuthenticate(AUTHENTICATORS)

    fun authStatusMessage(): String? = when (canAuthenticate()) {
        BiometricManager.BIOMETRIC_SUCCESS -> null
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "Set up a screen lock or fingerprint to use the secure folder."
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE, BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
            "No biometric hardware; a device PIN/pattern/password is needed."
        else -> "Device authentication is not available."
    }

    /** Shows the system prompt; on success the Keystore key is usable for its validity window. */
    fun authenticate(activity: FragmentActivity, onResult: (Boolean, String?) -> Unit) {
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                lock.unlock()
                onResult(true, null)
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onResult(false, errString.toString())
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock secure folder")
            .setSubtitle("Biometric or device credential")
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
        prompt.authenticate(info)
    }

    private fun requireUnlocked() {
        check(lock.isUnlocked) { "Secure folder is locked" }
        lock.touch()
    }

    /** Runs a Keystore-backed op; if the key's auth window lapsed anyway, relock with a clear message. */
    private fun <T> keyed(block: () -> T): T = try {
        block()
    } catch (e: android.security.keystore.UserNotAuthenticatedException) {
        lock.lock()
        throw IllegalStateException("Authentication expired — unlock the secure folder again", e)
    }

    fun list(): List<VaultEntry> { requireUnlocked(); return keyed { vault.list() } }

    /** Move-in: encrypt then delete the original. */
    fun moveIn(paths: List<String>): Int {
        requireUnlocked()
        var n = 0
        for (p in paths) {
            val f = File(p)
            if (!f.isFile) continue
            keyed { vault.add(f) }
            if (!f.delete()) throw IllegalStateException("Encrypted ${f.name} but could not delete the original")
            n++
        }
        return n
    }

    fun moveOut(id: String, destDir: String): File { requireUnlocked(); return keyed { vault.export(id, File(destDir), remove = true) } }

    fun delete(id: String) { requireUnlocked(); keyed { vault.delete(id) } }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(KEY_VALIDITY_SEC, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                } else {
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(KEY_VALIDITY_SEC)
                }
            }
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
    }

    companion object {
        private const val ALIAS = "fm_secure_folder_v1"
        /** Keystore-side window after an unlock; the app-side VaultLock usually locks sooner. */
        private const val KEY_VALIDITY_SEC = 300
        // BiometricPrompt rejects BIOMETRIC_STRONG|DEVICE_CREDENTIAL below API 30; there the
        // time-bound Keystore key accepts any lock-screen auth, so WEAK|DEVICE_CREDENTIAL is used.
        val AUTHENTICATORS: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        else androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or DEVICE_CREDENTIAL
    }
}
