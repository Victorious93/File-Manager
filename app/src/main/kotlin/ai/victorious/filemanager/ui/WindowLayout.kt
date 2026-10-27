package ai.victorious.filemanager.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker

/** Material window size classes by width. */
enum class WidthClass { COMPACT, MEDIUM, EXPANDED }

const val MEDIUM_WINDOW_DP = 600
const val EXPANDED_WINDOW_DP = 840

/** Phones in landscape are wide but short; they stay MEDIUM so the panes are not split automatically. */
const val EXPANDED_MIN_HEIGHT_DP = 480

fun widthClassFor(widthDp: Int, heightDp: Int = Int.MAX_VALUE): WidthClass = when {
    widthDp < MEDIUM_WINDOW_DP -> WidthClass.COMPACT
    widthDp < EXPANDED_WINDOW_DP || heightDp < EXPANDED_MIN_HEIGHT_DP -> WidthClass.MEDIUM
    else -> WidthClass.EXPANDED
}

/**
 * A vertical fold or hinge the panes should split around: [leftFraction] of the content width goes to
 * the left pane, and [gap] is the width the hinge itself occludes (0 for a seamless fold).
 */
@Immutable
data class HingeSplit(val leftFraction: Float, val gap: Dp)

/**
 * Where to split side-by-side panes for a vertical fold at [foldLeftPx]..[foldRightPx] in a window
 * [windowWidthPx] wide. Null when the fold is not usable (missing, or so far off-centre a pane would be tiny).
 */
fun hingeSplitFor(foldLeftPx: Int, foldRightPx: Int, windowWidthPx: Int, density: Float): HingeSplit? {
    if (windowWidthPx <= 0 || foldRightPx < foldLeftPx) return null
    val fraction = foldLeftPx.toFloat() / windowWidthPx
    if (fraction !in 0.25f..0.75f) return null
    return HingeSplit(fraction, ((foldRightPx - foldLeftPx) / density).dp)
}

@Immutable
data class WindowLayout(
    val widthClass: WidthClass,
    /** A vertical fold the window spans, or null on flat screens and in tabletop posture. */
    val hinge: HingeSplit?,
    /** Half-open (tabletop/book) posture: a fold is present and the device is not flat. */
    val halfOpened: Boolean,
) {
    val expanded: Boolean get() = widthClass == WidthClass.EXPANDED || hinge != null
    val useRail: Boolean get() = widthClass != WidthClass.COMPACT
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** The current window's size class plus any hinge from Jetpack WindowManager, updating live as a foldable opens. */
@Composable
fun rememberWindowLayout(): WindowLayout {
    val config = LocalConfiguration.current
    val context = LocalContext.current
    val density = LocalDensity.current.density
    var fold by remember { mutableStateOf<FoldingFeature?>(null) }
    val activity = remember(context) { context.findActivity() }
    LaunchedEffect(activity) {
        if (activity == null) return@LaunchedEffect
        runCatching {
            WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).collect { info ->
                fold = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
            }
        }
    }
    val widthClass = widthClassFor(config.screenWidthDp, config.screenHeightDp)
    val f = fold
    val windowPx = (config.screenWidthDp * density).toInt()
    val hinge = if (f != null && f.orientation == FoldingFeature.Orientation.VERTICAL &&
        (f.isSeparating || f.state == FoldingFeature.State.HALF_OPENED)
    ) {
        hingeSplitFor(f.bounds.left, f.bounds.right, windowPx, density)
    } else {
        null
    }
    return WindowLayout(widthClass, hinge, halfOpened = f?.state == FoldingFeature.State.HALF_OPENED)
}
