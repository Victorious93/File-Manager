package ai.victorious.filemanager.fleet

import ai.victorious.filemanager.FileManagerApplication
import ai.victorious.filemanager.core.fleet.BRIDGE_ACTION
import ai.victorious.filemanager.core.fleet.BRIDGE_KEY_REQUEST
import ai.victorious.filemanager.core.fleet.BRIDGE_KEY_RESPONSE
import ai.victorious.filemanager.core.fleet.BRIDGE_MSG_REQUEST
import ai.victorious.filemanager.core.fleet.BRIDGE_MSG_RESPONSE
import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger

/**
 * Optional Messenger transport (RootForge-style). The app does not depend on
 * DroidCommand AI. Binding is exported without a hub signature permission.
 */
class FileManagerBridgeService : Service() {
    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        val executor = (application as FileManagerApplication).bridge
        messenger = Messenger(
            object : Handler(Looper.getMainLooper()) {
                override fun handleMessage(msg: Message) {
                    if (msg.what != BRIDGE_MSG_REQUEST) {
                        super.handleMessage(msg)
                        return
                    }
                    val replyTo = msg.replyTo ?: return
                    val line = msg.data?.getString(BRIDGE_KEY_REQUEST).orEmpty()
                    val response = executor.handle(line)
                    val out = Message.obtain(null, BRIDGE_MSG_RESPONSE)
                    out.data = Bundle().apply { putString(BRIDGE_KEY_RESPONSE, response) }
                    runCatching { replyTo.send(out) }
                }
            },
        )
    }

    override fun onBind(intent: Intent?): IBinder? {
        if (intent?.action != null && intent.action != BRIDGE_ACTION && intent.action != ACTION_BIND) {
            return messenger.binder
        }
        return messenger.binder
    }

    companion object {
        const val ACTION_BIND = BRIDGE_ACTION
    }
}
