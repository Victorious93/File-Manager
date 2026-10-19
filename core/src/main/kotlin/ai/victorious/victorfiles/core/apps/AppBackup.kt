package ai.victorious.victorfiles.core.apps

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class BackupApkEntry(val name: String, val role: String) // base | split | other

@Serializable
data class AppBackupManifest(
    val version: Int = 1,
    val packageName: String,
    val label: String,
    val versionName: String? = null,
    val versionCode: Long? = null,
    val backedUpAtEpochMs: Long,
    val includeData: Boolean = false,
    val apks: List<BackupApkEntry>,
    val dataArchive: String? = null,
)

object AppBackup {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val stampFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /** Folder name: `<package>_<versionCode>_<utcStamp>`. */
    fun folderName(packageName: String, versionCode: Long?, atMs: Long = System.currentTimeMillis()): String {
        val safePkg = packageName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val vc = versionCode?.toString() ?: "0"
        return safePkg + "_" + vc + "_" + stampFmt.format(Date(atMs))
    }

    fun singleApkName(packageName: String): String = packageName.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".apk"

    fun apksBundleName(packageName: String): String = packageName.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".apks"

    fun dataArchiveName(packageName: String): String = packageName.replace(Regex("[^A-Za-z0-9._-]"), "_") + "_data.tar"

    fun writeManifest(m: AppBackupManifest): String = json.encodeToString(m)

    fun readManifest(text: String): AppBackupManifest = json.decodeFromString(text)

    fun roleFor(fileName: String, index: Int): String = when {
        fileName.equals("base.apk", true) || index == 0 && fileName.endsWith(".apk", true) -> "base"
        fileName.contains("split", true) || fileName.contains("config.", true) -> "split"
        else -> "other"
    }

    /** Sort: user apps first optional; then by label/size/install. Pure comparator helpers. */
    enum class Sort { NAME, SIZE, INSTALLED }

    data class AppRow(
        val packageName: String,
        val label: String,
        val isSystem: Boolean,
        val sizeBytes: Long,
        val installedEpochMs: Long,
    )

    fun filterAndSort(
        apps: List<AppRow>,
        query: String,
        userOnly: Boolean,
        systemOnly: Boolean,
        sort: Sort,
        ascending: Boolean = true,
    ): List<AppRow> {
        var list = apps.asSequence()
        if (userOnly) list = list.filter { !it.isSystem }
        if (systemOnly) list = list.filter { it.isSystem }
        val q = query.trim()
        if (q.isNotEmpty()) {
            list = list.filter {
                it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true)
            }
        }
        val sorted = when (sort) {
            Sort.NAME -> list.sortedBy { it.label.lowercase() }
            Sort.SIZE -> list.sortedBy { it.sizeBytes }
            Sort.INSTALLED -> list.sortedBy { it.installedEpochMs }
        }.toList()
        return if (ascending) sorted else sorted.asReversed()
    }
}
