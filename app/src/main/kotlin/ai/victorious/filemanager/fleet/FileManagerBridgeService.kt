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
 * Optional Messenger transport (EXTERNAL_NODE). The app does not depend on
 * DroidCommand AI. Callers are identified via Message.sendingUid and checked
 * against the allowlist in BridgeExecutor; external control is off by default.
 */
class FileManagerBridgeService : Service() {
    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        val app = application as FileManagerApplication
        val executor = app.bridge
        val resolver = CallerResolver(packageManager)
        messenger = Messenger(
            object : Handler(Looper.getMainLooper()) {
                override fun handleMessage(msg: Message) {
                    if (msg.what != BRIDGE_MSG_REQUEST) {
                        super.handleMessage(msg)
                        return
                    }
                    val replyTo = msg.replyTo ?: return
                    // Messenger dispatch happens off the binder thread, so
                    // Binder.getCallingUid() would be our own uid here;
                    // sendingUid is the kernel-attested sender (-1 if unknown).
                    val caller = resolver.resolve(msg.sendingUid)
                    val line = msg.data?.getString(BRIDGE_KEY_REQUEST).orEmpty()
                    // Requests needing on-device approval are held; the reply is sent once decided.
                    executor.handleAsync(line, caller) { response ->
                        val out = Message.obtain(null, BRIDGE_MSG_RESPONSE)
                        out.data = Bundle().apply { putString(BRIDGE_KEY_RESPONSE, response) }
                        runCatching { replyTo.send(out) }
                    }
                }
            },
        )
    }

    override fun onBind(intent: Intent?): IBinder? {
        if (intent?.action != BRIDGE_ACTION) return null
        return messenger.binder
    }
}
