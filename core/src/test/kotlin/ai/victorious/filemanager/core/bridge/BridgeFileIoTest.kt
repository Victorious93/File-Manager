package ai.victorious.filemanager.core.bridge

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

/**
 * An in-memory stand-in for the Bridge service that applies the same rules the real one does: approved roots only,
 * no `..`, no symlinks, 1 MiB per call, empty-folder delete only. It exists to test [BridgeFileIo]'s behaviour
 * (chunking, cancellation, cleanup, error mapping). It is NOT proof the real service behaves this way - that is
 * covered by ShizukuV's own bridge-core tests.
 */
private class FakeBridge(val root: File) : BridgeFileGateway {
    var cancelAfterCalls = Int.MAX_VALUE
    var revokeAfterCalls = Int.MAX_VALUE
    val calls = ArrayList<Pair<String, Map<String, Any>>>()
    override fun isCancelled() = calls.size >= cancelAfterCalls

    private fun deny(code: String) = BridgeReply(false, code, code)
    private fun scoped(path: String): File? {
        if (!path.startsWith("/") || path.split('/').contains("..")) return null
        val f = File(path).canonicalFile
        return if (f.path == root.path || f.path.startsWith(root.path + "/")) f else null
    }

    override fun call(operation: String, args: Map<String, Any>): BridgeReply {
        calls += operation to args
        if (calls.size > revokeAfterCalls) return deny("REVOKED")
        val path = args["path"] as String
        val f = scoped(path) ?: return deny("PATH_OUT_OF_SCOPE")
        return when (operation) {
            "files.stat" -> if (!f.exists()) deny("NOT_FOUND") else BridgeReply(true, data = mapOf("is_dir" to f.isDirectory, "size" to f.length()))
            "files.list" -> {
                val names = f.list()!!.sorted(); val off = (args["offset"] as Long).toInt(); val lim = (args["limit"] as Long).toInt()
                val page = names.drop(off).take(lim)
                BridgeReply(true, data = mapOf("entries" to page.map { n -> File(f, n).let { c -> "${if (c.isDirectory) "d" else "f"}\t${c.length()}\t${c.lastModified()}\t$n" } },
                    "has_more" to (off + page.size < names.size)))
            }
            "files.read" -> {
                val off = args["offset"] as Long; val len = (args["length"] as Long).toInt()
                require(len <= BridgeFileIo.CHUNK)
                RandomAccessFile(f, "r").use { r ->
                    val n = minOf(len.toLong(), maxOf(0L, r.length() - off)).toInt(); val b = ByteArray(n); r.seek(off); r.readFully(b)
                    BridgeReply(true, data = mapOf("data" to b, "eof" to (off + n >= r.length())))
                }
            }
            "files.write" -> {
                val d = args["data"] as ByteArray
                if (d.size > BridgeFileIo.CHUNK) return deny("TOO_LARGE")
                RandomAccessFile(f, "rw").use { r -> if (args["truncate"] == true) r.setLength(0); r.seek(args["offset"] as Long); r.write(d) }
                BridgeReply(true)
            }
            "files.mkdir" -> if (f.mkdir()) BridgeReply(true) else deny("IO_ERROR")
            "files.delete" -> if (f.isDirectory && f.list()!!.isNotEmpty()) deny("INVALID_ARGUMENT") else if (f.delete()) BridgeReply(true) else deny("IO_ERROR")
            else -> deny("UNSUPPORTED_OPERATION")
        }
    }
}

class BridgeFileIoTest {
    @get:Rule val tmp = TemporaryFolder()
    private val root get() = File(tmp.root, "approved").apply { mkdirs() }.canonicalFile
    private fun io(b: FakeBridge) = BridgeFileIo(b)

    @Test fun outOfScopeIsAnErrorNotNonExistence() {
        val b = FakeBridge(root); val io = io(b)
        val e = assertThrows(BridgeFileException::class.java) { io.exists("/data/data/com.other/databases/x.db") }
        assertTrue(e.isOutOfScope)
        assertTrue(assertThrows(BridgeFileException::class.java) { io.list("${root.path}/../outside") }.isOutOfScope)
        assertFalse("a genuinely missing file inside scope is just absent", io.exists("${root.path}/nope"))
    }

    @Test fun textRoundTripAndListingSortedFoldersFirst() {
        val b = FakeBridge(root); val io = io(b)
        io.mkdirs("${root.path}/a/b"); io.writeText("${root.path}/a/b/n.txt", "héllo")
        File(root, "z.txt").writeText("z"); File(root, ".hidden").writeText("h")
        assertEquals("héllo", io.readText("${root.path}/a/b/n.txt"))
        val l = io.list(root.path)
        assertEquals(listOf("a", ".hidden", "z.txt"), l.map { it.name })
        assertTrue(l.first { it.name == ".hidden" }.isHidden)
    }

    @Test fun largeCopyIsChunkedAtOneMiBAndExact() {
        val b = FakeBridge(root); val io = io(b); val progress = ArrayList<Long>()
        val src = File(root, "big.bin").apply { writeBytes(ByteArray(3_200_000) { (it * 7).toByte() }) }
        BridgeFileIo(b, onProgress = { c, _ -> progress += c }).copy(src.path, "${root.path}/copy.bin")
        assertArrayEquals(src.readBytes(), File(root, "copy.bin").readBytes())
        assertTrue(b.calls.filter { it.first == "files.write" }.all { (it.second["data"] as ByteArray).size <= BridgeFileIo.CHUNK })
        assertEquals(3_200_000L, progress.last()); assertTrue(progress.size >= 4)
    }

    @Test fun cancellingMidCopyStopsAndRemovesThePartialDestination() {
        val b = FakeBridge(root); val io = io(b)
        File(root, "big.bin").writeBytes(ByteArray(4_000_000))
        b.cancelAfterCalls = 5
        val e = assertThrows(BridgeFileException::class.java) { io.copy("${root.path}/big.bin", "${root.path}/dst.bin") }
        assertTrue(e.isCancelled)
        assertFalse("no half-written file left behind", File(root, "dst.bin").exists())
    }

    @Test fun accessRevokedMidTransferSurfacesAsAccessLossAndNeverFallsBack() {
        val b = FakeBridge(root); val io = io(b)
        File(root, "big.bin").writeBytes(ByteArray(3_000_000))
        b.revokeAfterCalls = 4
        val e = assertThrows(BridgeFileException::class.java) { io.copy("${root.path}/big.bin", "${root.path}/dst.bin") }
        assertTrue(e.isAccessLoss)
        assertTrue("only files.* operations were ever attempted", b.calls.all { it.first.startsWith("files.") })
    }

    @Test fun textViewRefusesHugeFilesInsteadOfLoadingThem() {
        val b = FakeBridge(root)
        File(root, "huge.txt").writeBytes(ByteArray(20_000))
        val e = assertThrows(BridgeFileException::class.java) { BridgeFileIo(b, maxTextBytes = 10_000).readText("${root.path}/huge.txt") }
        assertEquals("TOO_LARGE", e.code)
        assertTrue("it did not even start reading", b.calls.none { it.first == "files.read" })
    }

    @Test fun recursiveDeleteEmptiesFoldersBottomUpBecauseTheBridgeOnlyDeletesEmptyOnes() {
        val b = FakeBridge(root); val io = io(b)
        File(root, "t/u").mkdirs(); File(root, "t/u/f").writeText("x"); File(root, "t/g").writeText("y")
        io.deleteRecursively("${root.path}/t")
        assertFalse(File(root, "t").exists())
    }

    @Test fun moveCopiesThenDeletesAndKeepsSourceIfCopyFails() {
        val b = FakeBridge(root); val io = io(b)
        File(root, "s.bin").writeBytes(ByteArray(2_500_000))
        b.revokeAfterCalls = 3
        assertThrows(BridgeFileException::class.java) { io.move("${root.path}/s.bin", "${root.path}/d.bin") }
        assertTrue("source untouched when the copy did not complete", File(root, "s.bin").exists())
    }

    @Test fun emptyFileCopiesToAnEmptyFile() {
        val b = FakeBridge(root); val io = io(b)
        File(root, "e").writeBytes(ByteArray(0)); io.copy("${root.path}/e", "${root.path}/e2")
        assertTrue(File(root, "e2").exists()); assertEquals(0L, File(root, "e2").length())
    }
}
