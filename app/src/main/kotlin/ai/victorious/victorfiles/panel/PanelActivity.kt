package ai.victorious.victorfiles.panel

import ai.victorious.victorfiles.FileManagerApplication
import ai.victorious.victorfiles.MainActivity
import ai.victorious.victorfiles.ui.Dest
import ai.victorious.victorfiles.ui.FileManagerApp
import ai.victorious.victorfiles.ui.FileManagerViewModel
import ai.victorious.victorfiles.ui.UiEvent
import ai.victorious.victorfiles.ui.theme.VictoriousTheme
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.coerceAtMost
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt

/**
 * Right-edge sheet that hosts the full file manager. A host app (VictorSuite) launches it from its
 * swipe-out right drawer in place of its own file window. It is a separate APK, so it cannot be
 * placed inside the host's view tree; instead it is a translucent activity that slides in from the
 * right over the host and returns to it on back / scrim tap / drag-back.
 *
 * It exposes no more than [MainActivity] already does (same UI, same permission prompts) and grants
 * the caller no file access, so it is not caller-gated. The only extra honoured is
 * [MainActivity.EXTRA_OPEN_PATH]. See docs/VICTORSUITE_PANEL.md.
 */
class PanelActivity : FragmentActivity() {
    private val viewModel: FileManagerViewModel by viewModels {
        FileManagerViewModel.factory(application as FileManagerApplication)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        viewModel.refreshPermissions()
        setContent {
            val themePrefs by (application as FileManagerApplication).themeSettings.state.collectAsStateWithLifecycle()
            VictoriousTheme(themePrefs) {
                val state by viewModel.ui.collectAsStateWithLifecycle()
                EdgePanel(onClosed = { finish() }) {
                    FileManagerApp(
                        state = state,
                        onEvent = viewModel::onEvent,
                        onRefreshPermissions = viewModel::refreshPermissions,
                    )
                }
            }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.getStringExtra(MainActivity.EXTRA_OPEN_PATH)?.let { path ->
            viewModel.onEvent(UiEvent.Navigate(Dest.FILES))
            viewModel.onEvent(UiEvent.Open(0, path))
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshPermissions()
        viewModel.refreshAll()
    }

    override fun onStart() {
        super.onStart()
        (application as FileManagerApplication).secureFolder.lock.onForeground()
    }

    override fun onStop() {
        super.onStop()
        (application as FileManagerApplication).secureFolder.lock.onBackground()
    }

    override fun finish() {
        super.finish()
        // The sheet animates itself out before finish(); skip the window animation on top of it.
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        const val ACTION_PANEL = "ai.victorious.victorfiles.action.PANEL"
    }
}

private const val PANEL_WIDTH_FRACTION = 0.88f
private val PANEL_MIN_WIDTH = 320.dp
private val EDGE_HANDLE_WIDTH = 16.dp
private val DISMISS_DRAG_DISTANCE = 72.dp

/**
 * Scrim plus a sheet anchored to the right edge. [onClosed] runs after the exit animation, so every
 * dismissal path (back, scrim tap, drag-back) animates out before the activity finishes.
 */
@Composable
private fun EdgePanel(onClosed: () -> Unit, content: @Composable () -> Unit) {
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    val dismiss = { shown.targetState = false }
    LaunchedEffect(shown.isIdle, shown.currentState) {
        if (shown.isIdle && !shown.currentState) onClosed()
    }
    // Composed before the content, so the file manager's own back handling (folder up) wins first.
    BackHandler { dismiss() }

    var drag by remember { mutableFloatStateOf(0f) }
    val dismissDragPx = with(LocalDensity.current) { DISMISS_DRAG_DISTANCE.toPx() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val panelWidth = (maxWidth * PANEL_WIDTH_FRACTION).coerceAtLeast(PANEL_MIN_WIDTH).coerceAtMost(maxWidth)

        AnimatedVisibility(visibleState = shown, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { dismiss() },
            )
        }

        AnimatedVisibility(
            visibleState = shown,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
        ) {
            Surface(
                modifier = Modifier
                    .width(panelWidth)
                    .fillMaxHeight()
                    .offset { IntOffset(drag.roundToInt(), 0) },
                shadowElevation = 8.dp,
            ) {
                Box(Modifier.fillMaxSize()) {
                    content()
                    // Drag-back handle on the inner edge only, so the file manager's own horizontal
                    // gestures (tab strip, row swipes) are not intercepted.
                    Box(
                        Modifier
                            .align(Alignment.CenterStart)
                            .fillMaxHeight()
                            .width(EDGE_HANDLE_WIDTH)
                            .pointerInput(Unit) {
                                detectHorizontalDragGestures(
                                    onDragEnd = { if (drag > dismissDragPx) dismiss() else drag = 0f },
                                    onDragCancel = { drag = 0f },
                                ) { _, dx -> drag = (drag + dx).coerceAtLeast(0f) }
                            },
                    )
                }
            }
        }
    }
}
