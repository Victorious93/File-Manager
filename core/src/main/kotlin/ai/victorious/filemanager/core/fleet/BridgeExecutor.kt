package ai.victorious.filemanager.core.fleet

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Honest Phase 1 fleet stub. Never claims LIVE control. Never requires
 * DroidCommand AI. Hub recognition needs a later PR in droidcommand-AI;
 * fleet class (EXTERNAL_NODE vs BOUND_COMPANION) is pending owner choice.
 */
class BridgeExecutor(
    private val nodeId: String,
    private val version: String = APP_VERSION,
) {
    fun handle(requestLine: String): String {
        if (requestLine.isBlank()) {
            return fail(null, ErrorCategory.MALFORMED, "empty request")
        }
        val request = try {
            bridgeJson.decodeFromString<BridgeRequest>(requestLine.trim())
        } catch (t: Throwable) {
            return fail(null, ErrorCategory.MALFORMED, t.message ?: "malformed JSON")
        }
        if (request.protocolMajor != BRIDGE_PROTOCOL_MAJOR) {
            return fail(
                request.requestId,
                ErrorCategory.PROTOCOL,
                "unsupported protocol_major ${request.protocolMajor}; this stub speaks $BRIDGE_PROTOCOL_MAJOR",
            )
        }
        if (request.targetNodeId.isNotBlank() && request.targetNodeId != nodeId) {
            return fail(
                request.requestId,
                ErrorCategory.BAD_PARAMETERS,
                "wrong node: this File Manager node is $nodeId",
            )
        }
        return when (request.operation) {
            BridgeOp.CAPABILITIES -> ok(request.requestId, capabilities())
            BridgeOp.STATUS -> ok(request.requestId, status())
            BridgeOp.LIST -> ok(request.requestId, listStub(request.parameters))
            else -> fail(
                request.requestId,
                ErrorCategory.UNKNOWN_OPERATION,
                "stub does not implement '${request.operation}' and will not pretend LIVE control",
            )
        }
    }

    private fun capabilities(): JsonObject = buildJsonObject {
        put("protocol_major", JsonPrimitive(BRIDGE_PROTOCOL_MAJOR))
        put("node_kind", JsonPrimitive("android-host-app"))
        put("app", JsonPrimitive("victorious-filemanager"))
        put("app_version", JsonPrimitive(version))
        put("node_id", JsonPrimitive(nodeId))
        put("live", JsonPrimitive(false))
        put("mode", JsonPrimitive("stub"))
        put("dca_required", JsonPrimitive(false))
        put("fleet_class", JsonPrimitive("pending"))
        put(
            "fleet_class_note",
            JsonPrimitive("Owner has not chosen EXTERNAL_NODE vs BOUND_COMPANION. Hub recognition requires a later droidcommand-AI PR."),
        )
        put(
            "operations",
            JsonArray(
                listOf(
                    opJson(BridgeOp.CAPABILITIES, "read", stub = false),
                    opJson(BridgeOp.STATUS, "read", stub = false),
                    opJson(BridgeOp.LIST, "read", stub = true),
                ),
            ),
        )
    }

    private fun status(): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(APP_ID))
        put("version", JsonPrimitive(version))
        put("application_id", JsonPrimitive(APPLICATION_ID))
        put("live", JsonPrimitive(false))
        put("mode", JsonPrimitive("stub"))
        put("dca_required", JsonPrimitive(false))
        put("fleet_class", JsonPrimitive("pending"))
        put(
            "message",
            JsonPrimitive(
                "Standalone file manager. Works without DroidCommand AI. " +
                    "This Messenger stub is not LIVE fleet control.",
            ),
        )
    }

    private fun listStub(parameters: JsonObject): JsonObject {
        val path = parameters["path"]?.let { if (it is JsonPrimitive) it.content else null }
        return buildJsonObject {
            put("live", JsonPrimitive(false))
            put("mode", JsonPrimitive("stub"))
            put("path", JsonPrimitive(path ?: ""))
            put("entries", JsonArray(emptyList()))
            put(
                "message",
                JsonPrimitive(
                    "Remote listing is a stub and does not expose device files. Use the File Manager UI.",
                ),
            )
        }
    }

    private fun opJson(name: String, tier: String, stub: Boolean) = buildJsonObject {
        put("name", JsonPrimitive(name))
        put("tier", JsonPrimitive(tier))
        put("stub", JsonPrimitive(stub))
    }

    private fun ok(requestId: String?, result: JsonObject): String =
        bridgeJson.encodeToString(
            BridgeResponse(
                protocolMajor = BRIDGE_PROTOCOL_MAJOR,
                requestId = requestId,
                nodeId = nodeId,
                ok = true,
                result = result,
                error = null,
            ),
        )

    private fun fail(requestId: String?, category: String, message: String): String =
        bridgeJson.encodeToString(
            BridgeResponse(
                protocolMajor = BRIDGE_PROTOCOL_MAJOR,
                requestId = requestId,
                nodeId = nodeId,
                ok = false,
                result = null,
                error = BridgeErrorBody(category, message),
            ),
        )
}
