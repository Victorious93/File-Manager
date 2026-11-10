package ai.victorious.filemanager.core.dupes

import java.io.File
import java.util.concurrent.CancellationException
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DuplicateFinderFastTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun sameEdgesDifferentMiddleAreNotDuplicates() {
        val root = tmp.newFolder("r")
        val base = Random(1).nextBytes(300_000)
        File(root, "a.mp4").writeBytes(base)
        File(root, "b.mp4").writeBytes(base.copyOf())
        File(root, "c.mp4").writeBytes(base.copyOf().also { it[150_000] = (it[150_000] + 1).toByte() })
        val phases = mutableListOf<DupePhase>()
        val g = DuplicateFinder.scan(root, DupeOptions(), onProgress = { phases += it.phase })
        assertEquals(1, g.size)
        assertEquals(listOf("a.mp4", "b.mp4"), g[0].files.map { it.name })
        assertEquals(DupePhase.DONE, phases.last())
        assertTrue(DupePhase.FULL_CHECK in phases)
    }

    @Test fun cacheIsReusedAndInvalidatedOnChange() {
        val root = tmp.newFolder("r")
        val data = Random(2).nextBytes(100_000)
        val a = File(root, "a").apply { writeBytes(data) }
        File(root, "b").writeBytes(data)
        val cache = HashCache()
        val first = DuplicateFinder.scan(root, DupeOptions(), cache)
        assertEquals(1, first.size)
        assertEquals(2, cache.size)
        // Changing a file's content and time makes its old cached hash unusable.
        a.writeBytes(Random(9).nextBytes(100_000))
        a.setLastModified(a.lastModified() + 5_000)
        assertEquals(0, DuplicateFinder.scan(root, DupeOptions(), cache).size)
    }

    @Test fun hiddenFoldersSkippedByDefaultAndMinSize() {
        val root = tmp.newFolder("r")
        File(root, ".thumbnails").mkdirs()
        File(root, ".thumbnails/x").writeText("same-content")
        File(root, "y").writeText("same-content")
        File(root, "z").writeText("same-content")
        assertEquals(2, DuplicateFinder.scan(root, DupeOptions()).single().files.size)
        assertEquals(3, DuplicateFinder.scan(root, DupeOptions(skipHidden = false)).single().files.size)
        assertEquals(0, DuplicateFinder.scan(root, DupeOptions(minSize = 1000)).size)
    }

    @Test fun parallelMatchesSingleThread() {
        val root = tmp.newFolder("r")
        val rnd = Random(5)
        val blobs = List(6) { rnd.nextBytes(40_000 + it) }
        repeat(30) { i -> File(root, "d${i % 4}/f$i").apply { parentFile.mkdirs(); writeBytes(blobs[i % 6]) } }
        val one = DuplicateFinder.scan(root, DupeOptions(threads = 1))
        val four = DuplicateFinder.scan(root, DupeOptions(threads = 4))
        assertEquals(6, one.size)
        assertEquals(one.map { it.files }, four.map { it.files })
    }

    @Test fun keepNewestAndOldest() {
        val root = tmp.newFolder("r")
        val old = File(root, "old").apply { writeText("dup"); setLastModified(1_000_000_000_000) }
        val new = File(root, "new").apply { writeText("dup"); setLastModified(1_700_000_000_000) }
        val g = DuplicateFinder.scan(root)
        assertEquals(setOf(old), DuplicateFinder.keepNewestSelection(g))
        assertEquals(setOf(new), DuplicateFinder.keepOldestSelection(g))
    }

    @Test fun cancelStopsTheScan() {
        val root = tmp.newFolder("r")
        repeat(50) { File(root, "f$it").writeText("same") }
        try {
            DuplicateFinder.scan(root, DupeOptions(), isCancelled = { true })
            fail("expected cancel")
        } catch (e: CancellationException) { /* expected */ }
    }
}
