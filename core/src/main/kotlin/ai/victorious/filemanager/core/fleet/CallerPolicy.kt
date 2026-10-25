package ai.victorious.filemanager.core.fleet

/**
 * Identity of a Messenger caller, resolved on the Android side from
 * Message.sendingUid / Binder.getCallingUid via PackageManager.
 *
 * @param certSha256 per package, lowercase hex SHA-256 digests of the
 *   signing certificates (no colons).
 */
data class CallerIdentity(
    val uid: Int,
    val packages: List<String>,
    val certSha256: Map<String, List<String>> = emptyMap(),
)

data class CallerDecision(
    val allowed: Boolean,
    val matchedPackage: String?,
    val reason: String,
    /** True when denied because no allowlisted (paired) package was found at all. */
    val notPaired: Boolean = false,
    /** Lowercase hex cert digest that identified the caller (null if none known). */
    val matchedCert: String? = null,
    /** Op-name prefixes this caller may invoke; null means every op. */
    val opScope: Set<String>? = null,
) {
    /** Replay-cache key half; derived only from authenticated identity. */
    val sourceNodeRef: String get() = "${matchedPackage.orEmpty()}#${matchedCert ?: "unpinned"}"

    fun mayInvoke(op: String): Boolean = opScope == null || opScope.any { op.startsWith(it) }
}

/**
 * Package allowlist plus optional signing-cert pinning.
 *
 * [certPins] maps package -> allowed SHA-256 digests. A package absent from
 * [certPins] (or an empty map, the default) is allowed by package name only.
 * Pins are supplied at build time (gradle property `fleetCallerCertPins`);
 * no hashes are hard-coded here.
 */
class CallerPolicy(
    val allowedPackages: Set<String> = DEFAULT_ALLOWED_PACKAGES,
    certPins: Map<String, Set<String>> = emptyMap(),
    /** Built-in mandatory pins; these override any build-time pin for the same package. */
    builtInPins: Map<String, Set<String>> = BUILT_IN_PINS,
    /** package -> allowed op-name prefixes. Packages absent here may call every op. */
    val opScopes: Map<String, Set<String>> = DEFAULT_OP_SCOPES,
) {
    private val pins: Map<String, Set<String>> =
        (certPins + builtInPins.filterKeys { it in allowedPackages })
            .mapValues { (_, v) -> v.map(::normalizeDigest).toSet() }.filterValues { it.isNotEmpty() }

    val pinningEnabled: Boolean get() = pins.isNotEmpty()

    fun evaluate(caller: CallerIdentity?): CallerDecision {
        if (caller == null || caller.packages.isEmpty()) {
            return CallerDecision(false, null, "caller identity could not be resolved", notPaired = true)
        }
        val candidates = caller.packages.filter { it in allowedPackages }
        if (candidates.isEmpty()) {
            return CallerDecision(
                false, null, "caller uid ${caller.uid} (${caller.packages.joinToString()}) is not allowlisted", notPaired = true,
            )
        }
        for (pkg in candidates) {
            val actual = caller.certSha256[pkg].orEmpty().map(::normalizeDigest)
            val required = pins[pkg]
                ?: return CallerDecision(true, pkg, "allowlisted package", matchedCert = actual.firstOrNull(), opScope = opScopes[pkg])
            actual.firstOrNull { it in required }?.let {
                return CallerDecision(true, pkg, "allowlisted package, pinned cert matched", matchedCert = it, opScope = opScopes[pkg])
            }
        }
        return CallerDecision(false, null, "signing certificate does not match pinned SHA-256 for ${candidates.joinToString()}")
    }

    companion object {
        const val KEYBOARD_DEBUG = "com.victorious.keyboard.debug"
        /** Release keyboard: intentionally NOT allowlisted (unpinned -> denied). */
        const val KEYBOARD_RELEASE = "com.victorious.keyboard"
        /** Victorious Keyboard debug signing cert (public fingerprint, not a secret). */
        const val KEYBOARD_DEBUG_CERT_SHA256 =
            "47:1E:AD:6B:3F:96:7E:F3:C8:63:EF:49:08:74:8B:3B:E3:63:F9:1D:75:0B:FD:B1:E9:28:05:BC:F1:6B:AF:4F"

        val DEFAULT_ALLOWED_PACKAGES = setOf("ai.droidcommand.app", "ai.droidcommand.app.debug", KEYBOARD_DEBUG)
        val BUILT_IN_PINS: Map<String, Set<String>> = mapOf(KEYBOARD_DEBUG to setOf(KEYBOARD_DEBUG_CERT_SHA256))
        val DEFAULT_OP_SCOPES: Map<String, Set<String>> = mapOf(KEYBOARD_DEBUG to setOf("media."))

        fun normalizeDigest(d: String): String = d.replace(":", "").trim().lowercase()

        /** Parses `pkg=hash[|hash];pkg2=hash` (empty string -> no pins). */
        fun parsePins(spec: String): Map<String, Set<String>> =
            spec.split(';').map { it.trim() }.filter { it.contains('=') }.associate { entry ->
                val (pkg, hashes) = entry.split('=', limit = 2)
                pkg.trim() to hashes.split('|').map(::normalizeDigest).filter { it.isNotEmpty() }.toSet()
            }
    }
}
