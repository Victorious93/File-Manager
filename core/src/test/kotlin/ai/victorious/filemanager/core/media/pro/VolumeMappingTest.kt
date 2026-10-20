package ai.victorious.filemanager.core.media.pro

import org.junit.Assert.assertEquals
import org.junit.Test

class VolumeMappingTest {
    @Test fun mapsEndsAndMid() {
        assertEquals(0, VolumeMapping.streamIndex(0, 15))
        assertEquals(15, VolumeMapping.streamIndex(100, 15))
        assertEquals(7, VolumeMapping.streamIndex(50, 15))
        assertEquals(0, VolumeMapping.streamIndex(10, 0))
    }
}
