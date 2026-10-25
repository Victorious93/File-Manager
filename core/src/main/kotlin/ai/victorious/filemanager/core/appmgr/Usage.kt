package ai.victorious.filemanager.core.appmgr

/**
 * Usage-stats model. The app layer reads [android.app.usage.UsageStatsManager] (needs
 * PACKAGE_USAGE_STATS); this module aggregates and ranks so the UI and tests stay pure.
 */
data class AppUsage(
    val packageName: String,
    val totalForegroundMs: Long,
    val lastUsedEpochMs: Long,
    val launchCount: Int = 0,
)

enum class UsageSort { TIME, LAST_USED, LAUNCHES }

object UsageAggregator {
    fun merge(rows: List<AppUsage>): List<AppUsage> =
        rows.groupBy { it.packageName }.map { (pkg, list) ->
            AppUsage(
                pkg,
                list.sumOf { it.totalForegroundMs },
                list.maxOf { it.lastUsedEpochMs },
                list.sumOf { it.launchCount },
            )
        }

    fun sort(rows: List<AppUsage>, by: UsageSort): List<AppUsage> = when (by) {
        UsageSort.TIME -> rows.sortedByDescending { it.totalForegroundMs }
        UsageSort.LAST_USED -> rows.sortedByDescending { it.lastUsedEpochMs }
        UsageSort.LAUNCHES -> rows.sortedByDescending { it.launchCount }
    }

    fun formatDuration(ms: Long): String {
        if (ms < 0) return "0s"
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${sec}s"
            else -> "${sec}s"
        }
    }
}
