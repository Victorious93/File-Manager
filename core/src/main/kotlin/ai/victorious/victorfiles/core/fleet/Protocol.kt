package ai.victorious.victorfiles.core.fleet

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Optional RootForge-style Messenger envelope. VictorFiles is a standalone
 * product; this protocol is a stub so a later hub PR can recognize the app.
 * Fleet class is EXTERNAL_NODE: not a BOUND_COMPANION AIDL contract, no
 * hub signature permission, and it does not require DCA.
 */
const val BRIDGE_PROTOCOL_MAJOR = 1
const val BRIDGE_ACTION = "ai.victorious.victorfiles.action.BRIDGE"
const val BRIDGE_PACKAGE = "ai.victorious.victorfiles"
const val BRIDGE_PACKAGE_DEBUG = "ai.victorious.victorfiles.debug"

const val BRIDGE_MSG_REQUEST = 1
const val BRIDGE_MSG_RESPONSE = 2
const val BRIDGE_KEY_REQUEST = "request"
const val BRIDGE_KEY_RESPONSE = "response"

const val FLEET_CLASS = "external_node"

const val APP_VERSION = "0.1.0"
const val APP_ID = "filemanager"
const val APPLICATION_ID = "ai.victorious.victorfiles"

val bridgeJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

object BridgeOp {
    const val CAPABILITIES = "filemanager.capabilities.get"
    const val STATUS = "filemanager.status"
    const val LIST = "filemanager.list"

    val ALL: Set<String> = setOf(CAPABILITIES, STATUS, LIST)
}

object ErrorCategory {
    const val MALFORMED = "malformed_request"
    const val PROTOCOL = "protocol_mismatch"
    const val UNKNOWN_OPERATION = "unknown_operation"
    const val BAD_PARAMETERS = "bad_parameters"
    const val FAILED = "operation_failed"
    const val EXTERNAL_CONTROL_DISABLED = "external_control_disabled"
    const val UNAUTHORIZED_CALLER = "unauthorized_caller"
}

@Serializable
data class BridgeRequest(
    @SerialName("protocol_major") val protocolMajor: Int = BRIDGE_PROTOCOL_MAJOR,
    @SerialName("request_id") val requestId: String,
    @SerialName("target_node_id") val targetNodeId: String = "",
    val operation: String,
    @SerialName("timeout_ms") val timeoutMs: Int = 10_000,
    val parameters: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class BridgeErrorBody(val category: String, val message: String)

@Serializable
data class BridgeResponse(
    @SerialName("protocol_major") val protocolMajor: Int,
    @SerialName("request_id") val requestId: String? = null,
    @SerialName("node_id") val nodeId: String? = null,
    val ok: Boolean,
    val result: JsonObject? = null,
    val error: BridgeErrorBody? = null,
)
