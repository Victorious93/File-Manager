package ai.victorious.filemanager

import ai.victorious.filemanager.ui.FileManagerApp
import ai.victorious.filemanager.ui.FileManagerViewModel
import ai.victorious.filemanager.ui.theme.VictoriousTheme
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val viewModel: FileManagerViewModel by viewModels {
        FileManagerViewModel.factory(application as FileManagerApplication)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        viewModel.refreshPermissions()
        viewModel.refreshAll()
        setContent {
            VictoriousTheme {
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
}
