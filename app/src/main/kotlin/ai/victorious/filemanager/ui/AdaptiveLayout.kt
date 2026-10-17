package ai.victorious.filemanager.ui

/**
 * Adaptive-layout breakpoints (Material window size classes). Pure so they can be unit-tested.
 * Wide windows (tablets, unfolded foldables, landscape phones ≥ 600dp) get a NavigationRail
 * instead of the bottom bar; the two file panes go side by side when there is room for both.
 */
internal const val WIDE_WINDOW_DP = 600f
internal const val SIDE_BY_SIDE_MIN_PANE_AREA_DP = 520f

internal fun isWideWindow(widthDp: Float): Boolean = widthDp >= WIDE_WINDOW_DP

internal fun panesSideBySide(widthDp: Float, heightDp: Float): Boolean =
    widthDp >= SIDE_BY_SIDE_MIN_PANE_AREA_DP || (widthDp > heightDp && widthDp >= 400f)
