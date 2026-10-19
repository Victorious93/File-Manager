package ai.victorious.victorfiles.core.fleet

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

data class CallerDecision(val allowed: Boolean, val matchedPackage: String?, val reason: String)

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
) {
    private val pins: Map<String, Set<String>> =
        certPins.mapValues { (_, v) -> v.map(::normalizeDigest).toSet() }.filterValues { it.isNotEmpty() }

    val pinningEnabled: Boolean get() = pins.isNotEmpty()

    fun evaluate(caller: CallerIdentity?): CallerDecision {
        if (caller == null || caller.packages.isEmpty()) {
            return CallerDecision(false, null, "caller identity could not be resolved")
        }
        val candidates = caller.packages.filter { it in allowedPackages }
        if (candidates.isEmpty()) {
            return CallerDecision(false, null, "caller uid ${caller.uid} (${caller.packages.joinToString()}) is not allowlisted")
        }
        for (pkg in candidates) {
            val required = pins[pkg] ?: return CallerDecision(true, pkg, "allowlisted package")
            val actual = caller.certSha256[pkg].orEmpty().map(::normalizeDigest)
            if (actual.any { it in required }) return CallerDecision(true, pkg, "allowlisted package, pinned cert matched")
        }
        return CallerDecision(false, null, "signing certificate does not match pinned SHA-256 for ${candidates.joinToString()}")
    }

    companion object {
        val DEFAULT_ALLOWED_PACKAGES = setOf("ai.droidcommand.app", "ai.droidcommand.app.debug")

        fun normalizeDigest(d: String): String = d.replace(":", "").trim().lowercase()

        /** Parses `pkg=hash[|hash];pkg2=hash` (empty string -> no pins). */
        fun parsePins(spec: String): Map<String, Set<String>> =
            spec.split(';').map { it.trim() }.filter { it.contains('=') }.associate { entry ->
                val (pkg, hashes) = entry.split('=', limit = 2)
                pkg.trim() to hashes.split('|').map(::normalizeDigest).filter { it.isNotEmpty() }.toSet()
            }
    }
}
