package ai.victorious.filemanager.core.fleet

import ai.victorious.filemanager.core.priv.ShizukuState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EnvelopeV1Test {
    private val nodeId = "fm-0123456789abcdef0123456789abcdef"
    private val pkg = "ai.victorious.filemanager.debug"
    private var now = 1_760_920_000_000L
    private var enabled = true
    private var shizuku: ShizukuState? = ShizukuState.GRANTED
    private var confirmed = false
    private var counter = 0
    private val ran = mutableListOf<String>()

    private val readOk = NodeOp("test.read", OpKind.READ, true, false) { ran += "test.read"; JsonObject(mapOf("v" to JsonPrimitive(1))) }
    private val failing = NodeOp("test.fail", OpKind.ACTION, true, false) { ran += "test.fail"; throw EnvelopeException(RemoteError.INVALID_PARAMETERS, "boom") }
    private val wipe = NodeOp("test.wipe", OpKind.DESTRUCTIVE, true, true) { ran += "test.wipe"; JsonObject(emptyMap()) }
    private val localOnly = NodeOp("test.local", OpKind.READ, remoteAllowed = false, requiresConfirm = false) { ran += "test.local"; JsonObject(emptyMap()) }

    private val executor = BridgeExecutor(
        nodeId,
        externalControlEnabled = { enabled },
        packageName = pkg,
        clock = { now },
        shizukuState = { shizuku },
        confirmation = { _, _ -> confirmed },
        extraOps = listOf(readOk, failing, wipe, localOnly),
    )

    private val dcaCert = "aa".repeat(32)
    private val dca = CallerIdentity(10123, listOf("ai.droidcommand.app"), mapOf("ai.droidcommand.app" to listOf(dcaCert)))
    private val kbCert = CallerPolicy.normalizeDigest(CallerPolicy.KEYBOARD_DEBUG_CERT_SHA256)
    private val keyboard = CallerIdentity(10200, listOf(CallerPolicy.KEYBOARD_DEBUG), mapOf(CallerPolicy.KEYBOARD_DEBUG to listOf(kbCert)))

    private fun nonce(): String = "%032x".format(++counter)

    private fun req(
        op: String,
        extra: Map<String, JsonElement> = emptyMap(),
        nonce: String? = nonce(),
        ts: Long? = now,
        ttl: Long? = null,
        target: String = "",
    ): String {
        val p = buildMap<String, JsonElement> {
            put("targetNodeRef", JsonPrimitive(nodeId))
            if (nonce != null) put("nonce", JsonPrimitive(nonce))
            if (ts != null) put("timestampMs", JsonPrimitive(ts))
            if (ttl != null) put("ttlMs", JsonPrimitive(ttl))
            put("destructive", JsonPrimitive(false))
            putAll(extra)
        }
        return bridgeJson.encodeToString(
            BridgeRequest.serializer(),
            BridgeRequest(requestId = "req-$counter", targetNodeId = target, operation = op, parameters = JsonObject(p)),
        )
    }

    private fun call(json: String, caller: CallerIdentity? = dca): BridgeResponse =
        bridgeJson.decodeFromString(BridgeResponse.serializer(), executor.handle(json, caller))

    private fun assertErr(expected: String, r: BridgeResponse) {
        assertFalse("expected $expected but ok: ${r.result}", r.ok)
        assertEquals(expected, r.error!!.category)
    }

    private fun cmd(actionRef: String, extra: Map<String, JsonElement> = emptyMap()) =
        req(EnvelopeOp.REMOTE_COMMAND, mapOf("actionRef" to JsonPrimitive(actionRef)) + extra)

    private fun steps(vararg s: Pair<String, String>) = JsonArray(
        s.map { (op, onErr) -> buildJsonObject { put("op", JsonPrimitive(op)); put("onError", JsonPrimitive(onErr)); put("params", JsonObject(emptyMap())) } },
    )

    // ---- constants / capabilities ----

    @Test fun protocolVersionIsFrozen() {
        assertEquals(1, BRIDGE_PROTOCOL_MAJOR)
        assertEquals(1, BRIDGE_PROTOCOL_MINOR)
        assertEquals("ai.victorious.filemanager.action.BRIDGE", BRIDGE_ACTION)
    }

    @Test fun capabilitiesAdvertiseMinorAndDescriptors() {
        val r = call(req(EnvelopeOp.CAPABILITIES_GET))
        assertTrue(r.ok)
        val res = r.result!!
        assertEquals(1, res["protocolMajor"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, res["protocolMinor"]!!.jsonPrimitive.content.toInt())
        val ops = res["ops"]!!.jsonArray.map { it.jsonObject }.associateBy { it["name"]!!.jsonPrimitive.content }
        for (o in ops.values) assertEquals(setOf("name", "kind", "remoteAllowed", "requiresConfirm"), o.keys)
        val st = ops.getValue("node.status.get")
        assertEquals("read", st["kind"]!!.jsonPrimitive.content)
        assertEquals("true", st["remoteAllowed"]!!.jsonPrimitive.content)
        assertEquals("false", st["requiresConfirm"]!!.jsonPrimitive.content)
        assertEquals("destructive", ops.getValue("test.wipe")["kind"]!!.jsonPrimitive.content)
        assertEquals("true", ops.getValue("test.wipe")["requiresConfirm"]!!.jsonPrimitive.content)
        assertTrue(ops.keys.containsAll(listOf("capabilities.get", "remote.command", "macro.step", "macro.run", MediaOps.PLAY_PAUSE)))
    }

    @Test fun capabilitiesAnswerWhenExternalControlOff() {
        enabled = false
        val r = call(req(EnvelopeOp.CAPABILITIES_GET))
        assertTrue(r.ok)
        assertEquals("false", r.result!!["enabled"]!!.jsonPrimitive.content)
    }

    @Test fun requestIdReusesBridgeRequestId() {
        val r = call(req(EnvelopeOp.CAPABILITIES_GET))
        assertEquals("req-$counter", r.requestId)
    }

    @Test fun unknownOpIsUnsupportedOrLegacy() {
        assertErr(ErrorCategory.UNKNOWN_OPERATION, call(req("bogus.op")))
    }

    @Test fun destructiveCannotBeAssertedIntoNonDestructiveOp() {
        @Suppress("UNUSED_VARIABLE") val unused = 0
        // Advisory flag true does not block a read, and false does not unlock a destructive op.
        assertTrue(call(cmd("test.read", mapOf("destructive" to JsonPrimitive(true)))).ok)
        assertErr(RemoteError.CONFIRMATION_REQUIRED, call(cmd("test.wipe")))
    }

    // ---- expiry / skew / ttl ----

    @Test fun expiredAfterTtl() {
        val r = call(req(EnvelopeOp.CAPABILITIES_GET, ts = now - 30_001, ttl = 30_000))
        assertErr(RemoteError.EXPIRED, r)
    }

    @Test fun withinTtlAccepted() {
        assertTrue(call(req(EnvelopeOp.CAPABILITIES_GET, ts = now - 29_999, ttl = 30_000)).ok)
    }

    @Test fun defaultTtlIs120s() {
        assertTrue(call(req(EnvelopeOp.CAPABILITIES_GET, ts = now - 120_000)).ok)
        assertErr(RemoteError.EXPIRED, call(req(EnvelopeOp.CAPABILITIES_GET, ts = now - 120_001)))
    }

    @Test fun pastSkewBeyond120sExpiredEvenWithMaxTtl() {
        assertErr(RemoteError.EXPIRED, call(req(EnvelopeOp.CAPABILITIES_GET, ts = now - 120_001, ttl = 300_000)))
    }

    @Test fun futureSkewBeyond120sExpired() {
        assertErr(RemoteError.EXPIRED, call(req(EnvelopeOp.CAPABILITIES_GET, ts = now + 120_001, ttl = 300_000)))
        assertTrue(call(req(EnvelopeOp.CAPABILITIES_GET, ts = now + 120_000)).ok)
    }

    @Test fun ttlAboveMaxRejected() {
        assertErr(RemoteError.INVALID_PARAMETERS, call(req(EnvelopeOp.CAPABILITIES_GET, ttl = 300_001)))
        assertErr(RemoteError.INVALID_PARAMETERS, call(req(EnvelopeOp.CAPABILITIES_GET, ttl = 0)))
        assertTrue(call(req(EnvelopeOp.CAPABILITIES_GET, ttl = 300_000)).ok)
    }

    @Test fun missingTimestampOrNonceInvalid() {
        assertErr(RemoteError.INVALID_PARAMETERS, call(req(EnvelopeOp.CAPABILITIES_GET, ts = null)))
        assertErr(RemoteError.INVALID_PARAMETERS, call(req(EnvelopeOp.CAPABILITIES_GET, nonce = null)))
    }

    @Test fun nonceMustBe128Bit() {
        assertErr(RemoteError.INVALID_PARAMETERS, call(req(EnvelopeOp.CAPABILITIES_GET, nonce = "abc")))
        assertErr(RemoteError.INVALID_PARAMETERS, call(req(EnvelopeOp.CAPABILITIES_GET, nonce = "a".repeat(31))))
        assertTrue(EnvelopeCodec.isValidNonce("0123456789abcdef0123456789ABCDEF"))
        assertTrue(EnvelopeCodec.isValidNonce(java.util.Base64.getEncoder().encodeToString(ByteArray(16) { it.toByte() })))
        assertFalse(EnvelopeCodec.isValidNonce(java.util.Base64.getEncoder().encodeToString(ByteArray(8))))
    }

    // ---- replay ----

    @Test fun duplicateNonceRejected() {
        val n = nonce()
        assertTrue(call(req(EnvelopeOp.CAPABILITIES_GET, nonce = n)).ok)
        assertErr(RemoteError.REPLAY_DETECTED, call(req(EnvelopeOp.CAPABILITIES_GET, nonce = n)))
        // case-insensitive hex
        assertErr(RemoteError.REPLAY_DETECTED, call(req(EnvelopeOp.CAPABILITIES_GET, nonce = n.uppercase())))
    }

    @Test fun replayKeyedBySource() {
        val other = CallerIdentity(10124, listOf("ai.droidcommand.app.debug"), mapOf("ai.droidcommand.app.debug" to listOf("bb".repeat(32))))
        val n = nonce()
        assertTrue(call(req(EnvelopeOp.CAPABILITIES_GET, nonce = n), dca).ok)
        assertTrue(call(req(EnvelopeOp.CAPABILITIES_GET, nonce = n), other).ok)
    }

    @Test fun replayCacheRetainedTenMinutes() {
        val g = ReplayGuard()
        assertEquals(ReplayGuard.Result.FRESH, g.checkAndRecord("s", "n", 0))
        assertEquals(ReplayGuard.Result.REPLAY, g.checkAndRecord("s", "n", 599_999))
        assertEquals(ReplayGuard.Result.FRESH, g.checkAndRecord("s", "n", 600_000))
    }

    @Test fun replayDetectedAcrossTimeInExecutor() {
        val n = nonce()
        val t0 = now
        assertTrue(call(req(EnvelopeOp.CAPABILITIES_GET, nonce = n, ts = t0)).ok)
        now = t0 + 60_000
        assertErr(RemoteError.REPLAY_DETECTED, call(req(EnvelopeOp.CAPABILITIES_GET, nonce = n, ts = now)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun replayRetentionBelowTenMinutesRefused() {
        ReplayGuard(retentionMs = 599_999)
    }

    @Test fun replayCacheFullFailsClosed() {
        val g = ReplayGuard(maxEntries = 2)
        g.checkAndRecord("s", "1", 0); g.checkAndRecord("s", "2", 0)
        assertEquals(ReplayGuard.Result.FULL, g.checkAndRecord("s", "3", 0))
        assertEquals(ReplayGuard.Result.REPLAY, g.checkAndRecord("s", "1", 0))
    }

    @Test fun expiredEnvelopeDoesNotConsumeNonce() {
        val n = nonce()
        assertErr(RemoteError.EXPIRED, call(req(EnvelopeOp.CAPABILITIES_GET, nonce = n, ts = now - 200_000)))
        assertTrue(call(req(EnvelopeOp.CAPABILITIES_GET, nonce = n)).ok)
    }

    // ---- auth ----

    @Test fun unresolvedCallerNotPaired() {
        assertErr(RemoteError.NOT_PAIRED, call(req(EnvelopeOp.CAPABILITIES_GET), null))
    }

    @Test fun unknownPackageNotPaired() {
        val evil = CallerIdentity(10999, listOf("com.evil"), mapOf("com.evil" to listOf(dcaCert)))
        assertErr(RemoteError.NOT_PAIRED, call(req(EnvelopeOp.CAPABILITIES_GET), evil))
    }

    @Test fun envelopeAndTriggerFieldsNeverAuthorize() {
        val evil = CallerIdentity(10999, listOf("com.evil"))
        val extra = mapOf(
            "sourceNodeRef" to JsonPrimitive("ai.droidcommand.app"),
            "callerPackage" to JsonPrimitive("ai.droidcommand.app"),
            "trigger" to buildJsonObject {
                put("sourceNumber", JsonPrimitive("+15555550100"))
                put("matchedKeyword", JsonPrimitive(true))
                put("rawExtras", JsonObject(emptyMap()))
            },
        )
        assertErr(RemoteError.NOT_PAIRED, call(req(EnvelopeOp.CAPABILITIES_GET, extra), evil))
        assertErr(RemoteError.NOT_PAIRED, call(cmd("test.read", extra), evil))
        assertTrue(ran.isEmpty())
    }

    @Test fun pinnedCertMismatchUnauthorized() {
        val pinned = BridgeExecutor(
            nodeId, CallerPolicy(certPins = mapOf("ai.droidcommand.app" to setOf("cc".repeat(32)))),
            externalControlEnabled = { true }, clock = { now },
        )
        val r = bridgeJson.decodeFromString(BridgeResponse.serializer(), pinned.handle(req(EnvelopeOp.CAPABILITIES_GET), dca))
        assertErr(RemoteError.UNAUTHORIZED, r)
    }

    @Test fun keyboardDebugPinnedAndMediaOnly() {
        // media.* allowed through identity + scope, but placeholders are capability_off.
        assertErr(RemoteError.CAPABILITY_OFF, call(cmd(MediaOps.PLAY_PAUSE), keyboard))
        assertErr(RemoteError.CAPABILITY_OFF, call(cmd(MediaOps.NEXT), keyboard))
        // Anything outside media.* is unauthorized.
        assertErr(RemoteError.UNAUTHORIZED, call(cmd("test.read"), keyboard))
        assertErr(RemoteError.UNAUTHORIZED, call(req(EnvelopeOp.NODE_STATUS_GET), keyboard))
        assertErr(RemoteError.UNAUTHORIZED, call(req(EnvelopeOp.CAPABILITIES_GET), keyboard))
        assertErr(RemoteError.UNAUTHORIZED, call(req(EnvelopeOp.MACRO_RUN, mapOf("steps" to steps(MediaOps.NEXT to "abort", "test.read" to "abort"))), keyboard))
        assertTrue(ran.isEmpty())
    }

    @Test fun keyboardDebugWrongCertUnauthorized() {
        val fake = CallerIdentity(10201, listOf(CallerPolicy.KEYBOARD_DEBUG), mapOf(CallerPolicy.KEYBOARD_DEBUG to listOf("dd".repeat(32))))
        assertErr(RemoteError.UNAUTHORIZED, call(cmd(MediaOps.PLAY_PAUSE), fake))
        val none = CallerIdentity(10201, listOf(CallerPolicy.KEYBOARD_DEBUG))
        assertErr(RemoteError.UNAUTHORIZED, call(cmd(MediaOps.PLAY_PAUSE), none))
    }

    @Test fun keyboardPinCannotBeOverriddenAtBuildTime() {
        val p = CallerPolicy(certPins = mapOf(CallerPolicy.KEYBOARD_DEBUG to setOf("dd".repeat(32))))
        val fake = CallerIdentity(1, listOf(CallerPolicy.KEYBOARD_DEBUG), mapOf(CallerPolicy.KEYBOARD_DEBUG to listOf("dd".repeat(32))))
        assertFalse(p.evaluate(fake).allowed)
        assertTrue(p.evaluate(keyboard).allowed)
    }

    @Test fun keyboardReleaseDenied() {
        val rel = CallerIdentity(10202, listOf(CallerPolicy.KEYBOARD_RELEASE), mapOf(CallerPolicy.KEYBOARD_RELEASE to listOf(kbCert)))
        assertErr(RemoteError.NOT_PAIRED, call(cmd(MediaOps.PLAY_PAUSE), rel))
        assertFalse(CallerPolicy.KEYBOARD_RELEASE in CallerPolicy.DEFAULT_ALLOWED_PACKAGES)
    }

    @Test fun keyboardCannotUseLegacyOps() {
        val legacy = bridgeJson.encodeToString(BridgeRequest.serializer(), BridgeRequest(requestId = "l", operation = BridgeOp.STATUS))
        assertErr(ErrorCategory.UNAUTHORIZED_CALLER, call(legacy, keyboard))
    }

    @Test fun authCheckedBeforeExternalControl() {
        enabled = false
        assertErr(RemoteError.NOT_PAIRED, call(cmd("test.read"), CallerIdentity(1, listOf("com.evil"))))
        assertErr(RemoteError.EXTERNAL_CONTROL_DISABLED, call(cmd("test.read")))
    }

    // ---- external control / policy ----

    @Test fun externalControlOffBlocksEverythingButCapabilities() {
        enabled = false
        for (j in listOf(cmd("test.read"), req(EnvelopeOp.NODE_STATUS_GET), req(EnvelopeOp.MACRO_STEP, mapOf("op" to JsonPrimitive("test.read"))),
            req(EnvelopeOp.MACRO_RUN, mapOf("steps" to steps("test.read" to "abort"), "dryRun" to JsonPrimitive(true))))) {
            assertErr(RemoteError.EXTERNAL_CONTROL_DISABLED, call(j))
        }
        assertTrue(ran.isEmpty())
    }

    @Test fun externalControlDefaultsOff() {
        val def = BridgeExecutor(nodeId, clock = { now })
        val r = bridgeJson.decodeFromString(BridgeResponse.serializer(), def.handle(cmd("node.status.get"), dca))
        assertErr(RemoteError.EXTERNAL_CONTROL_DISABLED, r)
    }

    @Test fun remoteCommandRunsAdvertisedOp() {
        val r = call(cmd("test.read"))
        assertTrue(r.ok)
        assertEquals(listOf("test.read"), ran)
    }

    @Test fun unknownActionRefNotFound() {
        assertErr(RemoteError.NOT_FOUND, call(cmd("nope.op")))
    }

    @Test fun wrongTargetNotFound() {
        assertErr(RemoteError.NOT_FOUND, call(req(EnvelopeOp.CAPABILITIES_GET, mapOf("targetNodeRef" to JsonPrimitive("fm-other")))))
        assertErr(RemoteError.NOT_FOUND, call(req(EnvelopeOp.CAPABILITIES_GET, target = "fm-other")))
    }

    @Test fun remoteCommandWithoutActionRefInvalid() {
        assertErr(RemoteError.INVALID_PARAMETERS, call(req(EnvelopeOp.REMOTE_COMMAND)))
    }

    @Test fun nestedMetaOpsRejected() {
        assertErr(RemoteError.INVALID_PARAMETERS, call(cmd(EnvelopeOp.MACRO_RUN)))
    }

    @Test fun localOnlyOpUnauthorizedRemotely() {
        assertErr(RemoteError.UNAUTHORIZED, call(cmd("test.local")))
        assertTrue(ran.isEmpty())
    }

    @Test fun destructiveNeedsOnDeviceConfirmation() {
        assertErr(RemoteError.CONFIRMATION_REQUIRED, call(cmd("test.wipe", mapOf("trigger" to buildJsonObject { put("matchedKeyword", JsonPrimitive(true)) }))))
        assertTrue(ran.isEmpty())
        confirmed = true
        assertTrue(call(cmd("test.wipe")).ok)
        assertEquals(listOf("test.wipe"), ran)
    }

    @Test fun defaultConfirmationNeverApproves() {
        assertFalse(RemoteConfirmation.NEVER.confirmedOnDevice(wipe, EnvelopeCodec.parse("remote.command", "r", JsonObject(mapOf(
            "actionRef" to JsonPrimitive("x"), "nonce" to JsonPrimitive("0".repeat(32)), "timestampMs" to JsonPrimitive(1)))),))
    }

    @Test(expected = IllegalArgumentException::class)
    fun destructiveOpMustRequireConfirm() {
        NodeOp("x", OpKind.DESTRUCTIVE, true, requiresConfirm = false)
    }

    @Test fun mediaPlaceholdersCapabilityOff() {
        for (m in listOf(MediaOps.PLAY_PAUSE, MediaOps.NEXT, MediaOps.PREVIOUS, MediaOps.STATUS_GET)) {
            assertErr(RemoteError.CAPABILITY_OFF, call(cmd(m)))
        }
    }

    // ---- macro.step ----

    @Test fun macroStepEchoesFieldsAndRuns() {
        val r = call(req(EnvelopeOp.MACRO_STEP, mapOf("macroId" to JsonPrimitive("m1"), "stepIndex" to JsonPrimitive(3), "op" to JsonPrimitive("test.read"), "onError" to JsonPrimitive("continue"))))
        assertTrue(r.ok)
        assertEquals("m1", r.result!!["macroId"]!!.jsonPrimitive.content)
        assertEquals("3", r.result!!["stepIndex"]!!.jsonPrimitive.content)
        assertEquals("continue", r.result!!["onError"]!!.jsonPrimitive.content)
        assertEquals(listOf("test.read"), ran)
    }

    @Test fun macroStepBadOnErrorInvalid() {
        assertErr(RemoteError.INVALID_PARAMETERS, call(req(EnvelopeOp.MACRO_STEP, mapOf("op" to JsonPrimitive("test.read"), "onError" to JsonPrimitive("retry")))))
        assertErr(RemoteError.INVALID_PARAMETERS, call(req(EnvelopeOp.MACRO_STEP, mapOf("op" to JsonPrimitive("test.read"), "stepIndex" to JsonPrimitive(-1)))))
        assertErr(RemoteError.INVALID_PARAMETERS, call(req(EnvelopeOp.MACRO_STEP)))
    }

    // ---- macro.run ----

    private fun runMacro(steps: JsonArray, dryRun: Boolean = false): JsonObject {
        val r = call(req(EnvelopeOp.MACRO_RUN, mapOf("macroId" to JsonPrimitive("m"), "steps" to steps, "dryRun" to JsonPrimitive(dryRun))))
        assertTrue("macro failed: ${r.error}", r.ok)
        return r.result!!
    }

    private fun statuses(res: JsonObject) = res["steps"]!!.jsonArray.map { it.jsonObject["status"]!!.jsonPrimitive.content }

    @Test fun macroRunSequentialSuccess() {
        val res = runMacro(steps("test.read" to "abort", "node.status.get" to "abort"))
        assertEquals(listOf("ok", "ok"), statuses(res))
        assertEquals("completed", res["status"]!!.jsonPrimitive.content)
        assertEquals("false", res["rollback"]!!.jsonPrimitive.content)
    }

    @Test fun onErrorAbortStopsAndReportsPartial() {
        val res = runMacro(steps("test.read" to "abort", "test.fail" to "abort", "test.read" to "abort"))
        assertEquals(listOf("ok", "error", "skipped"), statuses(res))
        assertEquals("aborted", res["status"]!!.jsonPrimitive.content)
        assertEquals(listOf("test.read", "test.fail"), ran)
    }

    @Test fun onErrorContinueKeepsGoing() {
        val res = runMacro(steps("test.fail" to "continue", "nope.op" to "continue", "test.read" to "abort"))
        assertEquals(listOf("error", "error", "ok"), statuses(res))
        assertEquals("completed_with_errors", res["status"]!!.jsonPrimitive.content)
        assertEquals(RemoteError.NOT_FOUND, res["steps"]!!.jsonArray[1].jsonObject["error"]!!.jsonObject["category"]!!.jsonPrimitive.content)
    }

    @Test fun onErrorDefaultsToAbort() {
        val s = JsonArray(listOf(buildJsonObject { put("op", JsonPrimitive("test.fail")) }, buildJsonObject { put("op", JsonPrimitive("test.read")) }))
        assertEquals(listOf("error", "skipped"), statuses(runMacro(s)))
    }

    @Test fun destructiveStepInMacroNeedsConfirmation() {
        val res = runMacro(steps("test.wipe" to "continue", "test.read" to "abort"))
        assertEquals(listOf("error", "ok"), statuses(res))
        assertEquals(RemoteError.CONFIRMATION_REQUIRED, res["steps"]!!.jsonArray[0].jsonObject["error"]!!.jsonObject["category"]!!.jsonPrimitive.content)
        assertFalse("test.wipe" in ran)
    }

    @Test fun dryRunValidatesWithoutExecuting() {
        val res = runMacro(steps("test.read" to "continue", "test.fail" to "continue", MediaOps.NEXT to "continue", "test.wipe" to "continue", "nope" to "continue"), dryRun = true)
        assertEquals(listOf("validated", "validated", "error", "error", "error"), statuses(res))
        assertEquals("true", res["dryRun"]!!.jsonPrimitive.content)
        assertEquals("false", res["executed"]!!.jsonPrimitive.content)
        assertTrue("dryRun must not execute anything", ran.isEmpty())
        val cats = res["steps"]!!.jsonArray.drop(2).map { it.jsonObject["error"]!!.jsonObject["category"]!!.jsonPrimitive.content }
        assertEquals(listOf(RemoteError.CAPABILITY_OFF, RemoteError.CONFIRMATION_REQUIRED, RemoteError.NOT_FOUND), cats)
    }

    @Test fun dryRunAbortSkipsRest() {
        val res = runMacro(steps("nope" to "abort", "test.read" to "abort"), dryRun = true)
        assertEquals(listOf("error", "skipped"), statuses(res))
        assertTrue(ran.isEmpty())
    }

    @Test fun dryRunStillConsumesNonceAndChecksExpiry() {
        val n = nonce()
        val p = mapOf("steps" to steps("test.read" to "abort"), "dryRun" to JsonPrimitive(true))
        assertTrue(call(req(EnvelopeOp.MACRO_RUN, p, nonce = n)).ok)
        assertErr(RemoteError.REPLAY_DETECTED, call(req(EnvelopeOp.MACRO_RUN, p, nonce = n)))
        assertErr(RemoteError.EXPIRED, call(req(EnvelopeOp.MACRO_RUN, p, ts = now - 500_000)))
    }

    @Test fun macroRunValidation() {
        assertErr(RemoteError.INVALID_PARAMETERS, call(req(EnvelopeOp.MACRO_RUN)))
        assertErr(RemoteError.INVALID_PARAMETERS, call(req(EnvelopeOp.MACRO_RUN, mapOf("steps" to JsonArray(emptyList())))))
        assertErr(RemoteError.INVALID_PARAMETERS, call(req(EnvelopeOp.MACRO_RUN, mapOf("steps" to steps("test.read" to "sometimes")))))
        val many = JsonArray(List(33) { buildJsonObject { put("op", JsonPrimitive("test.read")) } })
        assertErr(RemoteError.INVALID_PARAMETERS, call(req(EnvelopeOp.MACRO_RUN, mapOf("steps" to many))))
        assertTrue(ran.isEmpty())
    }

    // ---- node.status.get ----

    private fun status(args: JsonObject = JsonObject(emptyMap())): BridgeResponse = call(req(EnvelopeOp.NODE_STATUS_GET, mapOf("args" to args)))

    @Test fun statusSnapshotShape() {
        val r = status()
        assertTrue(r.ok)
        val s = r.result!!
        assertEquals(setOf("nodeId", "packageName", "connectionClass", "updatedAtMs", "domains"), s.keys)
        assertEquals(nodeId, s["nodeId"]!!.jsonPrimitive.content)
        assertEquals(pkg, s["packageName"]!!.jsonPrimitive.content)
        assertEquals("EXTERNAL_NODE", s["connectionClass"]!!.jsonPrimitive.content)
        assertEquals(now.toString(), s["updatedAtMs"]!!.jsonPrimitive.content)
        val d = s["domains"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("node_health", "other"), d.map { it["name"]!!.jsonPrimitive.content })
        assertEquals("ok", d[0]["state"]!!.jsonPrimitive.content)
        assertEquals("ok", d[1]["state"]!!.jsonPrimitive.content)
        assertTrue(d[1]["detail"]!!.jsonPrimitive.content.contains("shizuku"))
        for (x in d) assertTrue(x.keys.all { it in setOf("name", "state", "detail") })
    }

    @Test fun statusShizukuStates() {
        val expect = mapOf(ShizukuState.GRANTED to "ok", ShizukuState.DENIED to "degraded", ShizukuState.NOT_RUNNING to "down", ShizukuState.NOT_INSTALLED to "off", null to "unknown")
        for ((st, wire) in expect) {
            shizuku = st
            val d = status().result!!["domains"]!!.jsonArray[1].jsonObject
            assertEquals("other", d["name"]!!.jsonPrimitive.content)
            assertEquals(wire, d["state"]!!.jsonPrimitive.content)
        }
    }

    @Test fun statusViaRemoteCommandToo() {
        assertTrue(call(cmd(EnvelopeOp.NODE_STATUS_GET)).ok)
    }

    @Test fun statusNodeIdArgMismatchNotFound() {
        assertErr(RemoteError.NOT_FOUND, status(JsonObject(mapOf("nodeId" to JsonPrimitive("fm-other")))))
        assertTrue(status(JsonObject(mapOf("nodeId" to JsonPrimitive(nodeId)))).ok)
    }

    @Test fun statusSnapshotHasNoPathsOrContents() {
        for (st in listOf<ShizukuState?>(null) + ShizukuState.entries) {
            shizuku = st
            val raw = executor.handle(req(EnvelopeOp.NODE_STATUS_GET, mapOf("args" to JsonObject(mapOf("path" to JsonPrimitive("/sdcard/Download/secret.txt"))))), dca)
            val result = bridgeJson.decodeFromString(BridgeResponse.serializer(), raw).result!!.toString()
            for (bad in listOf("/", "\\\\", "sdcard", "storage", "Download", ".txt", "content:", "file:")) {
                assertFalse("snapshot leaked '$bad': $result", result.contains(bad))
            }
        }
    }

    @Test fun codecRefusesPathsAndSecrets() {
        fun snap(detail: String) = NodeStatusSnapshot(nodeId, pkg, "EXTERNAL_NODE", now, listOf(StatusTrayDomainStatus(StatusTrayDomain.OTHER, StatusTrayState.OK, detail)))
        for (bad in listOf("/sdcard/a", "C:\\x", "report.pdf", "file:///x", "content://media", "api_token=1", "password", "x".repeat(201))) {
            try {
                NodeStatusSnapshotCodec.encode(snap(bad))
                fail("should refuse '$bad'")
            } catch (e: EnvelopeException) {
                assertEquals(RemoteError.INVALID_PARAMETERS, e.category)
            }
        }
        NodeStatusSnapshotCodec.encode(snap("shizuku: connected"))
    }

    @Test fun detailOmittedWhenNull() {
        val o = NodeStatusSnapshotCodec.encode(NodeStatusSnapshot(nodeId, null, "EXTERNAL_NODE", 1, listOf(StatusTrayDomainStatus(StatusTrayDomain.NODE_HEALTH, StatusTrayState.OK))))
        assertNull(o["domains"]!!.jsonArray[0].jsonObject["detail"])
    }

    @Test fun unknownDomainNamesDecodeAsOther() {
        assertEquals(StatusTrayDomain.OTHER, StatusTrayDomain.fromWire("satellite"))
        assertEquals(StatusTrayDomain.NODE_HEALTH, StatusTrayDomain.fromWire("node_health"))
    }

    @Test fun errorCodeSetIsFrozen() {
        assertEquals(
            setOf("invalid_parameters", "not_paired", "not_found", "internal", "unauthorized", "replay_detected", "expired",
                "external_control_disabled", "confirmation_required", "unsupported_op", "capability_off"),
            RemoteError.ALL,
        )
    }

    @Test fun handlerCrashMapsToInternal() {
        val boom = NodeOp("test.crash", OpKind.READ, true, false) { error("kaboom /secret/path") }
        val ex = BridgeExecutor(nodeId, externalControlEnabled = { true }, clock = { now }, extraOps = listOf(boom))
        val r = bridgeJson.decodeFromString(BridgeResponse.serializer(), ex.handle(cmd("test.crash"), dca))
        assertErr(RemoteError.INTERNAL, r)
        assertFalse(r.error!!.message.contains("/secret"))
    }
}
