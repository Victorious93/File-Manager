package ai.victorious.filemanager.core.fleet

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeExecutorTest {
    private val nodeId = "fm-0123456789abcdef0123456789abcdef"
    private var enabled = true
    private val executor = BridgeExecutor(nodeId, externalControlEnabled = { enabled })
    private val dca = CallerIdentity(10123, listOf("ai.droidcommand.app"), mapOf("ai.droidcommand.app" to listOf("aa".repeat(32))))

    @Test
    fun capabilitiesAreHonestStubs() {
        val response = decode(
            executor.handle(request(BridgeOp.CAPABILITIES), dca),
        )
        assertTrue(response.ok)
        val result = response.result!!
        assertEquals(false, result.bool("live"))
        assertEquals("stub", result.str("mode"))
        assertEquals(false, result.bool("dca_required"))
        assertEquals("external_node", result.str("fleet_class"))
        assertEquals(true, result.bool("enabled"))
        assertEquals(nodeId, result.str("node_id"))
        assertFalse(result.toString().contains("LIVE control", ignoreCase = true) && result.bool("live"))
    }

    @Test
    fun statusNeverRequiresDca() {
        val response = decode(executor.handle(request(BridgeOp.STATUS), dca))
        assertTrue(response.ok)
        assertEquals("filemanager", response.result!!.str("id"))
        assertEquals("0.1.0", response.result!!.str("version"))
        assertEquals("ai.victorious.filemanager", response.result!!.str("application_id"))
        assertEquals(false, response.result!!.bool("dca_required"))
        assertEquals(false, response.result!!.bool("live"))
        assertTrue(response.result!!.str("message").contains("without DroidCommand AI"))
    }

    @Test
    fun listStubDoesNotExposeFiles() {
        val json = request(
            BridgeOp.LIST,
            params = JsonObject(mapOf("path" to JsonPrimitive("/sdcard"))),
        )
        val response = decode(executor.handle(json, dca))
        assertTrue(response.ok)
        assertEquals("stub", response.result!!.str("mode"))
        assertEquals(false, response.result!!.bool("live"))
        assertEquals("[]", response.result!!["entries"].toString())
        assertTrue(response.result!!.str("message").contains("does not expose"))
    }

    @Test
    fun unknownOperationFailsHonestly() {
        val response = decode(executor.handle(request("filemanager.delete"), dca))
        assertFalse(response.ok)
        assertEquals(ErrorCategory.UNKNOWN_OPERATION, response.error!!.category)
        assertTrue(response.error!!.message.contains("LIVE"))
    }

    @Test
    fun protocolMismatchRejected() {
        val line = """{"protocol_major":99,"request_id":"r1","target_node_id":"$nodeId","operation":"${BridgeOp.STATUS}"}"""
        val response = decode(executor.handle(line, dca))
        assertFalse(response.ok)
        assertEquals(ErrorCategory.PROTOCOL, response.error!!.category)
    }

    @Test
    fun malformedJsonRejected() {
        val response = decode(executor.handle("{not json", dca))
        assertFalse(response.ok)
        assertEquals(ErrorCategory.MALFORMED, response.error!!.category)
    }

    @Test
    fun emptyRequestRejected() {
        val response = decode(executor.handle("  ", dca))
        assertFalse(response.ok)
        assertEquals(ErrorCategory.MALFORMED, response.error!!.category)
    }

    @Test
    fun wrongNodeRejected() {
        val line = """{"protocol_major":1,"request_id":"r1","target_node_id":"fm-other","operation":"${BridgeOp.STATUS}"}"""
        val response = decode(executor.handle(line, dca))
        assertFalse(response.ok)
        assertEquals(ErrorCategory.BAD_PARAMETERS, response.error!!.category)
    }

    @Test
    fun disabledByDefault() {
        val fresh = BridgeExecutor(nodeId)
        val r = decode(fresh.handle(request(BridgeOp.LIST), dca))
        assertFalse(r.ok)
        assertEquals(ErrorCategory.EXTERNAL_CONTROL_DISABLED, r.error!!.category)
    }

    @Test
    fun disabledBlocksListAndUnknownOps() {
        enabled = false
        for (op in listOf(BridgeOp.LIST, "filemanager.delete")) {
            val r = decode(executor.handle(request(op), dca))
            assertFalse(r.ok)
            assertEquals(ErrorCategory.EXTERNAL_CONTROL_DISABLED, r.error!!.category)
        }
    }

    @Test
    fun disabledStillAnswersReadOnlyWithEnabledFalse() {
        enabled = false
        for (op in listOf(BridgeOp.CAPABILITIES, BridgeOp.STATUS)) {
            val r = decode(executor.handle(request(op), dca))
            assertTrue(r.ok)
            assertEquals(false, r.result!!.bool("enabled"))
            assertEquals("external_node", r.result!!.str("fleet_class"))
        }
    }

    @Test
    fun debugHubPackageAllowed() {
        val dbg = CallerIdentity(10124, listOf("ai.droidcommand.app.debug"))
        assertTrue(decode(executor.handle(request(BridgeOp.LIST), dbg)).ok)
    }

    @Test
    fun otherPackageRejectedEvenWhenDisabled() {
        val evil = CallerIdentity(10200, listOf("com.example.evil"))
        for (on in listOf(true, false)) {
            enabled = on
            val r = decode(executor.handle(request(BridgeOp.STATUS), evil))
            assertFalse(r.ok)
            assertEquals(ErrorCategory.UNAUTHORIZED_CALLER, r.error!!.category)
        }
    }

    @Test
    fun unresolvedCallerRejected() {
        val r = decode(executor.handle(request(BridgeOp.CAPABILITIES), null))
        assertEquals(ErrorCategory.UNAUTHORIZED_CALLER, r.error!!.category)
        val empty = decode(executor.handle(request(BridgeOp.CAPABILITIES), CallerIdentity(1, emptyList())))
        assertEquals(ErrorCategory.UNAUTHORIZED_CALLER, empty.error!!.category)
    }

    @Test
    fun sharedUidWithAllowlistedPackageAllowed() {
        val shared = CallerIdentity(10300, listOf("com.other", "ai.droidcommand.app"))
        assertTrue(decode(executor.handle(request(BridgeOp.STATUS), shared)).ok)
    }

    @Test
    fun certPinningEnforcedWhenConfigured() {
        val good = "AA:".repeat(31) + "AA"
        val pinned = BridgeExecutor(
            nodeId,
            policy = CallerPolicy(certPins = CallerPolicy.parsePins("ai.droidcommand.app=$good")),
            externalControlEnabled = { true },
        )
        assertTrue(decode(pinned.handle(request(BridgeOp.LIST), dca)).ok)
        val wrongCert = CallerIdentity(10123, listOf("ai.droidcommand.app"), mapOf("ai.droidcommand.app" to listOf("bb".repeat(32))))
        val r = decode(pinned.handle(request(BridgeOp.LIST), wrongCert))
        assertEquals(ErrorCategory.UNAUTHORIZED_CALLER, r.error!!.category)
        val noCert = CallerIdentity(10123, listOf("ai.droidcommand.app"))
        assertEquals(ErrorCategory.UNAUTHORIZED_CALLER, decode(pinned.handle(request(BridgeOp.LIST), noCert)).error!!.category)
        // Unpinned allowlisted package still allowed by name.
        assertTrue(decode(pinned.handle(request(BridgeOp.LIST), CallerIdentity(1, listOf("ai.droidcommand.app.debug")))).ok)
    }

    @Test
    fun parsePinsHandlesEmptyAndMultiple() {
        assertTrue(CallerPolicy.parsePins("").isEmpty())
        val p = CallerPolicy.parsePins("a=AB:CD|ef ; b=12")
        assertEquals(setOf("abcd", "ef"), p["a"])
        assertEquals(setOf("12"), p["b"])
    }

    private fun request(operation: String, params: JsonObject = JsonObject(emptyMap())): String {
        return bridgeJson.encodeToString(
            BridgeRequest(
                protocolMajor = BRIDGE_PROTOCOL_MAJOR,
                requestId = "r1",
                targetNodeId = nodeId,
                operation = operation,
                parameters = params,
            ),
        )
    }

    private fun decode(line: String): BridgeResponse = bridgeJson.decodeFromString(line)

    private fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content
    private fun JsonObject.bool(key: String): Boolean = this[key]!!.jsonPrimitive.content.toBooleanStrict()
}
