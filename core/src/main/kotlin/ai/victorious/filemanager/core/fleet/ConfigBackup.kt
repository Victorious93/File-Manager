package ai.victorious.filemanager.core.fleet

import ai.victorious.filemanager.core.bookmarks.Bookmark
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * `config.export` / `config.import` per DroidCommand AI
 * `docs/specs/UNIFIED_BACKUP.md` (FROZEN). VictorFiles contributes ONE
 * [UnifiedBackupEntry] (`packageName` + `schemaVersion` + ciphertext |
 * clearPayload). Payload = preferences + bookmarks only. Never file
 * contents, secret-bearing paths, cloud credentials or the external-control switch.
 */
object ConfigOps {
    const val EXPORT = "config.export"
    const val IMPORT = "config.import"
    val ALL = setOf(EXPORT, IMPORT)
}

enum class BackupWrapping(val wire: String) {
    RAW_AES_256_GCM("raw-aes-256-gcm"), ANDROID_KEYSTORE("android-keystore"), NONE("none");

    companion object {
        fun parse(s: String?): BackupWrapping? = entries.firstOrNull { it.wire == s }
    }
}

enum class BackupConflictPolicy(val wire: String) {
    SKIP("skip"), OVERWRITE("overwrite"), MERGE_BY_ID("merge-by-id");

    companion object {
        fun parse(s: String?): BackupConflictPolicy? = if (s == null) SKIP else entries.firstOrNull { it.wire == s }
    }
}

data class UnifiedBackupEntry(
    val packageName: String,
    val schemaVersion: Int,
    val ciphertext: String? = null,
    val clearPayload: String? = null,
)

data class UnifiedFleetBackup(
    val version: Int,
    val createdAtMs: Long,
    val sourceNodeRef: String,
    val wrapping: BackupWrapping,
    val entries: List<UnifiedBackupEntry>,
)

/** AES-GCM seam. AAD = `packageName|schemaVersion|sourceNodeRef`. */
interface FleetBackupAead {
    fun seal(plain: ByteArray, aad: String): String
    fun open(record: String, aad: String): ByteArray
}

/** `AES/GCM/NoPadding`, 12-byte IV, 128-bit tag, record `v1.<b64url iv>.<b64url ct+tag>` (no padding). */
class JceFleetBackupAead(private val key: SecretKey, private val random: SecureRandom = SecureRandom()) : FleetBackupAead {
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()

    override fun seal(plain: ByteArray, aad: String): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        c.updateAAD(aad.toByteArray())
        return "v1." + enc.encodeToString(iv) + "." + enc.encodeToString(c.doFinal(plain))
    }

    override fun open(record: String, aad: String): ByteArray {
        val parts = record.split('.')
        require(parts.size == 3 && parts[0] == "v1") { "bad record" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, dec.decode(parts[1])))
        c.updateAAD(aad.toByteArray())
        return c.doFinal(dec.decode(parts[2]))
    }
}

object UnifiedFleetBackupCodec {
    const val VERSION = 1

    /** Spec §3 never-in-a-payload names (case-insensitive substring), plus VictorFiles cloud credentials. */
    val FORBIDDEN = listOf(
        "password", "passwd", "passphrase", "apikey", "api_key", "api-key", "apitoken", "api_token", "token",
        "bearer", "secret", "privatekey", "private_key", "signingkey", "signing_key", "keystore", "pem",
        "codeword", "code_word", "whitelist", "twilio", "authtoken", "numberpool", "cookie",
        "credential", "oauth", "refresh", "pin",
    )

    fun aad(packageName: String, schemaVersion: Int, sourceNodeRef: String) = "$packageName|$schemaVersion|$sourceNodeRef"

    /** First forbidden name found in any key or string value of [el], else null. */
    fun secretScan(el: JsonElement): String? = when (el) {
        is JsonObject -> el.entries.firstNotNullOfOrNull { (k, v) -> hit(k) ?: secretScan(v) }
        is JsonArray -> el.firstNotNullOfOrNull(::secretScan)
        is JsonPrimitive -> if (el.isString) hit(el.content) else null
        JsonNull -> null
    }

    private fun hit(s: String): String? {
        val low = s.lowercase()
        return FORBIDDEN.firstOrNull { f ->
            // "pin" / "pem" are short: require a word-ish boundary to avoid "spinner", "open".
            if (f.length <= 3) Regex("(^|[^a-z])$f($|[^a-z])").containsMatchIn(low) else low.contains(f)
        }
    }

    fun encodeEntry(e: UnifiedBackupEntry): JsonObject = buildJsonObject {
        put("packageName", JsonPrimitive(e.packageName))
        put("schemaVersion", JsonPrimitive(e.schemaVersion))
        e.ciphertext?.let { put("ciphertext", JsonPrimitive(it)) }
        e.clearPayload?.let { put("clearPayload", JsonPrimitive(it)) }
    }

    fun encode(b: UnifiedFleetBackup): JsonObject = buildJsonObject {
        put("version", JsonPrimitive(b.version))
        put("createdAtMs", JsonPrimitive(b.createdAtMs))
        put("sourceNodeRef", JsonPrimitive(b.sourceNodeRef))
        put("wrapping", JsonPrimitive(b.wrapping.wire))
        put("entries", JsonArray(b.entries.map(::encodeEntry)))
    }

    fun decode(text: String): UnifiedFleetBackup {
        val o = runCatching { Json.parseToJsonElement(text) as JsonObject }.getOrNull() ?: bad("bundle is not a JSON object")
        val version = (o["version"] as? JsonPrimitive)?.longOrNull?.toInt() ?: bad("bundle.version missing")
        if (version != VERSION) bad("unknown bundle version $version")
        val wrapping = BackupWrapping.parse(o.str("wrapping")) ?: bad("unknown wrapping")
        val src = o.str("sourceNodeRef") ?: bad("sourceNodeRef missing")
        val created = (o["createdAtMs"] as? JsonPrimitive)?.longOrNull ?: bad("createdAtMs missing")
        val raw = o["entries"] as? JsonArray ?: bad("entries[] missing")
        val entries = raw.map { el ->
            val e = el as? JsonObject ?: bad("entry must be an object")
            val pkg = e.str("packageName")?.takeIf { it.isNotBlank() } ?: bad("entry.packageName missing")
            val sv = (e["schemaVersion"] as? JsonPrimitive)?.longOrNull?.toInt() ?: bad("entry.schemaVersion missing")
            val ct = e.str("ciphertext")
            val cp = e.str("clearPayload")
            if ((ct == null) == (cp == null)) bad("exactly one of ciphertext / clearPayload required ($pkg)")
            if (wrapping == BackupWrapping.NONE && cp == null) bad("wrapping=none requires clearPayload")
            if (wrapping != BackupWrapping.NONE && ct == null) bad("wrapping ${wrapping.wire} requires ciphertext")
            UnifiedBackupEntry(pkg, sv, ct, cp)
        }
        if (entries.map { it.packageName }.toSet().size != entries.size) bad("duplicate packageName")
        return UnifiedFleetBackup(version, created, src, wrapping, entries)
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun bad(m: String): Nothing = throw EnvelopeException(RemoteError.INVALID_PARAMETERS, m)
}

/** Live config the node owns. Implemented on the app side over SharedPreferences + BookmarkStore. */
interface NodeConfigStore {
    fun preferences(): Map<String, JsonPrimitive>
    fun bookmarks(): List<Bookmark>
    fun replacePreferences(prefs: Map<String, JsonPrimitive>)
    fun replaceBookmarks(bookmarks: List<Bookmark>)
    fun setExternalControlEnabled(enabled: Boolean)
}

data class ImportReport(
    val dryRun: Boolean,
    val policy: BackupConflictPolicy,
    val prefsAdded: List<String>,
    val prefsOverwritten: List<String>,
    val prefsSkipped: List<String>,
    val bookmarksAdded: List<String>,
    val bookmarksOverwritten: List<String>,
    val bookmarksSkipped: List<String>,
    val written: Boolean,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("dryRun", JsonPrimitive(dryRun))
        put("conflictPolicy", JsonPrimitive(policy.wire))
        put("written", JsonPrimitive(written))
        put("externalControlEnabled", JsonPrimitive(false))
        fun l(n: String, v: List<String>) = put(n, JsonArray(v.map(::JsonPrimitive)))
        l("preferencesAdded", prefsAdded); l("preferencesOverwritten", prefsOverwritten); l("preferencesSkipped", prefsSkipped)
        l("bookmarksAdded", bookmarksAdded); l("bookmarksOverwritten", bookmarksOverwritten); l("bookmarksSkipped", bookmarksSkipped)
    }
}

/**
 * VictorFiles payload schema v1: `{ "preferences": {key: primitive}, "bookmarks": [{id,path,label,createdAtEpochMs}] }`.
 */
class NodeConfigBackup(
    private val packageName: String,
    private val sourceNodeRef: String,
    private val store: NodeConfigStore,
    private val aead: FleetBackupAead?,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Bookmark path prefixes that must never leave the device (secure folder, cloud mounts, app-private dirs). */
    private val sensitivePathPrefixes: List<String> = emptyList(),
) {
    companion object {
        const val SCHEMA_VERSION = 1
        /** Never exported, and forced OFF after any import. */
        val EXCLUDED_PREF_KEYS = setOf("external_control_enabled", "externalControlEnabled")
    }

    private fun bad(m: String): Nothing = throw EnvelopeException(RemoteError.INVALID_PARAMETERS, m)

    private fun sensitivePath(p: String): Boolean =
        sensitivePathPrefixes.any { p == it || p.startsWith(it.trimEnd('/') + "/") } ||
            UnifiedFleetBackupCodec.secretScan(JsonPrimitive(p)) != null ||
            Regex("(?i)(^|/)(\\.?secure|\\.?vault|cloud|\\.ssh|android/data)(/|$)").containsMatchIn(p)

    fun payload(): JsonObject {
        val prefs = store.preferences()
            .filterKeys { it !in EXCLUDED_PREF_KEYS && UnifiedFleetBackupCodec.secretScan(JsonPrimitive(it)) == null }
            .filterValues { UnifiedFleetBackupCodec.secretScan(it) == null }
        val bms = store.bookmarks().filterNot { sensitivePath(it.path) || UnifiedFleetBackupCodec.secretScan(JsonPrimitive(it.label)) != null }
        val out = buildJsonObject {
            put("preferences", JsonObject(prefs))
            put(
                "bookmarks",
                JsonArray(
                    bms.map {
                        buildJsonObject {
                            put("id", JsonPrimitive(it.id)); put("path", JsonPrimitive(it.path))
                            put("label", JsonPrimitive(it.label)); put("createdAtEpochMs", JsonPrimitive(it.createdAtEpochMs))
                        }
                    },
                ),
            )
        }
        // Defense in depth: refuse rather than strip if anything slipped through.
        UnifiedFleetBackupCodec.secretScan(out)?.let { bad("refusing export: contains '$it'") }
        return out
    }

    /** `config.export` → one entry. [clearPayload] is local-dev / tests only. */
    fun export(clearPayload: Boolean): UnifiedBackupEntry {
        val text = payload().toString()
        if (clearPayload) return UnifiedBackupEntry(packageName, SCHEMA_VERSION, clearPayload = text)
        val a = aead ?: throw EnvelopeException(RemoteError.INTERNAL, "no backup wrapping key on this node")
        return UnifiedBackupEntry(packageName, SCHEMA_VERSION,
            ciphertext = a.seal(text.toByteArray(), UnifiedFleetBackupCodec.aad(packageName, SCHEMA_VERSION, sourceNodeRef)))
    }

    /** Standalone single-entry bundle for this node. */
    fun exportBundle(clearPayload: Boolean): UnifiedFleetBackup = UnifiedFleetBackup(
        UnifiedFleetBackupCodec.VERSION, clock(), sourceNodeRef,
        if (clearPayload) BackupWrapping.NONE else BackupWrapping.RAW_AES_256_GCM, listOf(export(clearPayload)),
    )

    /**
     * `config.import`. Caller MUST have obtained on-device confirmation unless [dryRun].
     * Always forces external control OFF after a write.
     */
    fun import(bundleText: String, policy: BackupConflictPolicy, dryRun: Boolean): ImportReport {
        val bundle = UnifiedFleetBackupCodec.decode(bundleText)
        val entry = bundle.entries.firstOrNull { it.packageName == packageName }
            ?: throw EnvelopeException(RemoteError.NOT_FOUND, "bundle has no entry for $packageName")
        if (entry.schemaVersion != SCHEMA_VERSION) bad("unsupported schemaVersion ${entry.schemaVersion}")
        val text = entry.clearPayload ?: run {
            val a = aead ?: throw EnvelopeException(RemoteError.INTERNAL, "no backup wrapping key on this node")
            try {
                String(a.open(entry.ciphertext!!, UnifiedFleetBackupCodec.aad(entry.packageName, entry.schemaVersion, bundle.sourceNodeRef)))
            } catch (t: Throwable) {
                throw EnvelopeException(RemoteError.INTERNAL, "decrypt failed (tampered or wrong key)")
            }
        }
        val payload = runCatching { Json.parseToJsonElement(text) as JsonObject }.getOrNull() ?: bad("payload is not a JSON object")
        UnifiedFleetBackupCodec.secretScan(payload)?.let { bad("refusing import: contains '$it'") }

        val inPrefs = (payload["preferences"] as? JsonObject).orEmpty()
            .filterKeys { it !in EXCLUDED_PREF_KEYS }
            .mapNotNull { (k, v) -> (v as? JsonPrimitive)?.let { k to it } }.toMap()
        val inBms = (payload["bookmarks"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = (o["id"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val path = (o["path"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            Bookmark(id, path, (o["label"] as? JsonPrimitive)?.content ?: path,
                (o["createdAtEpochMs"] as? JsonPrimitive)?.longOrNull ?: 0L)
        }.filterNot { sensitivePath(it.path) }

        val livePrefs = store.preferences()
        val liveBms = store.bookmarks()
        val (newPrefs, pa, po, ps) = mergeMap(livePrefs, inPrefs, policy)
        val (newBms, ba, bo, bs) = mergeById(liveBms, inBms, policy)
        if (!dryRun) {
            store.replacePreferences(newPrefs.filterKeys { it !in EXCLUDED_PREF_KEYS } +
                livePrefs.filterKeys { it in EXCLUDED_PREF_KEYS })
            store.replaceBookmarks(newBms)
        }
        // Never auto-enable remote control, even on dry run paths that touch nothing else.
        if (!dryRun) store.setExternalControlEnabled(false)
        return ImportReport(dryRun, policy, pa, po, ps, ba, bo, bs, written = !dryRun)
    }

    private data class Merge<T>(val result: T, val added: List<String>, val overwritten: List<String>, val skipped: List<String>)

    private fun <V> mergeMap(live: Map<String, V>, incoming: Map<String, V>, p: BackupConflictPolicy): Merge<Map<String, V>> {
        val out = LinkedHashMap(live)
        val a = mutableListOf<String>(); val o = mutableListOf<String>(); val s = mutableListOf<String>()
        for ((k, v) in incoming) {
            when {
                k !in live -> if (p == BackupConflictPolicy.SKIP) s += k else { out[k] = v; a += k }
                p == BackupConflictPolicy.OVERWRITE -> { out[k] = v; o += k }
                else -> s += k
            }
        }
        return Merge(out, a, o, s)
    }

    private fun mergeById(live: List<Bookmark>, incoming: List<Bookmark>, p: BackupConflictPolicy): Merge<List<Bookmark>> {
        val m = mergeMap(live.associateBy { it.id }, incoming.associateBy { it.id }, p)
        return Merge(m.result.values.toList(), m.added, m.overwritten, m.skipped)
    }
}
