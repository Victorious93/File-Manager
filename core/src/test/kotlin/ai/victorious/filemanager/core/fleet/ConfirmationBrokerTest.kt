package ai.victorious.filemanager.core.fleet

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmationBrokerTest {
    private val nodeId = "fm-0123456789abcdef0123456789abcdef"
    private var now = 1_760_920_000_000L
    private var n = 0
    private val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
    private val broker = ConfirmationBroker(clock = { now }, schedule = { d, b -> scheduled += d to b })
    private val ran = mutableListOf<String>()
    private val wipe = NodeOp("test.wipe", OpKind.DESTRUCTIVE, true, true) { ran += "wipe"; JsonObject(mapOf("done" to JsonPrimitive(true))) }
    private val read = NodeOp("test.read", OpKind.READ, true, false) { ran += "read"; JsonObject(emptyMap()) }
    private val ex = BridgeExecutor(nodeId, externalControlEnabled = { true }, clock = { now }, extraOps = listOf(wipe, read), confirmationBroker = broker)
    private val dca = CallerIdentity(1, listOf("ai.droidcommand.app"), mapOf("ai.droidcommand.app" to listOf("aa".repeat(32))))
    private val replies = mutableListOf<BridgeResponse>()

    private fun req(op: String, extra: Map<String, JsonElement> = emptyMap(), ttl: Long = 120_000): String {
        val p = mapOf("nonce" to JsonPrimitive("%032x".format(++n)), "timestampMs" to JsonPrimitive(now), "ttlMs" to JsonPrimitive(ttl)) + extra
        return bridgeJson.encodeToString(BridgeRequest.serializer(), BridgeRequest(requestId = "r$n", operation = op, parameters = JsonObject(p)))
    }

    private fun send(j: String) = ex.handleAsync(j, dca) { replies += bridgeJson.decodeFromString(BridgeResponse.serializer(), it) }

    private fun wipeCmd(args: Map<String, JsonElement> = emptyMap(), ttl: Long = 120_000) =
        req("remote.command", mapOf("actionRef" to JsonPrimitive("test.wipe"), "args" to JsonObject(args)), ttl)

    @Test fun heldUntilApproved() {
        send(wipeCmd())
        assertTrue(replies.isEmpty())
        val p = broker.pending().single()
        assertEquals("test.wipe", p.actionRef)
        assertEquals("remote.command", p.op)
        assertEquals("ai.droidcommand.app", p.callerPackage)
        assertTrue(ran.isEmpty())
        assertTrue(broker.decide(p.id, p.nonce, approve = true))
        assertTrue(replies.single().ok)
        assertEquals(listOf("wipe"), ran)
    }

    @Test fun denyReturnsConfirmationRequired() {
        send(wipeCmd())
        val p = broker.pending().single()
        broker.decide(p.id, p.nonce, approve = false)
        assertEquals(RemoteError.CONFIRMATION_REQUIRED, replies.single().error!!.category)
        assertTrue(replies.single().error!!.message.contains("denied"))
        assertTrue(ran.isEmpty())
    }

    @Test fun decisionIsSingleUseAndNonceBound() {
        send(wipeCmd())
        val p = broker.pending().single()
        assertFalse(broker.decide(p.id, "f".repeat(32), approve = true))
        assertTrue(replies.isEmpty())
        assertTrue(broker.decide(p.id, p.nonce, approve = true))
        assertFalse(broker.decide(p.id, p.nonce, approve = true))
        assertEquals(1, replies.size)
        assertEquals(listOf("wipe"), ran)
    }

    @Test fun timeoutDeniesWithinTtl() {
        send(wipeCmd(ttl = 30_000))
        assertEquals(30_000L, scheduled.single().first)
        now += 30_000
        scheduled.single().second()
        assertEquals(RemoteError.CONFIRMATION_REQUIRED, replies.single().error!!.category)
        assertTrue(replies.single().error!!.message.contains("timed out"))
        assertTrue(broker.pending().isEmpty())
        assertTrue(ran.isEmpty())
    }

    @Test fun lateApprovalCountsAsTimeout() {
        send(wipeCmd(ttl = 10_000))
        val p = broker.pending().single()
        now += 10_001
        assertTrue(broker.decide(p.id, p.nonce, approve = true))
        assertFalse(replies.single().ok)
        assertTrue(ran.isEmpty())
    }

    @Test fun replayOfHeldNonceRejected() {
        val j = wipeCmd()
        send(j); send(j)
        assertEquals(RemoteError.REPLAY_DETECTED, replies.single().error!!.category)
        assertEquals(1, broker.pending().size)
    }

    @Test fun nonConfirmOpsAnswerImmediately() {
        send(req("remote.command", mapOf("actionRef" to JsonPrimitive("test.read"))))
        assertTrue(replies.single().ok)
        assertTrue(broker.pending().isEmpty())
    }

    @Test fun macroRunDestructiveStepIsNotHeld() {
        val steps = kotlinx.serialization.json.JsonArray(listOf(JsonObject(mapOf("op" to JsonPrimitive("test.wipe")))))
        send(req("macro.run", mapOf("steps" to steps)))
        assertTrue(replies.single().ok)
        assertTrue(broker.pending().isEmpty())
        assertTrue(ran.isEmpty())
    }

    @Test fun syncHandleNeverHolds() {
        val r = bridgeJson.decodeFromString(BridgeResponse.serializer(), ex.handle(wipeCmd(), dca))
        assertEquals(RemoteError.CONFIRMATION_REQUIRED, r.error!!.category)
        assertTrue(broker.pending().isEmpty())
    }

    @Test fun capacityLimitDeniesImmediately() {
        val small = ConfirmationBroker(clock = { now }, maxPending = 1)
        val e = BridgeExecutor(nodeId, externalControlEnabled = { true }, clock = { now }, extraOps = listOf(wipe), confirmationBroker = small)
        e.handleAsync(wipeCmd(), dca) { replies += bridgeJson.decodeFromString(BridgeResponse.serializer(), it) }
        e.handleAsync(wipeCmd(), dca) { replies += bridgeJson.decodeFromString(BridgeResponse.serializer(), it) }
        assertEquals(1, replies.size)
        assertEquals(RemoteError.CONFIRMATION_REQUIRED, replies.single().error!!.category)
    }

    @Test fun listenerSeesPendingAndClears() {
        val seen = mutableListOf<Int>()
        broker.addListener { seen += it.size }
        send(wipeCmd())
        val p = broker.pending().single()
        broker.decide(p.id, p.nonce, false)
        assertEquals(listOf(0, 1, 0), seen)
    }

    @Test fun summaryRedactsSecretsAndPaths() {
        val s = ConfirmationBroker.summarize(JsonObject(mapOf(
            "password" to JsonPrimitive("hunter2"),
            "path" to JsonPrimitive("/sdcard/private.txt"),
            "note" to JsonPrimitive("my api_token abc"),
            "count" to JsonPrimitive(3),
            "mode" to JsonPrimitive("fast"),
            "long" to JsonPrimitive("x".repeat(100)),
        )))
        val joined = s.joinToString("\n")
        for (bad in listOf("hunter2", "/sdcard", "private.txt", "abc", "xxxxxxxxxx")) assertFalse(joined, joined.contains(bad))
        assertTrue(joined.contains("count: 3"))
        assertTrue(joined.contains("mode: fast"))
    }

    @Test fun summaryShownForHeldRequestHasNoSecrets() {
        send(wipeCmd(mapOf("token" to JsonPrimitive("SECRET"), "target" to JsonPrimitive("/data/x"))))
        val lines = broker.pending().single().paramsSummary.joinToString()
        assertFalse(lines.contains("SECRET"))
        assertFalse(lines.contains("/data"))
    }

    @Test fun approvedConfigImportViaBrokerStillForcesExternalOff() {
        // config.import is holdable but remoteAllowed:false by default -> capability_off, never held.
        val store = ConfigBackupTest.FakeStore(emptyMap(), emptyList(), external = true)
        val e = BridgeExecutor(nodeId, externalControlEnabled = { true }, clock = { now }, confirmationBroker = broker,
            configBackup = NodeConfigBackup("ai.victorious.filemanager", nodeId, store, null))
        e.handleAsync(req("config.import", mapOf("args" to JsonObject(mapOf("bundle" to JsonPrimitive("{}"))))), dca) {
            replies += bridgeJson.decodeFromString(BridgeResponse.serializer(), it)
        }
        assertEquals(RemoteError.CAPABILITY_OFF, replies.single().error!!.category)
        assertTrue(broker.pending().isEmpty())
        assertNull(replies.single().result)
        assertTrue(store.external)
        assertEquals("r$n", replies.single().requestId)
    }
}
