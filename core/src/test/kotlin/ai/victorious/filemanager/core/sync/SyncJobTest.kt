package ai.victorious.filemanager.core.sync

import ai.victorious.filemanager.core.net.InMemoryRemoteBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncJobTest {
    private fun remote(): Pair<InMemoryRemoteBackend, RemoteSyncFs> {
        val be = InMemoryRemoteBackend("sftp1")
        be.seedDir("/backup/phone")
        return be to RemoteSyncFs(be)
    }

    @Test fun localToRemoteOneWay_copiesAndIsIdempotent() {
        val local = MemorySyncFs().apply { seed("a.txt", "alpha", 1_000); seed("dir/b.txt", "beta", 2_000) }
        val (be, rfs) = remote()
        var job = SyncJob(id = "j", aRoot = "/sdcard/DCIM", bConnectionId = "sftp1", bRoot = "/backup/phone",
            direction = SyncDirection.ONE_WAY_A_TO_B)
        val first = SyncJobRunner.run(job, local, rfs, nowMs = 5)
        assertEquals(2, first.plan.copyCount)
        assertEquals("beta", be.openInput("/backup/phone/dir/b.txt").readBytes().decodeToString())
        job = first.job
        assertEquals(setOf("a.txt", "dir/b.txt"), job.previousBoth)
        assertEquals(5L, job.lastRunEpochMs)
        assertEquals(0, SyncJobRunner.run(job, local, rfs).plan.actions.size)
    }

    @Test fun twoWayWithRemote_doesNotPingPongBecauseRemoteMtimesDiffer() {
        val local = MemorySyncFs().apply { seed("notes.md", "v1", 1_000) }
        val (_, rfs) = remote()
        var job = SyncJob(id = "j", aRoot = "/x", bConnectionId = "sftp1", bRoot = "/backup/phone")
        job = SyncJobRunner.run(job, local, rfs).job
        // Uploaded copy has a "now" mtime on the remote, unlike the local 1_000.
        assertTrue(rfs.listRecursive("/backup/phone").single().modifiedEpochMs != 1_000L)
        val second = SyncJobRunner.run(job, local, rfs)
        assertEquals(emptyList<SyncAction>(), second.plan.actions)
        // A real local edit is still picked up.
        local.seed("notes.md", "v2-longer", 9_999_999_999_999)
        val third = SyncJobRunner.run(second.job, local, rfs)
        assertEquals(listOf(SyncActionKind.COPY_A_TO_B), third.plan.actions.map { it.kind })
        assertEquals("v2-longer", String(rfs.read("/backup/phone", "notes.md")))
    }

    @Test fun remoteToLocal_downloads_andDeletesGoThroughTrashHook() {
        val local = MemorySyncFs()
        val (be, rfs) = remote()
        be.seedFile("/backup/phone/r.txt", "remote".toByteArray())
        var job = SyncJob(id = "j", aRoot = "/local", bConnectionId = "sftp1", bRoot = "/backup/phone",
            direction = SyncDirection.ONE_WAY_B_TO_A)
        job = SyncJobRunner.run(job, local, rfs).job
        assertEquals("remote", String(local.read("/local", "r.txt")))
        be.delete("/backup/phone/r.txt")
        val trashed = mutableListOf<String>()
        SyncJobRunner.run(job, local, rfs, trashOnDelete = { side, rel -> trashed += "$side:$rel"; local.delete("/local", rel); true })
        assertEquals(listOf("A:r.txt"), trashed)
        assertFalse(local.exists("/local", "r.txt"))
    }

    @Test fun remoteToRemote_works() {
        val (_, src) = remote()
        val dstBe = InMemoryRemoteBackend("dav").apply { seedDir("/mirror") }
        src.write("/backup/phone", "x.bin", byteArrayOf(1, 2, 3))
        val job = SyncJob(id = "j", aConnectionId = "sftp1", aRoot = "/backup/phone", bConnectionId = "dav", bRoot = "/mirror",
            direction = SyncDirection.ONE_WAY_A_TO_B)
        SyncJobRunner.run(job, src, RemoteSyncFs(dstBe))
        assertEquals(listOf<Byte>(1, 2, 3), dstBe.openInput("/mirror/x.bin").readBytes().toList())
    }

    @Test fun storeRoundTripAndLegacyFormat() {
        val legacy = """{"old":{"aRoot":"/a","bRoot":"/b","direction":"ONE_WAY_A_TO_B","conflict":"KEEP_BOTH","previousBoth":["x"]}}"""
        val jobs = SyncJobStore.decode(legacy)
        val old = jobs.getValue("old")
        assertEquals("old", old.id)
        assertEquals(SyncDirection.ONE_WAY_A_TO_B, old.direction)
        assertEquals(setOf("x"), old.previousBoth)
        assertFalse(old.involvesRemote)
        val remoteJob = SyncJob(id = "r", aRoot = "/a", bConnectionId = "c1", bRoot = "/r", wifiOnly = true,
            requiresCharging = true, intervalHours = 6, snapshot = mapOf("x" to PairStamp(1, 2, 1, 3)))
        val back = SyncJobStore.decode(SyncJobStore.encode(jobs + ("r" to remoteJob)))
        assertEquals(remoteJob, back["r"])
        assertEquals(old, back["old"])
        assertEquals(emptyMap<String, SyncJob>(), SyncJobStore.decode("not json"))
    }

    @Test fun constraints() {
        val local = SyncJob(aRoot = "/a", bRoot = "/b")
        assertEquals(SyncRunConstraints(SyncRunConstraints.Network.NOT_REQUIRED, false, false), SyncRunConstraints.forJob(local))
        val remote = local.copy(bConnectionId = "c")
        assertEquals(SyncRunConstraints.Network.CONNECTED, SyncRunConstraints.forJob(remote).network)
        assertTrue(SyncRunConstraints.forJob(remote).batteryNotLow)
        val wifiCharging = remote.copy(wifiOnly = true, requiresCharging = true)
        assertEquals(SyncRunConstraints(SyncRunConstraints.Network.UNMETERED, true, true), SyncRunConstraints.forJob(wifiCharging))
        assertTrue(SyncRunConstraints.forJob(local.copy(requiresCharging = true)).requiresCharging)
    }
}
