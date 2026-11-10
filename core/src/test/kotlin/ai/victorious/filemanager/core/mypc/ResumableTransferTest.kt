package ai.victorious.filemanager.core.mypc

import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ResumableTransferTest {
    private lateinit var dir: File
    private val data = Random(7).nextBytes(300_000)

    /** Local side that counts bytes read and can pretend it has no hash, or lie about it. */
    private class Probe(
        var canHash: Boolean = true,
        var lieHash: Boolean = false,
        var failAfterBytes: Long = Long.MAX_VALUE,
    ) : TransferSide by LocalTransferSide() {
        private val real = LocalTransferSide()
        var bytesRead = 0L
        val deleted = mutableListOf<String>()

        override fun openRead(path: String, offset: Long): InputStream = object : FilterInputStream(real.openRead(path, offset)) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (bytesRead >= failAfterBytes) throw IOException("network dropped")
                val n = super.read(b, off, minOf(len, (failAfterBytes - bytesRead).coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)))
                if (n > 0) bytesRead += n
                return n
            }
        }
        override fun delete(path: String) { deleted += path; real.delete(path) }
        override fun sha256(path: String): String? = when {
            !canHash -> null
            lieHash -> "0".repeat(64)
            else -> real.sha256(path)
        }
    }

    @Before fun setUp() { dir = Files.createTempDirectory("fm-resume").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    private fun srcFile(bytes: ByteArray = data) = File(dir, "src.bin").apply { writeBytes(bytes) }
    private fun dest() = File(dir, "out/dest.bin").also { it.parentFile.mkdirs() }

    @Test fun freshCopyWritesPartialThenRenames() {
        val src = srcFile()
        val dst = dest()
        val seen = mutableListOf<Long>()
        val r = ResumableTransfer.copyFile(LocalTransferSide(), src.path, LocalTransferSide(), dst.path, { d, _ ->
            seen += d
            if (d in 1 until data.size) {
                assertTrue("writes go to .partial", File(dst.path + ".partial").exists())
                assertFalse("final name appears only after verify", dst.exists())
            }
        })
        assertArrayEquals(data, dst.readBytes())
        assertFalse(File(dst.path + ".partial").exists())
        assertEquals(0L, r.resumedFrom)
        assertEquals(data.size.toLong(), r.bytesCopied)
        assertTrue(r.hashVerified)
        assertEquals(data.size.toLong(), seen.last())
    }

    @Test fun resumesFromPartialOffset() {
        val src = srcFile()
        val dst = dest()
        File(dst.path + ".partial").writeBytes(data.copyOfRange(0, 123_456))
        val probe = Probe()
        val r = ResumableTransfer.copyFile(probe, src.path, LocalTransferSide(), dst.path)
        assertEquals(123_456L, r.resumedFrom)
        assertEquals((data.size - 123_456).toLong(), r.bytesCopied)
        assertEquals("only the missing tail is read", (data.size - 123_456).toLong(), probe.bytesRead)
        assertArrayEquals(data, dst.readBytes())
        assertTrue(r.hashVerified)
    }

    @Test fun interruptedCopyResumesOnNextAttempt() {
        val src = srcFile()
        val dst = dest()
        val flaky = Probe(failAfterBytes = 100_000)
        try {
            ResumableTransfer.copyFile(flaky, src.path, LocalTransferSide(), dst.path)
            fail("expected the simulated drop")
        } catch (e: IOException) {
            assertEquals("network dropped", e.message)
        }
        assertFalse(dst.exists())
        val kept = File(dst.path + ".partial").length()
        assertTrue("partial kept for resume ($kept)", kept in 1..100_000)
        val again = Probe()
        val r = ResumableTransfer.copyFile(again, src.path, LocalTransferSide(), dst.path)
        assertEquals(kept, r.resumedFrom)
        assertEquals(data.size - kept, again.bytesRead)
        assertArrayEquals(data, dst.readBytes())
    }

    @Test fun partialLongerThanSourceRestartsFromZero() {
        val src = srcFile(data.copyOfRange(0, 1000))
        val dst = dest()
        File(dst.path + ".partial").writeBytes(ByteArray(5000) { 1 })
        val r = ResumableTransfer.copyFile(LocalTransferSide(), src.path, LocalTransferSide(), dst.path)
        assertEquals(0L, r.resumedFrom)
        assertArrayEquals(data.copyOfRange(0, 1000), dst.readBytes())
    }

    @Test fun staleResumedBytesFailHashAndRestartOnce() {
        val src = srcFile()
        val dst = dest()
        // Same length prefix but different content: e.g. the PC file changed since the first attempt.
        File(dst.path + ".partial").writeBytes(ByteArray(50_000) { 9 })
        val probe = Probe()
        val r = ResumableTransfer.copyFile(probe, src.path, LocalTransferSide(), dst.path)
        assertEquals("second pass starts from zero", 0L, r.resumedFrom)
        assertEquals(data.size - 50_000L + data.size, probe.bytesRead)
        assertArrayEquals(data, dst.readBytes())
        assertTrue(r.hashVerified)
    }

    @Test fun hashMismatchOnFreshCopyFailsAndDiscards() {
        val src = srcFile()
        val dst = dest()
        try {
            ResumableTransfer.copyFile(LocalTransferSide(), src.path, Probe(lieHash = true), dst.path)
            fail("expected verify failure")
        } catch (e: TransferVerifyException) {
            assertTrue(e.message!!.contains("SHA-256"))
        }
        assertFalse(dst.exists())
        assertFalse(File(dst.path + ".partial").exists())
        assertTrue(src.exists())
    }

    @Test fun sizeOnlyWhenOneSideCannotHash() {
        val src = srcFile()
        val dst = dest()
        val r = ResumableTransfer.copyFile(LocalTransferSide(), src.path, Probe(canHash = false), dst.path)
        assertFalse(r.hashVerified)
        assertArrayEquals(data, dst.readBytes())
    }

    @Test fun cancelKeepsPartial() {
        val src = srcFile()
        val dst = dest()
        var calls = 0
        try {
            ResumableTransfer.copyFile(LocalTransferSide(), src.path, LocalTransferSide(), dst.path, isCancelled = { ++calls > 3 })
            fail("expected cancel")
        } catch (e: TransferCancelledException) {
            assertEquals(dst.path + ".partial", e.partialPath)
        }
        assertTrue(File(dst.path + ".partial").length() > 0)
        assertFalse(dst.exists())
    }

    @Test fun finishedFileFromEarlierAttemptIsAcceptedNotDuplicated() {
        val src = srcFile()
        val dst = dest().apply { writeBytes(data) }
        val r = ResumableTransfer.copyFile(LocalTransferSide(), src.path, LocalTransferSide(), dst.path)
        assertTrue(r.alreadyDone)
        assertTrue(r.hashVerified)
    }

    @Test fun differentExistingFileIsNeverOverwritten() {
        val src = srcFile()
        val dst = dest().apply { writeBytes(ByteArray(10)) }
        assertTrue(runCatching { ResumableTransfer.copyFile(LocalTransferSide(), src.path, LocalTransferSide(), dst.path) }.isFailure)
        assertEquals(10L, dst.length())
    }

    @Test fun emptyFileCopies() {
        val src = srcFile(ByteArray(0))
        val dst = dest()
        ResumableTransfer.copyFile(LocalTransferSide(), src.path, LocalTransferSide(), dst.path)
        assertTrue(dst.exists())
        assertEquals(0L, dst.length())
    }

    @Test fun moveDeletesSourceOnlyAfterVerify() {
        val tree = File(dir, "tree").apply { mkdirs() }
        File(tree, "a.txt").writeText("alpha")
        File(tree, "sub").mkdirs()
        File(tree, "sub/b.bin").writeBytes(data)
        val src = Probe()
        var lastTotal = 0L
        val summary = TreeTransfer(src, LocalTransferSide(), move = true, onProgress = { _, t, _ -> lastTotal = t })
            .run(tree.path, File(dir, "copy").path)
        assertEquals(2, summary.files)
        assertEquals(2, summary.hashVerifiedFiles)
        assertEquals(5L + data.size, lastTotal)
        assertEquals("alpha", File(dir, "copy/a.txt").readText())
        assertArrayEquals(data, File(dir, "copy/sub/b.bin").readBytes())
        assertFalse(tree.exists())
    }

    @Test fun moveKeepsSourceWhenVerifyFails() {
        val tree = File(dir, "tree").apply { mkdirs() }
        File(tree, "a.txt").writeText("alpha")
        val result = runCatching { TreeTransfer(LocalTransferSide(), Probe(lieHash = true), move = true).run(tree.path, File(dir, "copy").path) }
        assertTrue(result.exceptionOrNull() is TransferVerifyException)
        assertEquals("alpha", File(tree, "a.txt").readText())
        assertFalse(File(dir, "copy/a.txt").exists())
    }

    @Test fun uniqueNamesSkipTakenAndPartialNames() {
        val taken = setOf("/d/a.txt", "/d/a (1).txt.partial", "/d/folder")
        val join = { d: String, n: String -> "$d/$n" }
        assertEquals("/d/a (2).txt", TransferNames.unique("/d", "a.txt", join) { it in taken })
        assertEquals("/d/folder (1)", TransferNames.unique("/d", "folder", join) { it in taken })
        assertEquals("/d/new.txt", TransferNames.unique("/d", "new.txt", join) { it in taken })
        assertEquals("/d/.bashrc (1)", TransferNames.unique("/d", ".bashrc", join) { it == "/d/.bashrc" })
    }
}
