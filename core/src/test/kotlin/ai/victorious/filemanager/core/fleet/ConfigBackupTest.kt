package ai.victorious.filemanager.core.fleet

import ai.victorious.filemanager.core.bookmarks.Bookmark
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

class ConfigBackupTest {
    private val pkg = "ai.victorious.filemanager"
    private val node = "fm-0123456789abcdef0123456789abcdef"
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    private val aead = JceFleetBackupAead(key)

    class FakeStore(
        var prefs: Map<String, JsonPrimitive>,
        var bms: List<Bookmark>,
        var external: Boolean = false,
        var writes: Int = 0,
    ) : NodeConfigStore {
        override fun preferences() = prefs
        override fun bookmarks() = bms
        override fun replacePreferences(prefs: Map<String, JsonPrimitive>) { this.prefs = prefs; writes++ }
        override fun replaceBookmarks(bookmarks: List<Bookmark>) { bms = bookmarks; writes++ }
        override fun setExternalControlEnabled(enabled: Boolean) { external = enabled }
    }

    private fun bm(id: String, path: String) = Bookmark(id, path, "L-$id", 1L)

    private fun richStore() = FakeStore(
        prefs = mapOf(
            "theme" to JsonPrimitive("dark"),
            "show_hidden" to JsonPrimitive(true),
            "sort_order" to JsonPrimitive("name"),
            "external_control_enabled" to JsonPrimitive(true),
            "cloud_access_token" to JsonPrimitive("ya29.SECRETVALUE"),
            "webdav_password" to JsonPrimitive("hunter2"),
            "ai_api_key" to JsonPrimitive("sk-abc"),
            "note" to JsonPrimitive("Bearer abc.def"),
        ),
        bms = listOf(
            bm("b1", "/storage/emulated/0/Download"),
            bm("b2", "/storage/emulated/0/.secure/vaulted"),
            bm("b3", "/data/user/0/ai.victorious.filemanager/files/secure"),
            bm("b4", "/storage/emulated/0/Android/data/com.x"),
            bm("b5", "/storage/emulated/0/tokens"),
        ),
    )

    private fun backup(store: NodeConfigStore, sensitive: List<String> = listOf("/data/user/0/ai.victorious.filemanager")) =
        NodeConfigBackup(pkg, node, store, aead, clock = { 42L }, sensitivePathPrefixes = sensitive)

    private fun allStrings(e: JsonElement): List<String> = when (e) {
        is JsonObject -> e.keys.toList() + e.values.flatMap(::allStrings)
        is JsonArray -> e.flatMap(::allStrings)
        is JsonPrimitive -> listOf(e.content)
        else -> emptyList()
    }

    @Test fun exportHasNoTokensOrSecrets() {
        val p = backup(richStore()).payload()
        val text = p.toString()
        for (bad in listOf("ya29", "SECRETVALUE", "hunter2", "sk-abc", "Bearer", "token", "password", "api_key", "secret", "external_control")) {
            assertFalse("export leaked '$bad': $text", text.contains(bad, ignoreCase = true))
        }
        assertNull(UnifiedFleetBackupCodec.secretScan(p))
        assertEquals(setOf("preferences", "bookmarks"), p.keys)
        assertEquals(setOf("theme", "show_hidden", "sort_order"), p["preferences"]!!.jsonObject.keys)
    }

    @Test fun exportDropsSecretBearingPaths() {
        val ids = backup(richStore()).payload()["bookmarks"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals(listOf("b1"), ids)
    }

    @Test fun exportIsPrefsAndBookmarksOnlyNoContents() {
        val s = allStrings(backup(richStore()).payload())
        assertTrue(s.none { it.contains("content", true) || it.contains("bytes", true) })
    }

    @Test fun encryptedExportEntryShape() {
        val e = backup(richStore()).export(clearPayload = false)
        assertEquals(pkg, e.packageName)
        assertEquals(1, e.schemaVersion)
        assertNull(e.clearPayload)
        assertTrue(e.ciphertext!!.matches(Regex("^v1\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$")))
        assertFalse(e.ciphertext!!.contains("theme"))
    }

    @Test fun exportWithoutKeyFailsUnlessClear() {
        val b = NodeConfigBackup(pkg, node, richStore(), null)
        try { b.export(false); fail() } catch (e: EnvelopeException) { assertEquals(RemoteError.INTERNAL, e.category) }
        assertNotNull(b.export(true).clearPayload)
    }

    @Test fun encryptedRoundTrip() {
        val src = richStore()
        val text = UnifiedFleetBackupCodec.encode(backup(src).exportBundle(false)).toString()
        val dst = FakeStore(emptyMap(), emptyList())
        val r = backup(dst).import(text, BackupConflictPolicy.OVERWRITE, dryRun = false)
        assertTrue(r.written)
        assertEquals("dark", dst.prefs["theme"]!!.content)
        assertEquals(listOf("b1"), dst.bms.map { it.id })
    }

    @Test fun aadBindsPackageAndExporter() {
        val bundle = backup(richStore()).exportBundle(false)
        val tampered = bundle.copy(sourceNodeRef = "someone-else")
        try {
            backup(FakeStore(emptyMap(), emptyList())).import(UnifiedFleetBackupCodec.encode(tampered).toString(), BackupConflictPolicy.SKIP, false)
            fail()
        } catch (e: EnvelopeException) { assertEquals(RemoteError.INTERNAL, e.category) }
    }

    private fun clearBundle(payload: String, pkgName: String = pkg, wrapping: String = "none"): String =
        """{"version":1,"createdAtMs":1,"sourceNodeRef":"controller","wrapping":"$wrapping","entries":[{"packageName":"$pkgName","schemaVersion":1,"clearPayload":${JsonPrimitive(payload)}}]}"""

    private val incoming = """{"preferences":{"theme":"light","grid":true,"external_control_enabled":true},
        "bookmarks":[{"id":"b1","path":"/x/new","label":"N","createdAtEpochMs":5},{"id":"b9","path":"/x/nine","label":"9","createdAtEpochMs":6}]}"""

    private fun live() = FakeStore(mapOf("theme" to JsonPrimitive("dark")), listOf(bm("b1", "/x/old")))

    @Test fun skipKeepsLiveAndDropsImported() {
        val s = live()
        val r = backup(s).import(clearBundle(incoming), BackupConflictPolicy.SKIP, false)
        assertEquals("dark", s.prefs["theme"]!!.content)
        assertNull(s.prefs["grid"])
        assertEquals(listOf("/x/old"), s.bms.map { it.path })
        assertEquals(listOf("theme", "grid"), r.prefsSkipped)
    }

    @Test fun overwriteReplacesAndAdds() {
        val s = live()
        backup(s).import(clearBundle(incoming), BackupConflictPolicy.OVERWRITE, false)
        assertEquals("light", s.prefs["theme"]!!.content)
        assertEquals(true, s.prefs["grid"]!!.content.toBoolean())
        assertEquals(setOf("/x/new", "/x/nine"), s.bms.map { it.path }.toSet())
    }

    @Test fun mergeByIdLiveWinsPlusAddMissing() {
        val s = live()
        val r = backup(s).import(clearBundle(incoming), BackupConflictPolicy.MERGE_BY_ID, false)
        assertEquals("dark", s.prefs["theme"]!!.content)
        assertEquals(true, s.prefs["grid"]!!.content.toBoolean())
        assertEquals(mapOf("b1" to "/x/old", "b9" to "/x/nine"), s.bms.associate { it.id to it.path })
        assertEquals(listOf("b9"), r.bookmarksAdded)
        assertEquals(listOf("b1"), r.bookmarksSkipped)
    }

    @Test fun dryRunWritesNothing() {
        val s = live().apply { external = true }
        val r = backup(s).import(clearBundle(incoming), BackupConflictPolicy.OVERWRITE, dryRun = true)
        assertFalse(r.written)
        assertEquals(0, s.writes)
        assertEquals("dark", s.prefs["theme"]!!.content)
        assertEquals(listOf("theme"), r.prefsOverwritten)
    }

    @Test fun importNeverEnablesExternalControl() {
        for (p in BackupConflictPolicy.entries) {
            val s = live().apply { external = true }
            backup(s).import(clearBundle(incoming), p, false)
            assertFalse(s.external)
            assertNull(s.prefs["external_control_enabled"])
        }
    }

    @Test fun importRefusesSecrets() {
        for (bad in listOf("""{"preferences":{"apiKey":"x"}}""", """{"preferences":{"a":"my password"}}""",
            """{"preferences":{"twilio_sid":"x"}}""", """{"bookmarks":[{"id":"1","path":"/x","label":"authToken"}]}""")) {
            try { backup(live()).import(clearBundle(bad), BackupConflictPolicy.OVERWRITE, false); fail(bad) }
            catch (e: EnvelopeException) { assertEquals(RemoteError.INVALID_PARAMETERS, e.category) }
        }
    }

    @Test fun bundleValidation() {
        fun expect(cat: String, text: String) {
            try { backup(live()).import(text, BackupConflictPolicy.SKIP, true); fail(text) } catch (e: EnvelopeException) { assertEquals(text, cat, e.category) }
        }
        expect(RemoteError.INVALID_PARAMETERS, "not json")
        expect(RemoteError.INVALID_PARAMETERS, clearBundle(incoming).replace("\"version\":1", "\"version\":2"))
        expect(RemoteError.INVALID_PARAMETERS, clearBundle(incoming, wrapping = "rot13"))
        expect(RemoteError.INVALID_PARAMETERS, clearBundle(incoming, wrapping = "raw-aes-256-gcm"))
        expect(RemoteError.NOT_FOUND, clearBundle(incoming, pkgName = "com.other"))
        val dup = """{"version":1,"createdAtMs":1,"sourceNodeRef":"c","wrapping":"none","entries":[{"packageName":"a","schemaVersion":1,"clearPayload":"{}"},{"packageName":"a","schemaVersion":1,"clearPayload":"{}"}]}"""
        expect(RemoteError.INVALID_PARAMETERS, dup)
        val both = """{"version":1,"createdAtMs":1,"sourceNodeRef":"c","wrapping":"none","entries":[{"packageName":"$pkg","schemaVersion":1,"clearPayload":"{}","ciphertext":"v1.a.b"}]}"""
        expect(RemoteError.INVALID_PARAMETERS, both)
    }

    @Test fun conflictPolicyParse() {
        assertEquals(BackupConflictPolicy.SKIP, BackupConflictPolicy.parse(null))
        assertEquals(BackupConflictPolicy.MERGE_BY_ID, BackupConflictPolicy.parse("merge-by-id"))
        assertNull(BackupConflictPolicy.parse("merge"))
    }

    // ---- bridge surface ----

    private var now = 1_760_920_000_000L
    private var n = 0
    private var confirmed = false
    private val dca = CallerIdentity(1, listOf("ai.droidcommand.app"), mapOf("ai.droidcommand.app" to listOf("aa".repeat(32))))

    private fun exec(store: NodeConfigStore, remote: Boolean = false) = BridgeExecutor(
        node, externalControlEnabled = { true }, packageName = pkg, clock = { now },
        confirmation = { _, _ -> confirmed }, configBackup = backup(store), configRemoteAllowed = remote,
    )

    private fun req(op: String, args: Map<String, JsonElement>, top: Map<String, JsonElement> = emptyMap()): String {
        val p = mapOf("nonce" to JsonPrimitive("%032x".format(++n)), "timestampMs" to JsonPrimitive(now), "args" to JsonObject(args)) + top
        return bridgeJson.encodeToString(BridgeRequest.serializer(), BridgeRequest(requestId = "r$n", operation = op, parameters = JsonObject(p)))
    }

    private fun call(e: BridgeExecutor, j: String) = bridgeJson.decodeFromString(BridgeResponse.serializer(), e.handle(j, dca))

    @Test fun descriptorsAdvertised() {
        val r = call(exec(live()), req("capabilities.get", emptyMap()))
        val ops = r.result!!["ops"]!!.jsonArray.map { it.jsonObject }.associateBy { it["name"]!!.jsonPrimitive.content }
        val ex = ops.getValue("config.export"); val im = ops.getValue("config.import")
        assertEquals(listOf("action", "false", "false"), listOf("kind", "remoteAllowed", "requiresConfirm").map { ex[it]!!.jsonPrimitive.content })
        assertEquals(listOf("action", "false", "true"), listOf("kind", "remoteAllowed", "requiresConfirm").map { im[it]!!.jsonPrimitive.content })
    }

    @Test fun remoteCallersGetCapabilityOffByDefault() {
        val s = live()
        val e = exec(s)
        for (j in listOf(req("config.export", emptyMap()),
            req("config.import", mapOf("bundle" to JsonPrimitive(clearBundle(incoming)), "conflictPolicy" to JsonPrimitive("overwrite")))))
            assertEquals(RemoteError.CAPABILITY_OFF, call(e, j).error!!.category)
        assertEquals(0, s.writes)
    }

    @Test fun importOverBridgeNeedsConfirmationEvenWhenRemoteAllowed() {
        val s = live()
        val e = exec(s, remote = true)
        val args = mapOf("bundle" to JsonPrimitive(clearBundle(incoming)), "conflictPolicy" to JsonPrimitive("overwrite"))
        assertEquals(RemoteError.CONFIRMATION_REQUIRED, call(e, req("config.import", args)).error!!.category)
        assertEquals(0, s.writes)
        // dryRun (args string or envelope top-level) validates without confirmation and writes nothing.
        assertTrue(call(e, req("config.import", args + ("dryRun" to JsonPrimitive("true")))).ok)
        val top = call(e, req("config.import", args, mapOf("dryRun" to JsonPrimitive(true))))
        assertTrue(top.ok)
        assertEquals("false", top.result!!["written"]!!.jsonPrimitive.content)
        assertEquals(0, s.writes)
        confirmed = true
        val ok = call(e, req("config.import", args))
        assertTrue(ok.ok)
        assertEquals("false", ok.result!!["externalControlEnabled"]!!.jsonPrimitive.content)
        assertFalse(s.external)
    }

    @Test fun exportOverBridgeWhenRemoteAllowedHasNoSecrets() {
        val e = exec(richStore(), remote = true)
        val r = call(e, req("config.export", mapOf("clearPayload" to JsonPrimitive("true"))))
        assertTrue(r.ok)
        val txt = r.result.toString()
        assertFalse(txt.contains("hunter2") || txt.contains("ya29") || txt.contains("sk-abc"))
        assertEquals(RemoteError.NOT_FOUND, call(e, req("config.export", mapOf("packageName" to JsonPrimitive("com.other")))).error!!.category)
    }

    @Test fun keyboardCannotReachConfigOps() {
        val kb = CallerIdentity(2, listOf(CallerPolicy.KEYBOARD_DEBUG), mapOf(CallerPolicy.KEYBOARD_DEBUG to listOf(CallerPolicy.normalizeDigest(CallerPolicy.KEYBOARD_DEBUG_CERT_SHA256))))
        val r = bridgeJson.decodeFromString(BridgeResponse.serializer(), exec(live(), remote = true).handle(req("config.export", emptyMap()), kb))
        assertEquals(RemoteError.UNAUTHORIZED, r.error!!.category)
    }

    @Test fun noConfigSurfaceIsCapabilityOff() {
        val e = BridgeExecutor(node, externalControlEnabled = { true }, clock = { now })
        assertEquals(RemoteError.CAPABILITY_OFF, call(e, req("config.export", emptyMap())).error!!.category)
    }
}
