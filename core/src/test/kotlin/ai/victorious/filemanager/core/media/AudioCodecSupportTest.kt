package ai.victorious.filemanager.core.media

import ai.victorious.filemanager.core.viewer.FileKind
import ai.victorious.filemanager.core.viewer.FileKinds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioCodecSupportTest {
    @Test fun statuses() {
        assertEquals(AudioCodecSupport.Status.PLAYABLE, AudioCodecSupport.info("Movie.AC3")!!.status)
        assertEquals(AudioCodecSupport.Status.CONTAINER_DEPENDENT, AudioCodecSupport.info("a.dts")!!.status)
        assertEquals(AudioCodecSupport.Status.UNSUPPORTED, AudioCodecSupport.info("a.wma")!!.status)
        assertNull(AudioCodecSupport.info("a.mp3"))
    }

    @Test fun hints() {
        assertTrue(AudioCodecSupport.failureHint("x.wma", true)!!.contains("ASF"))
        assertTrue(AudioCodecSupport.failureHint("x.eac3", false)!!.contains("FFmpeg"))
        assertNull(AudioCodecSupport.failureHint("x.eac3", true))
        assertNull(AudioCodecSupport.failureHint("x.mp3", false))
    }

    @Test fun fileKindsTreatThemAsAudio() {
        for (n in listOf("a.ac3", "a.eac3", "a.ec3", "a.dts", "a.mka", "a.wma", "a.thd")) assertEquals(n, FileKind.AUDIO, FileKinds.byName(n))
    }
}
