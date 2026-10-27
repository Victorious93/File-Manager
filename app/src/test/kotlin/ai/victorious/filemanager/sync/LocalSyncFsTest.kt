package ai.victorious.filemanager.sync

import ai.victorious.filemanager.core.sync.SYNC_PART_PREFIX
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LocalSyncFsTest {
    private fun tmp(): File = Files.createTempDirectory("lsfs").toFile()

    @Test fun writeFrom_streamsAndKeepsMtime() {
        val root = tmp()
        LocalSyncFs().writeFrom(root.path, "d/x.bin", ByteArray(5_000_000) { 7 }.inputStream(), 1_600_000_000_000)
        val f = File(root, "d/x.bin")
        assertEquals(5_000_000L, f.length())
        assertEquals(1_600_000_000_000L, f.lastModified())
        assertTrue(File(root, "d").listFiles()!!.none { it.name.startsWith(SYNC_PART_PREFIX) })
    }

    @Test fun failedWrite_keepsOldFile_andLeavesNoTemp() {
        val root = tmp()
        File(root, "x.txt").writeText("old")
        val failing = object : InputStream() {
            var n = 0
            override fun read(): Int = if (n++ < 1000) 1 else throw IOException("boom")
        }
        try { LocalSyncFs().writeFrom(root.path, "x.txt", failing); fail() } catch (_: IOException) {}
        assertEquals("old", File(root, "x.txt").readText())
        assertEquals(listOf("x.txt"), root.list()!!.toList())
    }

    @Test fun leftoverTempFilesAreNotListed() {
        val root = tmp()
        File(root, "a.txt").writeText("a")
        File(root, "${SYNC_PART_PREFIX}1-a.txt").writeText("partial")
        assertEquals(listOf("a.txt"), LocalSyncFs().listRecursive(root.path).map { it.relativePath })
    }
}
