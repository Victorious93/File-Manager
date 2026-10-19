package ai.victorious.victorfiles.core.priv

/** How a privileged operation will be attempted. Prefer root, then Shizuku, then normal. */
enum class PrivilegeBackend { ROOT, SHIZUKU, NORMAL }

data class PrivilegeAvailability(
    val rootAvailable: Boolean,
    val rootEnabled: Boolean,
    val shizukuAvailable: Boolean,
    val shizukuPermission: Boolean,
    val shizukuEnabled: Boolean,
)

object PrivilegeSelector {
    /**
     * Chooses the backend for a privileged file/shell operation.
     * Order: root (if enabled & available) > Shizuku (if enabled, available, permitted) > NORMAL.
     */
    fun select(a: PrivilegeAvailability): PrivilegeBackend = when {
        a.rootEnabled && a.rootAvailable -> PrivilegeBackend.ROOT
        a.shizukuEnabled && a.shizukuAvailable && a.shizukuPermission -> PrivilegeBackend.SHIZUKU
        else -> PrivilegeBackend.NORMAL
    }

    fun needsElevation(path: String): Boolean {
        val p = path.replace('\\', '/')
        return p.startsWith("/data") ||
            p.startsWith("/system") ||
            p.startsWith("/vendor") ||
            p.startsWith("/product") ||
            p.contains("/Android/data") ||
            p.contains("/Android/obb") ||
            p.startsWith("/apex")
    }

    /** Human-readable reason when elevated access was requested but unavailable. */
    fun missingReason(a: PrivilegeAvailability): String? {
        if (select(a) != PrivilegeBackend.NORMAL) return null
        return when {
            a.rootEnabled && !a.rootAvailable ->
                "Root is enabled but no su binary answered. Install/grant Magisk (or similar) or turn Root off."
            a.shizukuEnabled && !a.shizukuAvailable ->
                "Shizuku is enabled but not running. Start Shizuku, then retry."
            a.shizukuEnabled && a.shizukuAvailable && !a.shizukuPermission ->
                "Shizuku is running but this app has no permission. Grant it in Settings."
            !a.rootEnabled && !a.shizukuEnabled ->
                "This path needs elevated access. Enable Root or Shizuku in Settings."
            else -> "Elevated access unavailable."
        }
    }
}
