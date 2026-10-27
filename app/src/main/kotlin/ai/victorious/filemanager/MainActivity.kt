package ai.victorious.filemanager

import ai.victorious.filemanager.ui.FileManagerApp
import ai.victorious.filemanager.ui.FileManagerViewModel
import ai.victorious.filemanager.ui.UiEvent
import ai.victorious.filemanager.ui.Dest
import ai.victorious.filemanager.ui.defaultMode
import java.io.File
import ai.victorious.filemanager.ui.theme.VictoriousTheme
import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : FragmentActivity() {
    private val viewModel: FileManagerViewModel by viewModels {
        FileManagerViewModel.factory(application as FileManagerApplication)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Leave the logo launch window (manifest theme) for the normal theme before the window is built.
        setTheme(R.style.Theme_VictoriousFileManager)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Listing happens in onResume (always follows onCreate), off the main thread.
        viewModel.refreshPermissions()
        if (savedInstanceState == null && (application as FileManagerApplication).themeSettings.state.value.startOnHome) {
            viewModel.onEvent(ai.victorious.filemanager.ui.UiEvent.Navigate(ai.victorious.filemanager.ui.Dest.HOME))
        }
        setContent {
            val themePrefs by (application as FileManagerApplication).themeSettings.state.collectAsStateWithLifecycle()
            VictoriousTheme(themePrefs) {
                val state by viewModel.ui.collectAsStateWithLifecycle()
                FileManagerApp(
                    state = state,
                    onEvent = viewModel::onEvent,
                    onRefreshPermissions = viewModel::refreshPermissions,
                )
            }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** Routes Quick Settings tile launches (no secrets, no file side effects here). */
    private fun handleIntent(intent: Intent?) {
        when (intent?.getStringExtra(EXTRA_OPEN)) {
            OPEN_WIFI_TRANSFER -> viewModel.onEvent(UiEvent.OpenWifiTransfer)
        }
        intent?.getStringExtra(EXTRA_OPEN_PATH)?.let { path ->
            viewModel.onEvent(UiEvent.Navigate(Dest.FILES))
            viewModel.onEvent(UiEvent.Open(0, path))
        }
        // Open-with: external ACTION_VIEW on a file:// URI opens the matching built-in viewer.
        if (intent?.action == Intent.ACTION_VIEW) {
            val path = intent.data?.takeIf { it.scheme == "file" }?.path
            if (path != null && File(path).isFile) {
                viewModel.onEvent(UiEvent.OpenFile(path, defaultMode(File(path))))
            }
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
        // Auto-lock: the secure folder re-locks once backgrounded past its timeout.
        (application as FileManagerApplication).secureFolder.lock.onBackground()
    }

    companion object {
        const val EXTRA_OPEN = "ai.victorious.filemanager.extra.OPEN"
        const val EXTRA_OPEN_PATH = "ai.victorious.filemanager.extra.OPEN_PATH"
        const val OPEN_WIFI_TRANSFER = "wifi_transfer"
    }
}
