package ai.victorious.filemanager.core.tiles

/**
 * Pure model for the Quick Settings tiles so the label/active text shown on each tile can be
 * decided and unit-tested without the Android `TileService`. The service in :app maps this to the
 * platform Tile object.
 */
enum class TileStatus { ACTIVE, INACTIVE, UNAVAILABLE }

data class TilePresentation(val label: String, val status: TileStatus, val subtitle: String?)

object Tiles {
    /** Wi-Fi file transfer toggle tile. [running] = server is serving; [url] = current LAN URL. */
    fun wifiTransfer(running: Boolean, url: String?): TilePresentation = TilePresentation(
        label = "Wi-Fi transfer",
        status = if (running) TileStatus.ACTIVE else TileStatus.INACTIVE,
        subtitle = if (running) (url ?: "On") else "Off",
    )

    /** "Open File Manager" is a launch action, not a toggle, so it is always inactive/available. */
    fun openFileManager(): TilePresentation =
        TilePresentation(label = "File Manager", status = TileStatus.INACTIVE, subtitle = null)
}
