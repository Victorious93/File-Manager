package ai.victorious.filemanager.core.shell

import ai.victorious.filemanager.core.fs.FileIoException
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Runs the generated commands in a real `sh`, so the shell syntax, chunking and base64 handling
 * are exercised for real (not against a fake that only matches strings).
 */
class ElevatedFileTransferTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var stage: File

    @Before fun setUp() {
        val tools = listOf("sh", "base64", "dd", "wc", "cat", "rm")
        assumeTrue("needs POSIX tools", tools.all { which(it) })
        stage = tmp.newFolder("stage")
    }

    private fun which(tool: String) = (System.getenv("PATH") ?: "").split(File.pathSeparator).any { File(it, tool).canExecute() }

    private val localShell = ElevatedFileTransfer.Shell { command, stdin ->
        val p = ProcessBuilder("sh", "-c", command).start()
        val writer = Thread { p.outputStream.use { o -> if (stdin != null) o.write(stdin.toByteArray()) } }.apply { start() }
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        val err = p.errorStream.readBytes().toString(Charsets.UTF_8)
        writer.join()
        p.waitFor(30, TimeUnit.SECONDS)
        ShellResult(p.exitValue(), out, err)
    }

    private fun write(path: File, data: ByteArray) =
        ElevatedFileTransfer.writeBytes(path.path, data, localShell, stage.path)

    @Test fun roundTripsEveryByteValue() {
        val data = ByteArray(256) { it.toByte() }
        val f = tmp.newFile()
        write(f, data)
        assertArrayEquals(data, f.readBytes())
        assertArrayEquals(data, ElevatedFileTransfer.readBytes(f.path, localShell))
    }

    @Test fun roundTripsAcrossSeveralChunksWithARaggedEnd() {
        val data = ByteArray(ElevatedFileTransfer.CHUNK_BYTES * 2 + 1234) { (it * 31 + 7).toByte() }
        val f = tmp.newFile()
        write(f, data)
        assertArrayEquals(data, f.readBytes())
        assertArrayEquals(data, ElevatedFileTransfer.readBytes(f.path, localShell))
    }

    @Test fun exactChunkMultipleAndEmptyFile() {
        val exact = ByteArray(ElevatedFileTransfer.CHUNK_BYTES) { it.toByte() }
        val a = tmp.newFile().also { write(it, exact) }
        assertArrayEquals(exact, ElevatedFileTransfer.readBytes(a.path, localShell))

        val b = tmp.newFile().also { it.writeText("old content") }
        write(b, ByteArray(0))
        assertEquals(0L, b.length())
        assertEquals(0, ElevatedFileTransfer.readBytes(b.path, localShell).size)
    }

    @Test fun textKeepsCrlfAndTheMissingFinalNewline() {
        val withCrlf = "line one\r\nline two\r\nlast, no newline"
        val f = tmp.newFile()
        ElevatedFileTransfer.writeText(f.path, withCrlf, localShell, stage.path)
        assertEquals(withCrlf, f.readText())
        assertEquals(withCrlf, ElevatedFileTransfer.readText(f.path, localShell))

        val trailing = "one\ntwo\n\n"
        ElevatedFileTransfer.writeText(f.path, trailing, localShell, stage.path)
        assertEquals(trailing, ElevatedFileTransfer.readText(f.path, localShell))
    }

    @Test fun unicodeAndShellSpecialCharactersAreInert() {
        val text = "naïve — 日本語 'single' \"double\" \$HOME `ls` ; rm -rf / \\n"
        val f = File(tmp.root, "odd name 'quote' \$x.txt")
        ElevatedFileTransfer.writeText(f.path, text, localShell, stage.path)
        assertEquals(text, f.readText())
        assertEquals(text, ElevatedFileTransfer.readText(f.path, localShell))
    }

    @Test fun overwritingKeepsTheSameInode() {
        val f = tmp.newFile().also { it.writeText("before") }
        val inodeBefore = Files.getAttribute(f.toPath(), "unix:ino")
        write(f, "after".toByteArray())
        assertEquals("after", f.readText())
        assertEquals(inodeBefore, Files.getAttribute(f.toPath(), "unix:ino"))
    }

    @Test fun overwritingAShorterFileDropsTheOldTail() {
        val f = tmp.newFile().also { it.writeText("a much longer previous body") }
        write(f, "short".toByteArray())
        assertEquals("short", f.readText())
    }

    @Test fun stagingFileIsRemovedAfterSuccess() {
        write(tmp.newFile(), ByteArray(5000) { 1 })
        assertTrue(stage.listFiles().orEmpty().isEmpty())
    }

    @Test fun failureLeavesTheOriginalUntouchedAndCleansUp() {
        val f = tmp.newFile().also { it.writeText("precious") }
        var failedOn: String? = null
        val flaky = ElevatedFileTransfer.Shell { cmd, stdin ->
            // Swallow the first chunk so the staged length no longer matches the data.
            if (cmd.startsWith("base64 -d >>") && failedOn == null && stdin != null && stdin.length > 200) {
                failedOn = cmd
                ShellResult(0, "", "")
            } else localShell.run(cmd, stdin)
        }
        val data = ByteArray(ElevatedFileTransfer.CHUNK_BYTES + 500) { 9 }
        try {
            ElevatedFileTransfer.writeBytes(f.path, data, flaky, stage.path)
            org.junit.Assert.fail("expected a length-mismatch failure")
        } catch (e: FileIoException) {
            assertTrue(e.message!!.contains("original left untouched"))
        }
        assertEquals("precious", f.readText())
        assertTrue(stage.listFiles().orEmpty().isEmpty())
    }

    @Test fun failedCommandSurfacesTheFirstStderrLine() {
        val broken = ElevatedFileTransfer.Shell { _, _ -> ShellResult(1, "", "Permission denied\nsecond line") }
        try {
            ElevatedFileTransfer.writeBytes("/x", byteArrayOf(1), broken, stage.path)
            org.junit.Assert.fail("expected failure")
        } catch (e: FileIoException) {
            assertEquals("Permission denied", e.message)
        }
    }

    @Test fun oversizedFileIsRefusedBeforeReadingIt() {
        val f = tmp.newFile().also { it.writeBytes(ByteArray(2048)) }
        var chunkReads = 0
        val counting = ElevatedFileTransfer.Shell { cmd, stdin ->
            if (cmd.startsWith("dd ")) chunkReads++
            localShell.run(cmd, stdin)
        }
        try {
            ElevatedFileTransfer.readBytes(f.path, counting, maxBytes = 1024)
            org.junit.Assert.fail("expected size refusal")
        } catch (e: FileIoException) {
            assertTrue(e.message!!.contains("too large"))
        }
        assertEquals(0, chunkReads)
    }

    @Test fun missingFileReportsAnErrorInsteadOfEmptyContent() {
        try {
            ElevatedFileTransfer.readBytes(File(tmp.root, "nope").path, localShell)
            org.junit.Assert.fail("expected failure")
        } catch (e: FileIoException) {
            assertFalse(e.message.isNullOrBlank())
        }
    }
}
