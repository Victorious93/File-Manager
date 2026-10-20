package ai.victorious.filemanager.core.fleet

import ai.victorious.filemanager.core.priv.ShizukuState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Honest Phase 1 fleet stub. Never claims LIVE control. Never requires
 * DroidCommand AI. Fleet class is EXTERNAL_NODE.
 *
 * Gating order (see docs/FLEET_BRIDGE.md):
 * 1. envelope parse / protocol_major / target node
 * 2. caller identity ([CallerPolicy]) -> unauthorized_caller
 * Frozen v1 envelope ops are routed to [EnvelopeExecutor] first (own gating/error codes).
 * 3. external-control toggle -> external_control_disabled, except the
 *    read-only capabilities.get / status which answer with enabled:false
 */
class BridgeExecutor(
    private val nodeId: String,
    private val version: String = APP_VERSION,
    private val policy: CallerPolicy = CallerPolicy(),
    private val externalControlEnabled: () -> Boolean = { false },
    packageName: String = APPLICATION_ID,
    clock: () -> Long = System::currentTimeMillis,
    shizukuState: () -> ShizukuState? = { null },
    replayGuard: ReplayGuard = ReplayGuard(),
    confirmation: RemoteConfirmation = RemoteConfirmation.NEVER,
    extraOps: List<NodeOp> = emptyList(),
) {
    /** Frozen fleet envelope v1 (capabilities.get, remote.command, macro.*, node.status.get). */
    val envelope = EnvelopeExecutor(
        nodeId = nodeId,
        packageName = packageName,
        policy = policy,
        externalControlEnabled = externalControlEnabled,
        clock = clock,
        shizukuState = shizukuState,
        replay = replayGuard,
        confirmation = confirmation,
        extraOps = extraOps,
        legacyStatus = { status(externalControlEnabled()) },
    )

    fun handle(requestLine: String, caller: CallerIdentity?): String {
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
        if (request.operation in EnvelopeOp.ALL) {
            val out = envelope.handle(request, caller)
            return if (out.ok) ok(request.requestId, out.result!!) else fail(request.requestId, out.category!!, out.message.orEmpty())
        }
        if (request.targetNodeId.isNotBlank() && request.targetNodeId != nodeId) {
            return fail(
                request.requestId,
                ErrorCategory.BAD_PARAMETERS,
                "wrong node: this VictorFiles node is $nodeId",
            )
        }
        val decision = policy.evaluate(caller)
        if (!decision.allowed) {
            return fail(request.requestId, ErrorCategory.UNAUTHORIZED_CALLER, decision.reason)
        }
        if (!decision.mayInvoke(request.operation)) {
            return fail(request.requestId, ErrorCategory.UNAUTHORIZED_CALLER, "caller ${decision.matchedPackage} may not invoke '${request.operation}'")
        }
        val enabled = externalControlEnabled()
        if (!enabled && request.operation != BridgeOp.CAPABILITIES && request.operation != BridgeOp.STATUS) {
            return fail(
                request.requestId,
                ErrorCategory.EXTERNAL_CONTROL_DISABLED,
                "External control is off. Enable it in VictorFiles Settings.",
            )
        }
        return when (request.operation) {
            BridgeOp.CAPABILITIES -> ok(request.requestId, capabilities(enabled, decision.matchedPackage))
            BridgeOp.STATUS -> ok(request.requestId, status(enabled))
            BridgeOp.LIST -> ok(request.requestId, listStub(request.parameters))
            else -> fail(
                request.requestId,
                ErrorCategory.UNKNOWN_OPERATION,
                "stub does not implement '${request.operation}' and will not pretend LIVE control",
            )
        }
    }

    private fun capabilities(enabled: Boolean, callerPackage: String?): JsonObject = buildJsonObject {
        put("protocol_major", JsonPrimitive(BRIDGE_PROTOCOL_MAJOR))
        put("node_kind", JsonPrimitive("android-host-app"))
        put("app", JsonPrimitive("victorious-filemanager"))
        put("app_version", JsonPrimitive(version))
        put("node_id", JsonPrimitive(nodeId))
        put("live", JsonPrimitive(false))
        put("mode", JsonPrimitive("stub"))
        put("dca_required", JsonPrimitive(false))
        put("fleet_class", JsonPrimitive(FLEET_CLASS))
        put("enabled", JsonPrimitive(enabled))
        put("caller_package", JsonPrimitive(callerPackage ?: ""))
        put("cert_pinning", JsonPrimitive(policy.pinningEnabled))
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

    private fun status(enabled: Boolean): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(APP_ID))
        put("version", JsonPrimitive(version))
        put("application_id", JsonPrimitive(APPLICATION_ID))
        put("live", JsonPrimitive(false))
        put("mode", JsonPrimitive("stub"))
        put("dca_required", JsonPrimitive(false))
        put("fleet_class", JsonPrimitive(FLEET_CLASS))
        put("enabled", JsonPrimitive(enabled))
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
                    "Remote listing is a stub and does not expose device files. Use the VictorFiles UI.",
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
