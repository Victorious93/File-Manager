package ai.victorious.filemanager.core.archive

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArchiveSearchTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun zip(name: String, entries: Map<String, ByteArray>): File {
        val f = File(tmp.root, name)
        ZipOutputStream(f.outputStream()).use { z ->
            for ((n, b) in entries) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
        }
        return f
    }

    private fun text(s: String) = s.toByteArray()

    @Test fun findsEntriesByName() {
        val f = zip("a.zip", mapOf("docs/readme.md" to text("x"), "src/Main.kt" to text("y"), "src/util.kt" to text("z")))
        val r = ArchiveSearch.search(f, ArchiveQuery("main"))
        assertEquals(listOf("src/Main.kt"), r.hits.map { it.entry })
        assertEquals(null, r.hits.single().line)
        assertEquals(false, r.truncated)
    }

    @Test fun nameSearchIsCaseSensitiveOnRequest() {
        val f = zip("a.zip", mapOf("Main.kt" to text("y")))
        assertEquals(0, ArchiveSearch.search(f, ArchiveQuery("main", caseSensitive = true)).hits.size)
        assertEquals(1, ArchiveSearch.search(f, ArchiveQuery("Main", caseSensitive = true)).hits.size)
    }

    @Test fun findsContentWithLineNumbers() {
        val f = zip("a.zip", mapOf("a.txt" to text("one\nneedle here\nthree"), "b.txt" to text("nothing")))
        val r = ArchiveSearch.search(f, ArchiveQuery("needle", searchNames = false, searchContent = true))
        assertEquals(1, r.hits.size)
        assertEquals("a.txt", r.hits[0].entry)
        assertEquals(2, r.hits[0].line)
        assertEquals("needle here", r.hits[0].text)
    }

    @Test fun contentGlobRestrictsWhichEntriesAreRead() {
        val f = zip("a.zip", mapOf("a.txt" to text("needle"), "dir/b.xml" to text("needle")))
        val r = ArchiveSearch.search(f, ArchiveQuery("needle", searchNames = false, searchContent = true, nameGlobs = listOf("*.xml")))
        assertEquals(listOf("dir/b.xml"), r.hits.map { it.entry })
    }

    @Test fun regexQuery() {
        val f = zip("a.zip", mapOf("a.txt" to text("id=42\nid=x")))
        val r = ArchiveSearch.search(f, ArchiveQuery("id=\\d+", searchNames = false, searchContent = true, regex = true))
        assertEquals(listOf(1), r.hits.map { it.line })
    }

    @Test fun binaryEntriesAreNotSearched() {
        val f = zip("a.zip", mapOf("blob.bin" to byteArrayOf(0, 1, 2) + text("needle")))
        val r = ArchiveSearch.search(f, ArchiveQuery("needle", searchNames = false, searchContent = true))
        assertTrue(r.hits.isEmpty())
    }

    @Test fun oversizedEntryIsSkippedAndCounted_butItsNameIsStillSearched() {
        val big = ByteArray(2000) { 'a'.code.toByte() }
        val f = zip("a.zip", mapOf("big-needle.txt" to big))
        val r = ArchiveSearch.search(f, ArchiveQuery("needle", searchContent = true, maxEntryBytes = 1000))
        assertEquals(listOf("big-needle.txt"), r.hits.map { it.entry })
        assertEquals(1, r.skippedTooLarge)
    }

    @Test fun totalByteBudgetStopsTheSearch() {
        val body = text("filler line\n".repeat(100))
        val f = zip("a.zip", (1..10).associate { "f$it.txt" to body })
        val r = ArchiveSearch.search(f, ArchiveQuery("needle", searchNames = false, searchContent = true, maxTotalBytes = body.size * 3L))
        assertTrue(r.truncated)
    }

    @Test fun resultLimitTruncates() {
        val f = zip("a.zip", (1..10).associate { "f$it.txt" to text("x") })
        val r = ArchiveSearch.search(f, ArchiveQuery("f", maxResults = 3))
        assertEquals(3, r.hits.size)
        assertTrue(r.truncated)
    }

    @Test fun cancellationStopsTheSearch() {
        val f = zip("a.zip", (1..10).associate { "f$it.txt" to text("x") })
        var calls = 0
        val r = ArchiveSearch.search(f, ArchiveQuery("f"), isCancelled = { ++calls > 2 })
        assertTrue(r.hits.size < 10)
        assertTrue(r.truncated)
    }

    @Test fun searchesInsideTar() {
        val f = File(tmp.root, "a.tar")
        TarArchiveOutputStream(f.outputStream()).use { t ->
            val data = text("alpha\nneedle\n")
            t.putArchiveEntry(TarArchiveEntry("x/y.txt").apply { size = data.size.toLong() })
            t.write(data); t.closeArchiveEntry()
        }
        val r = ArchiveSearch.search(f, ArchiveQuery("needle", searchNames = false, searchContent = true))
        assertEquals(listOf("x/y.txt" to 2), r.hits.map { it.entry to it.line })
    }

    @Test fun invalidRegexIsReported() {
        val f = zip("a.zip", mapOf("a.txt" to text("x")))
        try {
            ArchiveSearch.search(f, ArchiveQuery("(", regex = true))
            org.junit.Assert.fail("expected InvalidQuery")
        } catch (e: ai.victorious.filemanager.core.search.ContentSearch.InvalidQuery) {
            assertTrue(e.message!!.startsWith("Invalid pattern"))
        }
    }

    @Test fun emptyQueryFindsNothing() {
        val f = zip("a.zip", mapOf("a.txt" to text("x")))
        assertTrue(ArchiveSearch.search(f, ArchiveQuery("")).hits.isEmpty())
    }
}
