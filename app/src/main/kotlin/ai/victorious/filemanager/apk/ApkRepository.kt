package ai.victorious.filemanager.apk

import ai.victorious.filemanager.core.apk.ApkSigning
import ai.victorious.filemanager.core.apk.AxmlParser
import ai.victorious.filemanager.core.apk.SigningKey
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.SecureRandom

data class ApkInfo(
    val path: String,
    val label: String?,
    val packageName: String?,
    val versionName: String?,
    val versionCode: Long?,
    val minSdk: Int?,
    val targetSdk: Int?,
    val permissions: List<String>,
    val installedVersion: String?,
    val signature: String,
    val sizeBytes: Long,
)

/** Reads APK metadata (PackageManager first, built-in AXML parser as fallback) and manages the debug key. */
class ApkRepository(private val context: Context) {
    fun info(apk: File): ApkInfo {
        val pm = context.packageManager
        val pi: PackageInfo? = runCatching {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_PERMISSIONS)
        }.getOrNull()
        val parsed = runCatching { AxmlParser.fromApk(apk) }.getOrNull()
        val appInfo = pi?.applicationInfo?.apply { sourceDir = apk.absolutePath; publicSourceDir = apk.absolutePath }
        val pkg = pi?.packageName ?: parsed?.packageName
        val installed = pkg?.let { p ->
            runCatching {
                val i = pm.getPackageInfo(p, 0)
                "${i.versionName} (${versionCodeOf(i)})"
            }.getOrNull()
        }
        val sig = runCatching {
            val r = ApkSigning.verify(apk, minSdk = 21)
            if (r.verified) listOfNotNull("v1".takeIf { r.v1 }, "v2".takeIf { r.v2 }, "v3".takeIf { r.v3 })
                .joinToString(" + ", prefix = "Verified: ").ifEmpty { "Verified" }
            else "Not verified (${r.errors.firstOrNull() ?: "unsigned"})"
        }.getOrElse { "Unknown (${it.message})" }
        return ApkInfo(
            path = apk.absolutePath,
            label = appInfo?.let { runCatching { pm.getApplicationLabel(it).toString() }.getOrNull() },
            packageName = pkg,
            versionName = pi?.versionName ?: parsed?.versionName,
            versionCode = pi?.let(::versionCodeOf) ?: parsed?.versionCode,
            minSdk = (if (Build.VERSION.SDK_INT >= 24) pi?.applicationInfo?.minSdkVersion else null) ?: parsed?.minSdk,
            targetSdk = pi?.applicationInfo?.targetSdkVersion ?: parsed?.targetSdk,
            permissions = (pi?.requestedPermissions?.toList() ?: parsed?.permissions.orEmpty()).distinct(),
            installedVersion = installed,
            signature = sig,
            sizeBytes = apk.length(),
        )
    }

    private fun versionCodeOf(i: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) i.longVersionCode else @Suppress("DEPRECATION") i.versionCode.toLong()

    /** Per-install debug keystore in private app storage; password is random and stays in private prefs. */
    fun debugKey(): SigningKey {
        val prefs = context.getSharedPreferences("apk_signing", Context.MODE_PRIVATE)
        val file = File(context.filesDir, "signing/debug.p12")
        var pass = prefs.getString("debug_pass", null)
        if (pass == null || !file.exists()) {
            val bytes = ByteArray(18).also { SecureRandom().nextBytes(it) }
            pass = bytes.joinToString("") { "%02x".format(it) }
            ApkSigning.generateDebugKeystore(file, pass.toCharArray())
            prefs.edit().putString("debug_pass", pass).apply()
        }
        return ApkSigning.loadKey(file, pass.toCharArray(), ApkSigning.DEBUG_ALIAS)
    }

    fun hasDebugKey(): Boolean = File(context.filesDir, "signing/debug.p12").exists()
}
