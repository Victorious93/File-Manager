package ai.victorious.filemanager.apk

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.util.ArrayDeque

/**
 * Installs APKs through the PackageInstaller session API. Every install is confirmed by the
 * user in the system dialog (STATUS_PENDING_USER_ACTION). Batches are installed one after
 * another: the next session is committed when the previous one reports a final status.
 */
object ApkInstaller {
    const val ACTION_RESULT = "ai.victorious.filemanager.action.INSTALL_RESULT"
    const val EXTRA_NAME = "apk_name"
    private val pending = ArrayDeque<File>()

    /** Android O+ requires the user to allow this app as an unknown-sources installer. */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesSettings(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Installs [apks] together in one PackageInstaller session (split APKs / bundles).
     * All APKs must belong to the same package. User confirms once.
     */
    fun installSplits(context: Context, apks: List<File>): String {
        if (apks.isEmpty()) return "No APKs"
        if (!canInstall(context)) {
            context.startActivity(unknownSourcesSettings(context))
            return "Allow \"Install unknown apps\" for this app, then try again"
        }
        try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            params.setSize(apks.sumOf { it.length() })
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                apks.forEachIndexed { i, apk ->
                    apk.inputStream().use { input ->
                        session.openWrite("split_$i.apk", 0, apk.length()).use { out ->
                            input.copyTo(out); session.fsync(out)
                        }
                    }
                }
                val intent = Intent(context, InstallResultReceiver::class.java)
                    .setAction(ACTION_RESULT).putExtra(EXTRA_NAME, apks.first().name + " +" + (apks.size - 1) + " splits")
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
            }
            return "Installing ${apks.size} splits together…"
        } catch (e: Exception) {
            return "Split install failed: ${e.message}"
        }
    }

    /** Queues [apks] and starts installing one-by-one. Returns a user-facing status string. */
    @Synchronized
    fun install(context: Context, apks: List<File>): String {
        if (apks.isEmpty()) return "No APKs selected"
        if (!canInstall(context)) {
            context.startActivity(unknownSourcesSettings(context))
            return "Allow \"Install unknown apps\" for this app, then try again"
        }
        val idle = pending.isEmpty()
        pending.addAll(apks)
        if (idle) commitNext(context.applicationContext)
        return if (apks.size == 1) "Installing ${apks.first().name}…" else "Installing ${apks.size} APKs one by one…"
    }

    @Synchronized
    internal fun onFinished(context: Context) {
        if (pending.isNotEmpty()) pending.removeFirst()
        if (pending.isNotEmpty()) commitNext(context)
    }

    private fun commitNext(context: Context) {
        val apk = pending.peekFirst() ?: return
        try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            params.setSize(apk.length())
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val intent = Intent(context, InstallResultReceiver::class.java)
                    .setAction(ACTION_RESULT).putExtra(EXTRA_NAME, apk.name)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pi = PendingIntent.getBroadcast(context, id, intent, flags)
                session.commit(pi.intentSender)
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Install of ${apk.name} failed: ${e.message}", Toast.LENGTH_LONG).show()
            onFinished(context)
        }
    }

    /** Opens the system uninstall confirmation for [packageName]. */
    fun uninstall(context: Context, packageName: String) {
        @Suppress("DEPRECATION")
        val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:$packageName"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    /** Share / export one or more files via the system share sheet. */
    fun share(context: Context, files: List<File>) {
        if (files.isEmpty()) return
        val apkOnly = files.all { it.extension.equals("apk", true) }
        val mime = if (apkOnly) "application/vnd.android.package-archive" else "*/*"
        val uris = ArrayList(files.map { uriFor(context, it) })
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }.setType(mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Share / export").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val name = intent.getStringExtra(ApkInstaller.EXTRA_NAME) ?: "APK"
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return // final status arrives in a later broadcast
            }
            PackageInstaller.STATUS_SUCCESS -> toast(context, "Installed $name")
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"
                toast(context, "Install of $name failed: $msg")
            }
        }
        ApkInstaller.onFinished(context.applicationContext)
    }

    private fun toast(c: Context, m: String) = Toast.makeText(c, m, Toast.LENGTH_LONG).show()
}
