package ai.victorious.filemanager.core.bridge

import org.junit.Assert.*
import org.junit.Test

/** Every operation BridgeFileIo uses must exist in the shared contract and need only the file capabilities. */
class BridgeContractTest {
    private val text = javaClass.getResource("/bridge-contract-v1.json")!!.readText()
    private val ops = Regex("\"operations\"\\s*:\\s*\\{(.*?)}", RegexOption.DOT_MATCHES_ALL).find(text)!!.groupValues[1]
        .let { b -> Regex("\"([^\"]+)\"\\s*:\\s*\"([^\"]+)\"").findAll(b).associate { it.groupValues[1] to it.groupValues[2] } }

    @Test fun usedOperationsExistAndNeedOnlyFileCapabilities() {
        val used = BridgeFileIo::class.java.getResourceAsStream("BridgeFileIo.class")!!.readBytes().toString(Charsets.ISO_8859_1)
        listOf("files.stat", "files.list", "files.read", "files.write", "files.mkdir", "files.delete").forEach {
            assertTrue("$it must be in the contract", it in ops.keys)
            assertTrue(it, used.contains(it))
            assertTrue(ops.getValue(it) == "files.read" || ops.getValue(it) == "files.write")
        }
    }

    @Test fun thereIsNoRenameOrCopyPrimitiveSoBridgeFileIoMustNotPretendToHaveOne() {
        assertTrue(ops.keys.none { it.contains("rename") || it.contains("copy") || it.contains("move") })
    }

    @Test fun chunkMatchesTheServiceLimit() = assertEquals(1024 * 1024, BridgeFileIo.CHUNK)
}
