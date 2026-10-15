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
    private val executor = BridgeExecutor(nodeId)

    @Test
    fun capabilitiesAreHonestStubs() {
        val response = decode(
            executor.handle(request(BridgeOp.CAPABILITIES)),
        )
        assertTrue(response.ok)
        val result = response.result!!
        assertEquals(false, result.bool("live"))
        assertEquals("stub", result.str("mode"))
        assertEquals(false, result.bool("dca_required"))
        assertEquals("pending", result.str("fleet_class"))
        assertEquals(nodeId, result.str("node_id"))
        assertFalse(result.toString().contains("LIVE control", ignoreCase = true) && result.bool("live"))
    }

    @Test
    fun statusNeverRequiresDca() {
        val response = decode(executor.handle(request(BridgeOp.STATUS)))
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
        val response = decode(executor.handle(json))
        assertTrue(response.ok)
        assertEquals("stub", response.result!!.str("mode"))
        assertEquals(false, response.result!!.bool("live"))
        assertEquals("[]", response.result!!["entries"].toString())
        assertTrue(response.result!!.str("message").contains("does not expose"))
    }

    @Test
    fun unknownOperationFailsHonestly() {
        val response = decode(executor.handle(request("filemanager.delete")))
        assertFalse(response.ok)
        assertEquals(ErrorCategory.UNKNOWN_OPERATION, response.error!!.category)
        assertTrue(response.error!!.message.contains("LIVE"))
    }

    @Test
    fun protocolMismatchRejected() {
        val line = """{"protocol_major":99,"request_id":"r1","target_node_id":"$nodeId","operation":"${BridgeOp.STATUS}"}"""
        val response = decode(executor.handle(line))
        assertFalse(response.ok)
        assertEquals(ErrorCategory.PROTOCOL, response.error!!.category)
    }

    @Test
    fun malformedJsonRejected() {
        val response = decode(executor.handle("{not json"))
        assertFalse(response.ok)
        assertEquals(ErrorCategory.MALFORMED, response.error!!.category)
    }

    @Test
    fun emptyRequestRejected() {
        val response = decode(executor.handle("  "))
        assertFalse(response.ok)
        assertEquals(ErrorCategory.MALFORMED, response.error!!.category)
    }

    @Test
    fun wrongNodeRejected() {
        val line = """{"protocol_major":1,"request_id":"r1","target_node_id":"fm-other","operation":"${BridgeOp.STATUS}"}"""
        val response = decode(executor.handle(line))
        assertFalse(response.ok)
        assertEquals(ErrorCategory.BAD_PARAMETERS, response.error!!.category)
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
