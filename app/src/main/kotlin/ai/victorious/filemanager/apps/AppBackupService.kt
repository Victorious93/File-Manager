package ai.victorious.filemanager.apps

import ai.victorious.filemanager.apk.ApkInstaller
import ai.victorious.filemanager.core.apps.AppBackup
import ai.victorious.filemanager.core.apps.AppBackupManifest
import ai.victorious.filemanager.core.apps.BackupApkEntry
import ai.victorious.filemanager.core.apps.BackupCrypto
import ai.victorious.filemanager.core.priv.PrivilegeBackend
import ai.victorious.filemanager.core.shell.ShellCommands
import ai.victorious.filemanager.priv.PrivilegeManager
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
        /** When set, every APK / data / bundle file is stored encrypted (VFBE1, see BackupCrypto). Never stored or logged. */
        passphrase: CharArray? = null,
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
            encryption = if (passphrase != null) BackupCrypto.FORMAT else null,
        )
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
        if (passphrase != null) {
            // Encrypt everything except the manifest (it only names the app and its files).
            try {
                folder.listFiles().orEmpty().filter { it.isFile && it.name != "manifest.json" }.forEach { BackupCrypto.encryptFile(it, passphrase) }
            } catch (e: Throwable) {
                folder.deleteRecursively() // never leave a half-encrypted backup behind
                throw e
            }
        }
        File(folder, "manifest.json").writeText(AppBackup.writeManifest(manifest))
        return folder
    }

    fun isEncrypted(folder: File): Boolean = readBackup(folder).encryption != null

    /** Decrypts [name] from an encrypted backup into a private temp folder; the caller deletes it. */
    private fun decryptToTemp(folder: File, name: String, passphrase: CharArray, tmp: File): File {
        val src = File(folder, name + BackupCrypto.EXTENSION)
        require(src.isFile) { "Missing ${src.name}" }
        return BackupCrypto.decryptFile(src, File(tmp, name), passphrase)
    }

    /** Private temp folder for decrypted files (app cache; the app's own installer session and root can read it). Deleted after use. */
    private fun tempDir(): File = File(context.cacheDir, "backup-restore-${System.nanoTime()}").apply { mkdirs() }

    fun readBackup(folder: File): AppBackupManifest =
        AppBackup.readManifest(File(folder, "manifest.json").readText())

    fun restoreApks(folder: File, passphrase: CharArray? = null): String {
        val m = readBackup(folder)
        if (m.encryption != null) {
            requireNotNull(passphrase) { "This backup is encrypted: enter its passphrase" }
            val tmp = tempDir()
            try {
                val apks = m.apks.map { decryptToTemp(folder, it.name, passphrase, tmp) }
                require(apks.isNotEmpty()) { "No APKs in backup" }
                // The PackageInstaller session copies the bytes before returning, so the temp copies can go right after.
                return if (apks.size == 1) installNow(apks) else ApkInstaller.installSplits(context, apks)
            } finally { tmp.deleteRecursively() }
        }
        val apks = m.apks.map { File(folder, it.name) }.filter { it.isFile }
        require(apks.isNotEmpty()) { "No APKs in backup" }
        return if (apks.size == 1) {
            ApkInstaller.install(context, apks)
        } else {
            ApkInstaller.installSplits(context, apks)
        }
    }

    /** A single decrypted APK goes through the split-session path too: it writes the bytes immediately (the normal queue reads later). */
    private fun installNow(apks: List<File>) = ApkInstaller.installSplits(context, apks)

    fun restoreData(folder: File, passphrase: CharArray? = null) {
        val m = readBackup(folder)
        val name = m.dataArchive ?: error("Backup has no data archive")
        if (privilege.backend() != PrivilegeBackend.ROOT) error("Data restore requires Root")
        // App data is private: decrypt it to internal cache only (root can read it there).
        val tmp = if (m.encryption != null) tempDir() else null
        try {
            val tar = if (tmp != null) decryptToTemp(folder, name, requireNotNull(passphrase) { "This backup is encrypted: enter its passphrase" }, tmp) else File(folder, name)
            require(tar.isFile) { "Missing $name" }
            val r = privilege.execElevated(ShellCommands.packageDataRestore(m.packageName, tar.absolutePath))
            if (!r.ok) error(r.stderr.ifBlank { "data restore failed" })
        } finally { tmp?.deleteRecursively() }
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
