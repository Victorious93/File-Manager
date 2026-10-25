package ai.victorious.filemanager.core.ops

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

    @Test fun parseSize() {
        assertEquals(50L * 1024 * 1024, FileSplit.parseSize("50"))
        assertEquals(50L * 1024 * 1024, FileSplit.parseSize(" 50 MB "))
        assertEquals((1.5 * 1024 * 1024 * 1024).toLong(), FileSplit.parseSize("1.5gb"))
        assertEquals(700L * 1024, FileSplit.parseSize("700k"))
        assertEquals(null, FileSplit.parseSize(""))
        assertEquals(null, FileSplit.parseSize("abc"))
        assertEquals(null, FileSplit.parseSize("0"))
        assertEquals(null, FileSplit.parseSize("-5"))
    }

    @Test fun aLoneBMeansBytesNotMegabytes() {
        // "512b" used to match as a bare number with an optional trailing b, so it fell into the MB default.
        assertEquals(512L, FileSplit.parseSize("512b"))
        assertEquals(512L, FileSplit.parseSize("512B"))
        assertEquals(512L, FileSplit.parseSize(" 512 b "))
        assertEquals(1L, FileSplit.parseSize("1b"))
    }

    @Test fun everyUnitIsBinaryAndTheBIsOptionalAfterAPrefix() {
        val kb = 1024L
        val mb = kb * 1024
        val gb = mb * 1024
        for ((suffixes, bytes) in listOf(listOf("k", "kb", "KB", "Kb") to kb, listOf("m", "mb", "MB") to mb, listOf("g", "gb", "GB") to gb)) {
            for (s in suffixes) assertEquals("2$s", 2 * bytes, FileSplit.parseSize("2$s"))
        }
        assertEquals("a bare number is megabytes", 2 * mb, FileSplit.parseSize("2"))
    }

    @Test fun sizesThatAreNotWholeBytesOrNotASizeAreRejected() {
        assertEquals("half a byte", null, FileSplit.parseSize("0.5b"))
        assertEquals("1.5 bytes is not a size", null, FileSplit.parseSize("1.5b"))
        assertEquals("0b", null, FileSplit.parseSize("0b"))
        assertEquals("unit without a number", null, FileSplit.parseSize("b"))
        assertEquals(null, FileSplit.parseSize("mb"))
        assertEquals("doubled suffix", null, FileSplit.parseSize("5bb"))
        assertEquals("unit before number", null, FileSplit.parseSize("mb5"))
        assertEquals("fractions of a larger unit are still fine", 512L, FileSplit.parseSize("0.5k"))
    }

    @Test fun aTypedByteSizeSplitsIntoThatManyBytes() {
        val src = sample(2_000)
        val parts = FileSplit.split(src, FileSplit.parseSize("512b")!!)
        assertEquals(listOf(512L, 512L, 512L, 464L), parts.map { it.length() })
    }
}
