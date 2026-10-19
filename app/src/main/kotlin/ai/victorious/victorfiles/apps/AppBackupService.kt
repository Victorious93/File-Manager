package ai.victorious.victorfiles.apps

import ai.victorious.victorfiles.apk.ApkInstaller
import ai.victorious.victorfiles.core.apps.AppBackup
import ai.victorious.victorfiles.core.apps.AppBackupManifest
import ai.victorious.victorfiles.core.apps.BackupApkEntry
import ai.victorious.victorfiles.core.priv.PrivilegeBackend
import ai.victorious.victorfiles.core.shell.ShellCommands
import ai.victorious.victorfiles.priv.PrivilegeManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class InstalledAppInfo(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long,
    val isSystem: Boolean,
    val sizeBytes: Long,
    val installedEpochMs: Long,
    val apkPaths: List<File>,
    val enabled: Boolean,
)

class AppBackupService(
    private val context: Context,
    private val privilege: PrivilegeManager,
) {
    fun listInstalled(): List<InstalledAppInfo> {
        val pm = context.packageManager
        return pm.getInstalledApplications(0).mapNotNull { ai ->
            val base = File(ai.sourceDir ?: return@mapNotNull null)
            if (!base.isFile) return@mapNotNull null
            val splits = (ai.splitSourceDirs?.map(::File) ?: emptyList()).filter { it.isFile }
            val paths = listOf(base) + splits
            val pi = runCatching { pm.getPackageInfo(ai.packageName, 0) }.getOrNull()
            val vc = if (android.os.Build.VERSION.SDK_INT >= 28) pi?.longVersionCode ?: 0L else @Suppress("DEPRECATION") pi?.versionCode?.toLong() ?: 0L
            InstalledAppInfo(
                packageName = ai.packageName,
                label = ai.loadLabel(pm)?.toString() ?: ai.packageName,
                versionName = pi?.versionName,
                versionCode = vc,
                isSystem = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                sizeBytes = paths.sumOf { it.length() },
                installedEpochMs = pi?.firstInstallTime ?: 0L,
                apkPaths = paths,
                enabled = ai.enabled,
            )
        }.sortedBy { it.label.lowercase() }
    }

    /**
     * Backs up APK(s) into [destDir]/[folderName]/`.
     * If [asApksBundle], also writes a zip `.apks` of the APKs.
     * If [includeData] and root is active, tars `/data/data/<pkg>`.
     */
    fun backup(
        app: InstalledAppInfo,
        destDir: File,
        asApksBundle: Boolean = true,
        includeData: Boolean = false,
    ): File {
        val folder = File(destDir, AppBackup.folderName(app.packageName, app.versionCode)).apply { mkdirs() }
        val entries = ArrayList<BackupApkEntry>()
        app.apkPaths.forEachIndexed { i, apk ->
            val name = if (i == 0) "base.apk" else apk.name
            apk.copyTo(File(folder, name), overwrite = true)
            entries += BackupApkEntry(name, AppBackup.roleFor(name, i))
        }
        var dataName: String? = null
        if (includeData) {
            if (privilege.backend() != PrivilegeBackend.ROOT) {
                throw IllegalStateException("App data backup requires Root (enable in Settings)")
            }
            dataName = AppBackup.dataArchiveName(app.packageName)
            val tar = File(folder, dataName)
            val r = privilege.execElevated(ShellCommands.packageDataBackup(app.packageName, tar.absolutePath))
            if (!r.ok) throw IllegalStateException(r.stderr.ifBlank { "data backup failed" })
        }
        val manifest = AppBackupManifest(
            packageName = app.packageName,
            label = app.label,
            versionName = app.versionName,
            versionCode = app.versionCode,
            backedUpAtEpochMs = System.currentTimeMillis(),
            includeData = includeData,
            apks = entries,
            dataArchive = dataName,
        )
        File(folder, "manifest.json").writeText(AppBackup.writeManifest(manifest))
        if (asApksBundle && app.apkPaths.size > 1) {
            val bundle = File(folder, AppBackup.apksBundleName(app.packageName))
            ZipOutputStream(bundle.outputStream()).use { z ->
                app.apkPaths.forEachIndexed { i, apk ->
                    val name = if (i == 0) "base.apk" else apk.name
                    z.putNextEntry(ZipEntry(name))
                    apk.inputStream().use { it.copyTo(z) }
                    z.closeEntry()
                }
            }
        }
        return folder
    }

    fun readBackup(folder: File): AppBackupManifest =
        AppBackup.readManifest(File(folder, "manifest.json").readText())

    fun restoreApks(folder: File): String {
        val m = readBackup(folder)
        val apks = m.apks.map { File(folder, it.name) }.filter { it.isFile }
        require(apks.isNotEmpty()) { "No APKs in backup" }
        return if (apks.size == 1) {
            ApkInstaller.install(context, apks)
        } else {
            ApkInstaller.installSplits(context, apks)
        }
    }

    fun restoreData(folder: File) {
        val m = readBackup(folder)
        val name = m.dataArchive ?: error("Backup has no data archive")
        if (privilege.backend() != PrivilegeBackend.ROOT) error("Data restore requires Root")
        val tar = File(folder, name)
        require(tar.isFile) { "Missing $name" }
        val r = privilege.execElevated(ShellCommands.packageDataRestore(m.packageName, tar.absolutePath))
        if (!r.ok) error(r.stderr.ifBlank { "data restore failed" })
    }

    fun openApp(packageName: String) {
        val launch = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: error("No launcher activity")
        context.startActivity(launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun appInfoSettings(packageName: String) {
        val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(android.net.Uri.parse("package:$packageName"))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
