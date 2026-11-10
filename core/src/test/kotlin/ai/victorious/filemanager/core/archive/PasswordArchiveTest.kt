package ai.victorious.filemanager.core.archive

import java.io.File
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PasswordArchiveTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun sources(): List<File> {
        val dir = tmp.newFolder("secret")
        File(dir, "notes.txt").writeText("top secret ".repeat(300))
        File(dir, "sub").mkdirs()
        File(dir, "sub/data.bin").writeBytes(Random(3).nextBytes(70_000))
        File(dir, "empty").mkdirs()
        val single = tmp.newFile("tiny.txt").apply { writeText("x") }
        return listOf(dir, single)
    }

    private fun assertExtracted(src: List<File>, out: File) {
        assertEquals(File(src[0], "notes.txt").readText(), File(out, "secret/notes.txt").readText())
        assertArrayEquals(File(src[0], "sub/data.bin").readBytes(), File(out, "secret/sub/data.bin").readBytes())
        assertEquals("x", File(out, "tiny.txt").readText())
    }

    @Test fun sevenZWithPasswordRoundTrips() {
        val src = sources()
        for (level in CompressionLevel.entries) {
            val out = File(tmp.root, "s-$level.7z")
            val r = ArchiveWriter.create(src, out, CreateFormat.SEVEN_Z, level, "pässwörd 1".toCharArray())
            assertTrue(r.encrypted)
            assertTrue(Archives.isSevenZPasswordProtected(out))
            // Names stay readable without the password.
            assertTrue(Archives.list(out).any { it.name == "secret/sub/data.bin" })
            assertTrue(Archives.verifySevenZPassword(out, "pässwörd 1".toCharArray()))
            assertFalse(Archives.verifySevenZPassword(out, "wrong".toCharArray()))
            val dest = File(tmp.root, "x-$level")
            Archives.extractSevenZ(out, dest, "pässwörd 1".toCharArray())
            assertExtracted(src, dest)
        }
    }

    @Test fun sevenZWithoutPasswordAsksOrRejectsWrongOne() {
        val src = sources()
        val out = File(tmp.root, "locked.7z")
        ArchiveWriter.create(src, out, CreateFormat.SEVEN_Z, CompressionLevel.FAST, "abc".toCharArray())
        try {
            Archives.extract(out, File(tmp.root, "none"))
            fail("expected a password error")
        } catch (e: ArchivePasswordException) { /* expected */ }
        val dest = File(tmp.root, "wrong")
        try {
            Archives.extractSevenZ(out, dest, "nope".toCharArray())
            fail("expected wrong password")
        } catch (e: WrongPasswordException) { /* expected */ }
        // No plausible-looking partial files are left behind for the entry that failed.
        assertFalse(File(dest, "secret/sub/data.bin").exists())
    }

    @Test fun plainSevenZIsNotPasswordProtected() {
        val src = sources()
        val out = File(tmp.root, "plain.7z")
        ArchiveWriter.create(src, out, CreateFormat.SEVEN_Z)
        assertFalse(Archives.isSevenZPasswordProtected(out))
        val dest = File(tmp.root, "plain")
        Archives.extractSevenZ(out, dest, null)
        assertExtracted(src, dest)
    }

    @Test fun zipWithPasswordUsesAesAndLevels() {
        val src = sources()
        val sizes = HashMap<CompressionLevel, Long>()
        for (level in CompressionLevel.entries) {
            val out = File(tmp.root, "z-$level.zip")
            val r = ArchiveWriter.create(src, out, CreateFormat.ZIP, level, "zip-pw".toCharArray())
            assertTrue(r.encrypted)
            assertEquals(3, r.files)
            assertTrue(EncryptedZip.isEncrypted(out))
            assertTrue(EncryptedZip.verifyPassword(out, "zip-pw".toCharArray()))
            assertFalse(EncryptedZip.verifyPassword(out, "bad".toCharArray()))
            val dest = File(tmp.root, "zx-$level")
            EncryptedZip.extract(out, dest, "zip-pw".toCharArray())
            assertExtracted(src, dest)
            assertTrue(File(dest, "secret/empty").isDirectory)
            sizes[level] = out.length()
        }
        assertTrue(sizes.getValue(CompressionLevel.BEST) < sizes.getValue(CompressionLevel.STORE))
    }

    @Test fun tarRefusesPasswordAndEmptyPasswordMeansNone() {
        val src = sources()
        for (f in listOf(CreateFormat.TAR, CreateFormat.TAR_GZ, CreateFormat.TAR_BZ2, CreateFormat.TAR_XZ)) {
            assertFalse(f.supportsPassword)
            try {
                ArchiveWriter.create(src, File(tmp.root, "t.${f.extension}"), f, CompressionLevel.FAST, "pw".toCharArray())
                fail("expected refusal for $f")
            } catch (e: IllegalArgumentException) { /* expected */ }
            assertFalse(File(tmp.root, "t.${f.extension}").exists())
        }
        val out = File(tmp.root, "empty-pw.7z")
        val r = ArchiveWriter.create(src, out, CreateFormat.SEVEN_Z, CompressionLevel.FAST, CharArray(0))
        assertFalse(r.encrypted)
        assertFalse(Archives.isSevenZPasswordProtected(out))
    }
}
