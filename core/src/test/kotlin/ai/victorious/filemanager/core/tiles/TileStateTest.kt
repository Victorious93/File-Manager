package ai.victorious.filemanager.core.tiles

import org.junit.Assert.assertEquals
import org.junit.Test

class TileStateTest {
    @Test fun wifiTileReflectsRunningState() {
        val on = Tiles.wifiTransfer(running = true, url = "http://192.168.0.5:8080")
        assertEquals(TileStatus.ACTIVE, on.status)
        assertEquals("http://192.168.0.5:8080", on.subtitle)

        val off = Tiles.wifiTransfer(running = false, url = null)
        assertEquals(TileStatus.INACTIVE, off.status)
        assertEquals("Off", off.subtitle)
    }

    @Test fun wifiTileRunningWithoutUrlSaysOn() {
        assertEquals("On", Tiles.wifiTransfer(running = true, url = null).subtitle)
    }

    @Test fun openTileIsAction() {
        val t = Tiles.openFileManager()
        assertEquals(TileStatus.INACTIVE, t.status)
        assertEquals("VictorFiles", t.label)
    }
}
