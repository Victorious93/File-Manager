package ai.victorious.victorfiles.core.ops

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class FileSplitTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun sample(size: Int): File =
        tmp.newFile("data.bin").apply { writeBytes(ByteArray(size) { (it * 31 + 7).toByte() }) }

    @Test fun splitThenJoinRoundTrips() {
        val src = sample(100_000)
        val parts = FileSplit.split(src, 30_000)
        assertEquals(listOf("data.bin.001", "data.bin.002", "data.bin.003", "data.bin.004"), parts.map { it.name })
        assertEquals(listOf(30_000L, 30_000L, 30_000L, 10_000L), parts.map { it.length() })
        val original = src.readBytes()
        src.delete()
        val joined = FileSplit.join(parts[2]) // any part works
        assertEquals("data.bin", joined.name)
        assertArrayEquals(original, joined.readBytes())
        assertFalse(File(joined.parentFile, ".data.bin.joining").exists())
    }

    @Test fun exactMultipleAndValidation() {
        assertEquals(2, FileSplit.split(sample(100), 50).size)
        assertTrue(runCatching { FileSplit.split(File(tmp.root, "data.bin"), 0) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun refusesTooManyPartsNoSplitNeededAndOverwrite() {
        val src = sample(2000)
        assertTrue(runCatching { FileSplit.split(src, 1) }.exceptionOrNull() is IllegalArgumentException) // 2000 parts
        assertTrue(runCatching { FileSplit.split(src, 5000) }.exceptionOrNull() is IllegalArgumentException)
        FileSplit.split(src, 1000)
        assertTrue(runCatching { FileSplit.split(src, 1000) }.exceptionOrNull() is IOException)
    }

    @Test fun joinRejectsGapsAndExistingOutput() {
        val src = sample(300)
        val parts = FileSplit.split(src, 100)
        // join refuses to overwrite the still-present source
        assertTrue(runCatching { FileSplit.join(parts[0]) }.exceptionOrNull() is IOException)
        src.delete()
        parts[1].delete() // gap: .001 and .003 remain
        assertTrue(runCatching { FileSplit.join(parts[0]) }.exceptionOrNull() is IOException)
        assertFalse(File(src.parentFile, "data.bin").exists())
    }

    @Test fun rejectsNonPartName() {
        assertTrue(runCatching { FileSplit.join(sample(10)) }.exceptionOrNull() is IllegalArgumentException)
    }
}
