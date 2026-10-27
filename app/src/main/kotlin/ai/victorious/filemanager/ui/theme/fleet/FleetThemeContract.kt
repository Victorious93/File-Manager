package ai.victorious.filemanager.ui.theme.fleet

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Read-only client side of VictorLaunch's shared fleet theme, schemaVersion 1
 * (Victorious93/victorlaunch docs/specs/FLEET_THEME.md). Everything here is pure so it is unit-tested.
 */
object FleetThemeContract {
    const val PROVIDER_PACKAGE = "com.victorious.launch"
    const val AUTHORITY = "com.victorious.launch.theme"
    const val URI = "content://$AUTHORITY/current"
    const val ACTION_CHANGED = "com.victorious.launch.action.FLEET_THEME_CHANGED"
    const val SCHEMA_VERSION = 1

    /**
     * SHA-256 signing-cert pins for [PROVIDER_PACKAGE]. The fleet does not share a signing key, so the
     * provider's signature permission cannot be relied on; we check package + cert ourselves.
     * VictorLaunch debug cert, from victorlaunch FleetManifest.kt / docs/fleet-manifest.md (the old
     * 1B:06… key was revoked). Add the release cert here once VictorLaunch publishes one.
     */
    val PROVIDER_CERT_PINS: Set<String> = setOf(
        "B9:9A:AF:E2:C1:41:8B:5D:64:AA:0D:4B:AC:61:FC:F7:A7:BA:81:44:35:E0:EF:89:E8:AA:55:BE:56:4E:4A:69",
    )

    val PALETTE_KEYS = listOf("background", "surface", "primary", "secondary", "onBackground", "muted", "danger", "stale", "tile")

    /** Uppercase colon form; accepts "b99aaf…" or "B9:9A:…". */
    fun normalizeCert(digest: String): String {
        val hex = digest.filter { it.isLetterOrDigit() }.uppercase()
        return hex.chunked(2).joinToString(":")
    }

    /** Trusted only if it is the expected package and every current signer is pinned. */
    fun isTrustedProvider(packageName: String?, signerDigests: List<String>): Boolean {
        if (packageName != PROVIDER_PACKAGE || signerDigests.isEmpty()) return false
        val pins = PROVIDER_CERT_PINS.map(::normalizeCert).toSet()
        return signerDigests.all { normalizeCert(it) in pins }
    }

    /** `#RRGGBB` or `#AARRGGBB` to ARGB; null when malformed. */
    fun parseHex(value: String?): Long? {
        val v = value?.trim() ?: return null
        if (!v.startsWith("#")) return null
        val body = v.substring(1)
        if (body.length != 6 && body.length != 8) return null
        val n = body.toLongOrNull(16) ?: return null
        return if (body.length == 6) 0xFF000000L or n else n
    }

    /**
     * Parses one provider row given as column name → value (unknown columns ignored). Palette columns win;
     * the `json` snapshot fills any missing ones. Rejects anything but schemaVersion 1.
     */
    fun parseRow(row: Map<String, Any?>): FleetThemeResult {
        val json = (row["json"] as? String)?.takeIf { it.isNotBlank() }?.let { s ->
            runCatching { Json.parseToJsonElement(s).jsonObject }.getOrNull()
        }
        val version = (row["schemaVersion"] as? Number)?.toInt()
            ?: (row["schemaVersion"] as? String)?.toIntOrNull()
            ?: json?.get("schemaVersion")?.jsonPrimitive?.intOrNull
            ?: return FleetThemeResult.Invalid("missing schemaVersion")
        if (version != SCHEMA_VERSION) return FleetThemeResult.UnsupportedVersion(version)
        val jsonPalette = json?.get("palette") as? JsonObject
        val colors = mutableMapOf<String, Long>()
        for (key in PALETTE_KEYS) {
            val hex = (row[key] as? String) ?: jsonPalette?.get(key)?.jsonPrimitive?.contentOrNull
            parseHex(hex)?.let { colors[key] = it }
        }
        val required = listOf("background", "surface", "primary", "secondary", "onBackground")
        val missing = required.filter { it !in colors }
        if (missing.isNotEmpty()) return FleetThemeResult.Invalid("missing or bad colours: ${missing.joinToString()}")
        fun str(k: String) = (row[k] as? String) ?: json?.get(k)?.jsonPrimitive?.contentOrNull
        val updated = (row["updatedAtMs"] as? Number)?.toLong() ?: json?.get("updatedAtMs")?.jsonPrimitive?.longOrNull ?: 0L
        return FleetThemeResult.Ok(
            FleetPalette(
                mode = str("mode").orEmpty(),
                background = colors.getValue("background"),
                surface = colors.getValue("surface"),
                primary = colors.getValue("primary"),
                secondary = colors.getValue("secondary"),
                onBackground = colors.getValue("onBackground"),
                muted = colors["muted"],
                danger = colors["danger"],
                updatedAtMs = updated,
            ),
        )
    }

    /** Just the JSON snapshot (e.g. a provider that only fills `json`). */
    fun parseJson(json: String): FleetThemeResult = parseRow(mapOf("json" to json))
}

/** Colours (ARGB) taken from VictorLaunch's palette.* fields. */
data class FleetPalette(
    val mode: String,
    val background: Long,
    val surface: Long,
    val primary: Long,
    val secondary: Long,
    val onBackground: Long,
    val muted: Long? = null,
    val danger: Long? = null,
    val updatedAtMs: Long = 0L,
)

sealed interface FleetThemeResult {
    data class Ok(val palette: FleetPalette) : FleetThemeResult
    data class UnsupportedVersion(val version: Int) : FleetThemeResult
    data class Invalid(val reason: String) : FleetThemeResult
}

/** What the client last saw. Anything but [Applied] means "use the user's own theme". */
sealed interface FleetThemeState {
    data class Applied(val palette: FleetPalette) : FleetThemeState
    data class Unavailable(val reason: String) : FleetThemeState
}

/** Outcome of one read attempt, mapped to a state. Pure so the fallback rules are tested. */
fun fleetStateFor(attempt: Result<FleetThemeResult?>): FleetThemeState {
    val r = attempt.getOrElse { e ->
        return FleetThemeState.Unavailable(
            if (e is SecurityException) "VictorLaunch has not trusted this app (add a pin or 'Trust installed fleet share apps')"
            else "VictorLaunch theme read failed: ${e.javaClass.simpleName}",
        )
    } ?: return FleetThemeState.Unavailable("VictorLaunch theme provider not installed")
    return when (r) {
        is FleetThemeResult.Ok -> FleetThemeState.Applied(r.palette)
        is FleetThemeResult.UnsupportedVersion -> FleetThemeState.Unavailable("Unsupported fleet theme schema ${r.version}")
        is FleetThemeResult.Invalid -> FleetThemeState.Unavailable("Bad fleet theme: ${r.reason}")
    }
}
