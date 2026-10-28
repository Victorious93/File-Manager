package ai.victorious.filemanager.plugins

import ai.victorious.filemanager.core.plugins.AppPluginContract
import ai.victorious.filemanager.core.plugins.AppPluginDescriptor
import ai.victorious.filemanager.core.plugins.AppPluginParser
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import java.io.File

/** An installed APK plugin (API v2). [problem] is set when its descriptor is invalid; it then can't be enabled. */
data class AppPlugin(
    val component: ComponentName,
    val label: String,
    val descriptor: AppPluginDescriptor?,
    val problem: String?,
    val enabled: Boolean,
) { val key: String get() = component.flattenToShortString() }

/** Discovers APK plugins via [AppPluginContract.ACTION] and launches them with per-file URI grants. */
class AppPluginHost(private val context: Context) {
    private val prefs = context.getSharedPreferences("app_plugins", Context.MODE_PRIVATE)

    fun list(): List<AppPlugin> {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val found = pm.queryIntentActivities(Intent(AppPluginContract.ACTION), PackageManager.GET_META_DATA)
        return found.mapNotNull { ri ->
            val ai = ri.activityInfo ?: return@mapNotNull null
            if (!ai.exported) return@mapNotNull null
            val cn = ComponentName(ai.packageName, ai.name)
            val raw = ai.metaData?.get(AppPluginContract.META_DATA)
            val text = when (raw) {
                is String -> raw
                is Int -> runCatching { pm.getResourcesForApplication(ai.applicationInfo).getString(raw) }.getOrNull()
                else -> null
            }
            val parsed = text?.let { runCatching { AppPluginParser.parse(it) } }
            val desc = parsed?.getOrNull()
            val problem = when {
                text == null -> "Missing <meta-data ${AppPluginContract.META_DATA}>"
                desc == null -> parsed?.exceptionOrNull()?.message
                else -> null
            }
            AppPlugin(cn, ri.loadLabel(pm).toString(), desc, problem, desc != null && prefs.getBoolean("enabled.${cn.flattenToShortString()}", false))
        }.sortedBy { it.label.lowercase() }
    }

    fun setEnabled(p: AppPlugin, enabled: Boolean) { prefs.edit().putBoolean("enabled.${p.key}", enabled).apply() }

    /** Builds the launch intent; throws with a user-readable reason if the plugin can't run. */
    fun launchIntent(p: AppPlugin, paths: List<String>): Intent {
        val d = requireNotNull(p.descriptor) { p.problem ?: "Invalid plugin" }
        require(p.enabled) { "Enable ${p.label} first" }
        AppPluginParser.whyNot(d, paths)?.let { throw IllegalArgumentException(it) }
        val files = paths.map(::File)
        require(files.all { it.isFile }) { "Plugins receive files only" }
        val authority = "${context.packageName}.files"
        val uris = files.map { FileProvider.getUriForFile(context, authority, it) }
        var flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (d.wantsWrite) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        return Intent(AppPluginContract.ACTION).apply {
            component = p.component
            addFlags(flags)
            putExtra(AppPluginContract.EXTRA_API_VERSION, AppPluginContract.API_VERSION)
            putExtra(AppPluginContract.EXTRA_DISPLAY_NAMES, files.map { it.name }.toTypedArray())
            if (uris.isNotEmpty()) {
                val clip = ClipData.newRawUri(files[0].name, uris[0])
                uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                clipData = clip
                if (uris.size == 1) data = uris[0]
            }
        }
    }
}
