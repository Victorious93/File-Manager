package ai.victorious.victorfiles

import ai.victorious.victorfiles.ui.FileManagerApp
import ai.victorious.victorfiles.ui.FileManagerViewModel
import ai.victorious.victorfiles.ui.theme.VictoriousTheme
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
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Listing happens in onResume (always follows onCreate), off the main thread.
        viewModel.refreshPermissions()
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
}
