package ai.victorious.filemanager.fleet

import ai.victorious.filemanager.FileManagerApplication
import ai.victorious.filemanager.R
import ai.victorious.filemanager.core.fleet.ConfirmationBroker
import ai.victorious.filemanager.ui.theme.VictoriousTheme
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.delay

/** Posts / clears the "remote request waiting" notification with a full-screen intent. */
class RemoteConfirmNotifier(private val context: Context) {
    private val nm = NotificationManagerCompat.from(context)

    init {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "Remote control requests", NotificationManager.IMPORTANCE_HIGH)
            ch.description = "A fleet controller asks you to approve an action on this device"
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    fun onPending(list: List<ConfirmationBroker.Pending>) {
        if (list.isEmpty()) { nm.cancel(NOTIF_ID); return }
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val p = list.first()
        val intent = Intent(context, RemoteConfirmActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Approve remote request?")
            .setContentText("${p.callerPackage}: ${p.actionRef}")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(false)
            .setTimeoutAfter((p.deadlineMs - System.currentTimeMillis()).coerceAtLeast(1_000))
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .build()
        runCatching { nm.notify(NOTIF_ID, n) }
    }

    companion object {
        const val CHANNEL = "fleet_remote_confirm"
        const val NOTIF_ID = 4711
    }
}

/**
 * Full-screen approve/deny for one held remote request. Shows caller app, op, actionRef and a
 * redacted params summary. Closing, backing out or timing out never approves.
 */
class RemoteConfirmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        val broker = (application as FileManagerApplication).confirmations
        setContent {
            val themeState by (application as FileManagerApplication).themeSettings.state.collectAsState()
            VictoriousTheme(themeState) {
                var pending by remember { mutableStateOf(broker.pending()) }
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(Unit) {
                    while (true) {
                        broker.expire()
                        pending = broker.pending(); now = System.currentTimeMillis()
                        if (pending.isEmpty()) { finish(); break }
                        delay(500)
                    }
                }
                pending.firstOrNull()?.let { p ->
                    ConfirmScreen(p, ((p.deadlineMs - now) / 1000).coerceAtLeast(0), onDecide = { approve ->
                        broker.decide(p.id, p.nonce, approve)
                        pending = broker.pending()
                        if (pending.isEmpty()) finish()
                    })
                }
            }
        }
    }
}

@Composable
private fun ConfirmScreen(p: ConfirmationBroker.Pending, secondsLeft: Long, onDecide: (Boolean) -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(24.dp)) {
            Text("Remote request", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.padding(6.dp))
            Text("An app is asking File Manager to do something that needs your approval.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.padding(8.dp))
            Field("Caller app", p.callerPackage)
            Field("Operation", p.op)
            Field("Action", p.actionRef)
            Text("Parameters", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            if (p.paramsSummary.isEmpty()) Text("(none)", style = MaterialTheme.typography.bodySmall)
            p.paramsSummary.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.padding(8.dp))
            Text("Denied automatically in ${secondsLeft}s.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.padding(12.dp))
            Row(Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { onDecide(false) }, modifier = Modifier.weight(1f)) { Text("Deny") }
                Spacer(Modifier.width(12.dp))
                Button(
                    onClick = { onDecide(true) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Approve") }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
    Text(value, style = MaterialTheme.typography.bodyLarge)
}
