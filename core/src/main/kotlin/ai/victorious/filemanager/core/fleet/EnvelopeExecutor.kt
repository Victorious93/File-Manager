package ai.victorious.filemanager.core.fleet

import ai.victorious.filemanager.core.priv.ShizukuState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Frozen fleet remote-control envelope v1 on VictorFiles (EXTERNAL_NODE).
 *
 * Gating order (DroidCommand AI REMOTE_CONTROL.md trust pipeline):
 * 1. caller identity = package + signing cert (from the Binder/Messenger uid) →
 *    `not_paired` / `unauthorized`. NEVER any envelope field or trigger.*.
 * 2. envelope parse → `invalid_parameters`; target node → `not_found`;
 *    caller op scope (e.g. keyboard = media.* only) → `unauthorized`.
 * 3. external-control switch (OFF by default) → `external_control_disabled`
 *    (only `capabilities.get` still answers, with `enabled:false`).
 * 4. freshness (`expired`) then replay (`replay_detected`).
 * 5. node policy: op exists (`not_found`), remoteAllowed (`unauthorized`),
 *    capability on (`capability_off`), confirmation (`confirmation_required`).
 *
 * `destructive` in the envelope is advisory and ignored for gating.
 */
class EnvelopeExecutor(
    private val nodeId: String,
    private val packageName: String,
    private val policy: CallerPolicy,
    private val externalControlEnabled: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val shizukuState: () -> ShizukuState? = { null },
    private val replay: ReplayGuard = ReplayGuard(),
    private val confirmation: RemoteConfirmation = RemoteConfirmation.NEVER,
    /** When set, [handleAsync] holds confirm-needing remote.command / macro.step / config.import until the user decides. */
    private val broker: ConfirmationBroker? = null,
    /** Media Pro player; null -> media.* are capability_off placeholders. */
    media: ai.victorious.filemanager.core.media.pro.MediaRemote? = null,
    extraOps: List<NodeOp> = emptyList(),
    private val configBackup: NodeConfigBackup? = null,
    /** UNIFIED_BACKUP default is false; only an explicit owner decision may raise it. */
    configRemoteAllowed: Boolean = false,
    legacyStatus: (() -> JsonObject)? = null,
) {
    private val ops: Map<String, NodeOp> = buildList {
        add(NodeOp(EnvelopeOp.CAPABILITIES_GET, OpKind.READ, remoteAllowed = true, requiresConfirm = false))
        add(NodeOp(EnvelopeOp.NODE_STATUS_GET, OpKind.READ, remoteAllowed = true, requiresConfirm = false) { args -> statusResult(args) })
        add(NodeOp(EnvelopeOp.REMOTE_COMMAND, OpKind.ACTION, remoteAllowed = true, requiresConfirm = false))
        add(NodeOp(EnvelopeOp.MACRO_STEP, OpKind.ACTION, remoteAllowed = true, requiresConfirm = false))
        add(NodeOp(EnvelopeOp.MACRO_RUN, OpKind.ACTION, remoteAllowed = true, requiresConfirm = false))
        if (legacyStatus != null) {
            add(NodeOp(BridgeOp.STATUS, OpKind.READ, remoteAllowed = true, requiresConfirm = false) { legacyStatus() })
        }
        add(NodeOp(ConfigOps.EXPORT, OpKind.ACTION, remoteAllowed = configRemoteAllowed, requiresConfirm = false, enabled = configBackup != null) { a -> configExport(a) })
        add(NodeOp(ConfigOps.IMPORT, OpKind.ACTION, remoteAllowed = configRemoteAllowed, requiresConfirm = true, enabled = configBackup != null) { a -> configImport(a) })
        addAll(media?.let { ai.victorious.filemanager.core.media.pro.MediaBridgeOps.ops(it) } ?: MediaOps.placeholders)
        addAll(extraOps)
    }.associateBy { it.name }

    fun descriptors(): List<NodeOp> = ops.values.toList()

    private companion object {
        val HOLDABLE = setOf(EnvelopeOp.REMOTE_COMMAND, EnvelopeOp.MACRO_STEP, ConfigOps.IMPORT)
    }

    class Outcome(val ok: Boolean, val result: JsonObject?, val category: String?, val message: String?)

    private class Fail(val category: String, msg: String) : Exception(msg)

    /** Raised by [gate] for a holdable op that still needs on-device approval. */
    private class NeedsConfirm(val opName: String) : Exception("needs confirmation")

    private class Ctx(val holdable: Boolean, val preConfirmed: Boolean)

    private var ctx = ThreadLocal.withInitial { Ctx(false, false) }

    /**
     * Async variant used by the Messenger service. Requests that need confirmation are
     * held in [broker]; [reply] is called exactly once (immediately, or on decision/timeout).
     */
    fun handleAsync(request: BridgeRequest, caller: CallerIdentity?, reply: (Outcome) -> Unit) {
        val b = broker
        if (b == null) { reply(handle(request, caller)); return }
        var held: Triple<EnvelopeV1, CallerDecision, String>? = null
        val first = try {
            Outcome(true, run(request, caller, holdable = true) { env, dec, opName -> held = Triple(env, dec, opName) }, null, null)
        } catch (f: Fail) { Outcome(false, null, f.category, f.message) } catch (e: EnvelopeException) {
            Outcome(false, null, e.category, e.message)
        } catch (n: NeedsConfirm) { null } catch (t: Throwable) { Outcome(false, null, RemoteError.INTERNAL, "internal error") }
        if (first != null) { reply(first); return }
        val (env, dec, opName) = held ?: run { reply(Outcome(false, null, RemoteError.INTERNAL, "internal error")); return }
        val p = b.submit(
            dec.sourceNodeRef, env.nonce, dec.matchedPackage.orEmpty(), env.op, opName, env.params,
            env.timestampMs + env.ttlMs,
        ) { decision ->
            if (decision != ConfirmationBroker.Decision.APPROVED) {
                reply(Outcome(false, null, RemoteError.CONFIRMATION_REQUIRED,
                    if (decision == ConfirmationBroker.Decision.DENIED) "denied on device" else "confirmation timed out (denied)"))
            } else {
                reply(
                    try { Outcome(true, dispatch(env, dec, enabled = true, preConfirmed = true), null, null) }
                    catch (f: Fail) { Outcome(false, null, f.category, f.message) }
                    catch (e: EnvelopeException) { Outcome(false, null, e.category, e.message) }
                    catch (t: Throwable) { Outcome(false, null, RemoteError.INTERNAL, "internal error") },
                )
            }
        }
        if (p == null) reply(Outcome(false, null, RemoteError.CONFIRMATION_REQUIRED, "could not hold request for confirmation"))
    }

    fun handle(request: BridgeRequest, caller: CallerIdentity?): Outcome = try {
        Outcome(true, run(request, caller), null, null)
    } catch (f: Fail) {
        Outcome(false, null, f.category, f.message)
    } catch (e: EnvelopeException) {
        Outcome(false, null, e.category, e.message)
    } catch (t: Throwable) {
        Outcome(false, null, RemoteError.INTERNAL, "internal error")
    }

    private fun run(
        request: BridgeRequest,
        caller: CallerIdentity?,
        holdable: Boolean = false,
        onHold: (EnvelopeV1, CallerDecision, String) -> Unit = { _, _, _ -> },
    ): JsonObject {
        val op = request.operation
        if (op !in EnvelopeOp.ROUTED) throw Fail(RemoteError.UNSUPPORTED_OP, "unsupported op '$op'")

        // 1. Identity: package + cert only.
        val decision = policy.evaluate(caller)
        if (!decision.allowed) {
            throw Fail(if (decision.notPaired) RemoteError.NOT_PAIRED else RemoteError.UNAUTHORIZED, decision.reason)
        }

        // 2. Envelope + target + caller scope.
        val env = EnvelopeCodec.parse(op, request.requestId, request.parameters)
        if (request.targetNodeId.isNotBlank() && request.targetNodeId != nodeId) {
            throw Fail(RemoteError.NOT_FOUND, "target_node_id does not match this node")
        }
        if (env.targetNodeRef.isNotBlank() && env.targetNodeRef != nodeId) {
            throw Fail(RemoteError.NOT_FOUND, "targetNodeRef does not match this node")
        }
        val effective = when (op) {
            EnvelopeOp.REMOTE_COMMAND, EnvelopeOp.MACRO_STEP -> listOf(env.actionRef)
            EnvelopeOp.MACRO_RUN -> env.steps.map { it.op }
            else -> listOf(op)
        }
        effective.firstOrNull { !decision.mayInvoke(it) }?.let {
            throw Fail(RemoteError.UNAUTHORIZED, "caller ${decision.matchedPackage} may not invoke '$it'")
        }

        // 3. External control (OFF by default).
        val enabled = externalControlEnabled()
        if (!enabled && op != EnvelopeOp.CAPABILITIES_GET) {
            throw Fail(RemoteError.EXTERNAL_CONTROL_DISABLED, "External control is off. Enable it in VictorFiles Settings.")
        }

        // 4. Freshness, then replay (only fresh envelopes consume a nonce slot).
        val now = clock()
        EnvelopeClock.check(env, now)?.let { throw Fail(RemoteError.EXPIRED, it) }
        when (replay.checkAndRecord(decision.sourceNodeRef, env.nonce, now)) {
            ReplayGuard.Result.REPLAY -> throw Fail(RemoteError.REPLAY_DETECTED, "nonce already seen")
            ReplayGuard.Result.FULL -> throw Fail(RemoteError.INTERNAL, "replay cache full; retry later")
            ReplayGuard.Result.FRESH -> Unit
        }

        // 5. Dispatch.
        return try {
            dispatch(env, decision, enabled, preConfirmed = false, holdable = holdable)
        } catch (n: NeedsConfirm) {
            onHold(env, decision, n.opName)
            throw n
        }
    }

    private fun dispatch(env: EnvelopeV1, decision: CallerDecision, enabled: Boolean, preConfirmed: Boolean, holdable: Boolean = false): JsonObject {
        val op = env.op
        val canHold = holdable && op in HOLDABLE
        val prev = ctx.get()
        ctx.set(Ctx(canHold, preConfirmed && op in HOLDABLE))
        try { return dispatchInner(env, decision, enabled) } finally { ctx.set(prev) }
    }

    private fun dispatchInner(env: EnvelopeV1, decision: CallerDecision, enabled: Boolean): JsonObject {
        val op = env.op
        return when (op) {
            EnvelopeOp.CAPABILITIES_GET -> capabilities(enabled, decision)
            EnvelopeOp.NODE_STATUS_GET -> statusResult(env.params)
            EnvelopeOp.REMOTE_COMMAND -> invoke(env.actionRef, env.params, env)
            EnvelopeOp.MACRO_STEP -> buildJsonObject {
                put("macroId", JsonPrimitive(env.macroId))
                put("stepIndex", JsonPrimitive(env.stepIndex))
                put("op", JsonPrimitive(env.actionRef))
                put("onError", JsonPrimitive(env.onError.wire))
                put("result", invoke(env.actionRef, env.params, env))
            }
            EnvelopeOp.MACRO_RUN -> macroRun(env)
            ConfigOps.EXPORT, ConfigOps.IMPORT -> {
                // Top-level envelope dryRun is equivalent to args.dryRun (UNIFIED_BACKUP §1).
                val params = if (env.dryRun) JsonObject(env.params + ("dryRun" to JsonPrimitive(true))) else env.params
                invoke(op, params, env.copy(params = params))
            }
            else -> throw Fail(RemoteError.UNSUPPORTED_OP, "unsupported op '$op'")
        }
    }

    /** Policy checks for one target op; throws on any gate. Returns the op when runnable. */
    private fun gate(name: String, env: EnvelopeV1): NodeOp {
        if (name in EnvelopeOp.META) throw Fail(RemoteError.INVALID_PARAMETERS, "'$name' cannot be nested")
        val op = ops[name] ?: throw Fail(RemoteError.NOT_FOUND, "op '$name' is not advertised by this node")
        // UNIFIED_BACKUP §6 / STATUS_TRAY: remoteAllowed=false for a remote caller -> capability_off.
        if (!op.remoteAllowed) throw Fail(RemoteError.CAPABILITY_OFF, "op '$name' is not remote-allowed on this node")
        if (!op.enabled) throw Fail(RemoteError.CAPABILITY_OFF, "op '$name' is turned off on this node")
        // Classified by the node itself; envelope `destructive` is ignored.
        val dryImport = name == ConfigOps.IMPORT && (env.dryRun || isTrue(env.params["dryRun"]))
        val c = ctx.get()
        if ((op.kind == OpKind.DESTRUCTIVE || op.requiresConfirm) && !dryImport && !c.preConfirmed &&
            !confirmation.confirmedOnDevice(op, env)
        ) {
            if (c.holdable) throw NeedsConfirm(name)
            throw Fail(RemoteError.CONFIRMATION_REQUIRED, "op '$name' needs on-device confirmation")
        }
        return op
    }

    private fun invoke(name: String, params: JsonObject, env: EnvelopeV1): JsonObject {
        if (name == EnvelopeOp.CAPABILITIES_GET) throw Fail(RemoteError.INVALID_PARAMETERS, "call capabilities.get directly")
        val op = gate(name, env)
        val h = op.handler ?: throw Fail(RemoteError.CAPABILITY_OFF, "op '$name' has no implementation")
        return h(params)
    }

    private fun macroRun(env: EnvelopeV1): JsonObject {
        val results = mutableListOf<JsonObject>()
        var aborted = false
        var failures = 0
        env.steps.forEachIndexed { i, step ->
            if (aborted) {
                results += stepJson(i, step, "skipped", null, null, null)
                return@forEachIndexed
            }
            try {
                if (env.dryRun) {
                    if (step.op == EnvelopeOp.CAPABILITIES_GET) throw Fail(RemoteError.INVALID_PARAMETERS, "call capabilities.get directly")
                    val op = gate(step.op, env)
                    if (op.handler == null) throw Fail(RemoteError.CAPABILITY_OFF, "op '${step.op}' has no implementation")
                    results += stepJson(i, step, "validated", null, null, null)
                } else {
                    results += stepJson(i, step, "ok", invoke(step.op, step.params, env), null, null)
                }
            } catch (f: Fail) {
                failures++
                results += stepJson(i, step, "error", null, f.category, f.message)
                if (step.onError == OnError.ABORT) aborted = true
            } catch (e: EnvelopeException) {
                failures++
                results += stepJson(i, step, "error", null, e.category, e.message)
                if (step.onError == OnError.ABORT) aborted = true
            }
        }
        return buildJsonObject {
            put("macroId", JsonPrimitive(env.macroId))
            put("dryRun", JsonPrimitive(env.dryRun))
            put("executed", JsonPrimitive(!env.dryRun))
            put(
                "status",
                JsonPrimitive(
                    when {
                        aborted -> "aborted"
                        failures > 0 -> "completed_with_errors"
                        else -> "completed"
                    },
                ),
            )
            put("rollback", JsonPrimitive(false))
            put("steps", JsonArray(results))
        }
    }

    private fun stepJson(i: Int, s: MacroStepSpec, status: String, result: JsonObject?, cat: String?, msg: String?) =
        buildJsonObject {
            put("index", JsonPrimitive(i))
            put("op", JsonPrimitive(s.op))
            put("onError", JsonPrimitive(s.onError.wire))
            put("status", JsonPrimitive(status))
            result?.let { put("result", it) }
            if (cat != null) put("error", buildJsonObject { put("category", JsonPrimitive(cat)); put("message", JsonPrimitive(msg)) })
        }

    private fun isTrue(e: kotlinx.serialization.json.JsonElement?): Boolean =
        (e as? JsonPrimitive)?.content?.equals("true", ignoreCase = true) == true

    private fun configExport(args: JsonObject): JsonObject {
        val cb = configBackup ?: throw Fail(RemoteError.UNSUPPORTED_OP, "no config surface")
        val pkg = (args["packageName"] as? JsonPrimitive)?.content.orEmpty()
        if (pkg.isNotEmpty() && pkg != packageName) throw Fail(RemoteError.NOT_FOUND, "no entry for $pkg")
        return UnifiedFleetBackupCodec.encodeEntry(cb.export(clearPayload = isTrue(args["clearPayload"])))
    }

    private fun configImport(args: JsonObject): JsonObject {
        val cb = configBackup ?: throw Fail(RemoteError.UNSUPPORTED_OP, "no config surface")
        val bundle = (args["bundle"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw Fail(RemoteError.INVALID_PARAMETERS, "bundle (JSON string) is required")
        val policy = BackupConflictPolicy.parse((args["conflictPolicy"] as? JsonPrimitive)?.content)
            ?: throw Fail(RemoteError.INVALID_PARAMETERS, "conflictPolicy must be skip|overwrite|merge-by-id")
        return cb.import(bundle, policy, dryRun = isTrue(args["dryRun"])).toJson()
    }

    private fun statusResult(args: JsonObject): JsonObject {
        val asked = (args["nodeId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (asked != null && asked != nodeId) throw Fail(RemoteError.NOT_FOUND, "nodeId does not match this node")
        val snap = FileManagerStatus.snapshot(nodeId, packageName, clock(), runCatching { shizukuState() }.getOrNull(), externalControlEnabled())
        return NodeStatusSnapshotCodec.encode(snap)
    }

    private fun capabilities(enabled: Boolean, decision: CallerDecision): JsonObject = buildJsonObject {
        put("protocolMajor", JsonPrimitive(BRIDGE_PROTOCOL_MAJOR))
        put("protocolMinor", JsonPrimitive(BRIDGE_PROTOCOL_MINOR))
        put("nodeId", JsonPrimitive(nodeId))
        put("packageName", JsonPrimitive(packageName))
        put("connectionClass", JsonPrimitive(NodeStatusSnapshotCodec.CONNECTION_CLASS_EXTERNAL_NODE))
        put("enabled", JsonPrimitive(enabled))
        put("callerPackage", JsonPrimitive(decision.matchedPackage))
        put("ops", JsonArray(ops.values.filter { decision.mayInvoke(it.name) }.map { it.descriptor() }))
    }
}
