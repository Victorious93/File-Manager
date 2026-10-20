package ai.victorious.filemanager.tiles

import ai.victorious.filemanager.MainActivity
import ai.victorious.filemanager.core.tiles.TileStatus
import ai.victorious.filemanager.core.tiles.Tiles
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tiles. These require the platform-mandated BIND_QUICK_SETTINGS_TILE permission on
 * OUR OWN services (declared in the manifest) — that is standard for any QS tile and is not a fleet
 * BIND_* permission. The tile label/subtitle come from the unit-tested [Tiles] model in :core.
 */
class OpenFileManagerTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val p = Tiles.openFileManager()
        qsTile?.apply {
            label = p.label
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    @Suppress("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                android.app.PendingIntent.getActivity(
                    this, 0, intent,
                    android.app.PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}

/**
 * Wi-Fi file transfer toggle tile. It launches the app to the Wi-Fi transfer screen (the LAN HTTP
 * server is bound to a UI lifecycle, so the tile hands off rather than running a headless server).
 * State text reflects whether the server is currently running.
 */
class WifiTransferTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val app = applicationContext as ai.victorious.filemanager.FileManagerApplication
        val running = runCatching { app.wifiTransfer.isRunning }.getOrDefault(false)
        val url = runCatching { app.wifiTransfer.url }.getOrNull()
        val p = Tiles.wifiTransfer(running, url)
        qsTile?.apply {
            label = p.label
            state = if (p.status == TileStatus.ACTIVE) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) subtitle = p.subtitle
            updateTile()
        }
    }

    @Suppress("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_WIFI_TRANSFER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                android.app.PendingIntent.getActivity(
                    this, 1, intent,
                    android.app.PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
