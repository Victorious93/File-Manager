package ai.victorious.filemanager.core.media.pro

/**
 * Maps a 0..100 media.volume level onto a system stream max.
 * Pure helper so STREAM_MUSIC wiring is unit-tested without AudioManager.
 */
object VolumeMapping {
    fun streamIndex(level0to100: Int, maxVolume: Int): Int {
        require(maxVolume >= 0)
        if (maxVolume == 0) return 0
        val v = level0to100.coerceIn(0, 100)
        return ((v * maxVolume) / 100).coerceIn(0, maxVolume)
    }
}
