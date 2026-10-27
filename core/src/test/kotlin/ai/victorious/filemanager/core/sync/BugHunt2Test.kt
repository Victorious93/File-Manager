package ai.victorious.filemanager.core.sync

import ai.victorious.filemanager.core.fs.FileEntry
import ai.victorious.filemanager.core.listing.ListingOptions
import ai.victorious.filemanager.core.listing.ListingWork
import ai.victorious.filemanager.core.net.InMemoryRemoteBackend
import ai.victorious.filemanager.core.net.SftpKeys
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Regression tests for the second bug-hunt pass. */
class BugHunt2Test {

    /** Generates [size] bytes lazily; nothing is held in memory. Optionally fails after [failAfter] bytes. */
    private class BigStream(private val size: Long, private val failAfter: Long = Long.MAX_VALUE) : InputStream() {
        var pos = 0L
        override fun read(): Int {
            if (pos >= failAfter) throw IOException("connection dropped")
            return if (pos >= size) -1 else (pos++ % 251).toInt()
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= size) return -1
            if (pos >= failAfter) throw IOException("connection dropped")
            val n = minOf(len.toLong(), size - pos, failAfter - pos).toInt()
            for (i in 0 until n) b[off + i] = ((pos + i) % 251).toByte()
            pos += n
            return n
        }
    }

    /** A side whose files only exist as streams; the whole-file methods must never be used. */
    private class StreamOnlyFs(private val files: Map<String, Long>, private val failAfter: Long = Long.MAX_VALUE) : SyncFs {
        val received = mutableMapOf<String, Long>()
        override fun listRecursive(root: String) = files.map { (k, v) -> SyncFile(k, v, 1_000) }
        override fun read(root: String, relative: String): ByteArray = fail("read() loads the whole file into memory") as Nothing
        override fun write(root: String, relative: String, bytes: ByteArray, modifiedEpochMs: Long) { fail("write(ByteArray) used") }
        override fun openRead(root: String, relative: String): InputStream = BigStream(files.getValue(relative), failAfter)
        override fun writeFrom(root: String, relative: String, input: InputStream, modifiedEpochMs: Long) {
            val buf = ByteArray(64 * 1024); var n = 0L
            while (true) { val r = input.read(buf); if (r < 0) break; n += r }
            received[relative] = n
        }
        override fun delete(root: String, relative: String) {}
        override fun mkdir(root: String, relative: String) {}
        override fun exists(root: String, relative: String) = relative in received
    }

    @Test fun copyStreams_multiGigabyteFileNeverLoadedIntoMemory() {
        val big = 3L * 1024 * 1024 * 1024 // 3 GiB: cannot fit in a ByteArray at all
        val a = StreamOnlyFs(mapOf("video.mp4" to big))
        val b = StreamOnlyFs(emptyMap())
        val plan = FolderSync.plan(a.listRecursive("").associateBy { it.relativePath }, emptyMap(),
            SyncDirection.ONE_WAY_A_TO_B, ConflictPolicy.NEWEST)
        FolderSync.apply(plan, a, "", b, "")
        assertEquals(big, b.received["video.mp4"])
    }

    @Test fun remoteUpload_failureMidFile_leavesNoPartialAndKeepsOldCopy() {
        val be = InMemoryRemoteBackend("r").apply { seedDir("/dst"); seedFile("/dst/f.bin", "old".toByteArray()) }
        val dst = RemoteSyncFs(be)
        try {
            dst.writeFrom("/dst", "f.bin", BigStream(1_000_000, failAfter = 300_000))
            fail("expected failure")
        } catch (_: IOException) {}
        assertEquals("old", be.openInput("/dst/f.bin").readBytes().decodeToString())
        assertEquals(listOf("f.bin"), be.list("/dst").map { it.name })
    }

    @Test fun remoteUpload_successReplacesTarget_andTempFilesAreNeverListed() {
        val be = InMemoryRemoteBackend("r").apply { seedDir("/dst"); seedFile("/dst/f.bin", "old".toByteArray()) }
        val dst = RemoteSyncFs(be)
        dst.writeFrom("/dst", "sub/f.bin", "new".byteInputStream())
        dst.writeFrom("/dst", "f.bin", "new".byteInputStream())
        assertEquals("new", be.openInput("/dst/f.bin").readBytes().decodeToString())
        be.seedFile("/dst/${SYNC_PART_PREFIX}abc-x.bin", "leftover".toByteArray())
        assertEquals(setOf("f.bin", "sub", "sub/f.bin"), dst.listRecursive("/dst").map { it.relativePath }.toSet())
        assertTrue(isSyncPart("a/${SYNC_PART_PREFIX}1-x"))
    }

    @Test fun listing_filterIsDebounced_otherChangesAreNot() {
        val o = ListingOptions()
        assertEquals(0L, ListingWork.debounceFor(null, o))
        assertEquals(ListingWork.FILTER_DEBOUNCE_MS, ListingWork.debounceFor(o, o.copy(filter = "a")))
        assertEquals(0L, ListingWork.debounceFor(o, o.copy(ascending = false)))
        assertEquals(0L, ListingWork.debounceFor(o, o.copy(filter = "a", ascending = false)))
    }

    @Test fun listing_sortsOnTheGivenBackgroundDispatcher_andTypingCancelsStaleWork() = runBlocking {
        val ran = AtomicInteger()
        val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "listing-bg").also { it.isDaemon = true } }
        val bg = java.util.concurrent.Executor { c -> ran.incrementAndGet(); pool.execute(c) }.asCoroutineDispatcher()
        val entries = (0 until 10_000).map { FileEntry("/x/f$it", "f$it", false, 0, 0, false) }.shuffled()
        val out = ListingWork.compute(entries, ListingOptions(), null, bg)
        assertEquals(10_000, out.size)
        assertTrue("work was dispatched to the background pool", ran.get() > 0)

        val before = ran.get()
        val stale = async { ListingWork.compute(entries, ListingOptions(filter = "f1"), ListingOptions(), bg) }
        delay(50); stale.cancel() // a newer keystroke arrives inside the debounce window
        assertTrue(stale.isCancelled)
        assertEquals("cancelled during debounce, so no sort ran", before, ran.get())
        pool.shutdown()
    }

    @Test fun sftpPassphraseOnlyChange_isApplied() {
        val key = "-----BEGIN OPENSSH PRIVATE KEY-----\nAAAAtest\n-----END OPENSSH PRIVATE KEY-----"
        val stored = SftpKeys.encodeSecret(key, "old")
        val updated = SftpKeys.secretForSave(stored, null, "new")
        assertNotNull(updated)
        val d = SftpKeys.decodeSecret(updated!!)
        assertEquals("new", d.passphrase)
        assertEquals(SftpKeys.decodeSecret(stored).keyText, d.keyText)
        assertNull("nothing typed → keep stored secret", SftpKeys.secretForSave(stored, "", null))
        assertNull("no stored key to apply it to", SftpKeys.secretForSave(null, null, "x"))
        assertEquals("p", SftpKeys.decodeSecret(SftpKeys.secretForSave(stored, key, "p")!!).passphrase)
    }

    @Test fun manualJob_roundTripsThroughWorkData() {
        val job = SyncJob(id = "adhoc", aRoot = "/a", bConnectionId = "s", bRoot = "/b", direction = SyncDirection.ONE_WAY_A_TO_B)
        assertEquals(job, SyncJobStore.decodeOne(SyncJobStore.encodeOne(job)))
        assertNull(SyncJobStore.decodeOne("not json"))
        assertTrue("fits WorkManager's 10 KB Data limit", SyncJobStore.encodeOne(job).length < 10_000)
        assertFalse(SyncJobStore.encodeOne(job).isEmpty())
    }
}
