package ai.victorious.filemanager.ui.theme.fleet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Reads VictorLaunch's fleet theme. Never writes the provider. Before every query it checks that the
 * authority resolves to [FleetThemeContract.PROVIDER_PACKAGE] signed by a pinned cert. Any failure
 * (missing app, SecurityException, bad payload) becomes [FleetThemeState.Unavailable] and the UI keeps the
 * user's own theme.
 */
class FleetThemeClient(context: Context) {
    private val app = context.applicationContext
    private val uri: Uri = Uri.parse(FleetThemeContract.URI)
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "fleet-theme").apply { isDaemon = true } }
    private val _state = MutableStateFlow<FleetThemeState>(FleetThemeState.Unavailable("not read yet"))
    val state: StateFlow<FleetThemeState> = _state.asStateFlow()

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = refresh()
    }

    fun start() {
        runCatching { app.contentResolver.registerContentObserver(uri, false, observer) }
            .onFailure { Log.w(TAG, "fleet theme observer not registered", it) }
        refresh()
    }

    /** Re-query off the main thread (from start, the observer, or the broadcast). */
    fun refresh() {
        io.execute {
            val next = fleetStateFor(runCatching { read() })
            if (next is FleetThemeState.Unavailable) Log.i(TAG, next.reason)
            _state.value = next
        }
    }

    private fun read(): FleetThemeResult? {
        val pm = app.packageManager
        @Suppress("DEPRECATION")
        val info = pm.resolveContentProvider(FleetThemeContract.AUTHORITY, 0) ?: return null
        if (!FleetThemeContract.isTrustedProvider(info.packageName, signerDigests(pm, info.packageName))) {
            throw SecurityException("fleet theme provider ${info.packageName} is not the pinned VictorLaunch")
        }
        val cursor = app.contentResolver.query(uri, null, null, null, null) ?: return null
        return cursor.use { c -> if (c.moveToFirst()) FleetThemeContract.parseRow(c.rowMap()) else FleetThemeResult.Invalid("empty") }
    }

    private fun Cursor.rowMap(): Map<String, Any?> = columnNames.indices.associate { i ->
        columnNames[i] to when (getType(i)) {
            Cursor.FIELD_TYPE_INTEGER -> getLong(i)
            Cursor.FIELD_TYPE_STRING -> getString(i)
            Cursor.FIELD_TYPE_FLOAT -> getDouble(i)
            else -> null
        }
    }

    companion object {
        private const val TAG = "FleetTheme"

        /** SHA-256 of the package's current signers (not the rotation history, so a revoked old key never matches). */
        @Suppress("DEPRECATION")
        fun signerDigests(pm: PackageManager, pkg: String): List<String> = runCatching {
            val sigs: Array<Signature> = if (Build.VERSION.SDK_INT >= 28) {
                val si = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo ?: return emptyList()
                if (si.hasMultipleSigners()) si.apkContentsSigners else arrayOf(si.signingCertificateHistory.last())
            } else {
                pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
            } ?: emptyArray()
            sigs.map { s -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString(":") { "%02X".format(it) } }
        }.getOrDefault(emptyList())
    }
}

/**
 * VictorLaunch sends FLEET_THEME_CHANGED explicitly to our package. The extras are only a wake-up and
 * are not trusted: we re-query, and the query does the package + cert check.
 */
class FleetThemeChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != FleetThemeContract.ACTION_CHANGED) return
        (context.applicationContext as? ai.victorious.filemanager.FileManagerApplication)?.fleetTheme?.refresh()
    }
}
