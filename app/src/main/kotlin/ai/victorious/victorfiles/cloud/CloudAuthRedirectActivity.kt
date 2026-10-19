package ai.victorious.victorfiles.cloud

import ai.victorious.victorfiles.FileManagerApplication
import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Captures the OAuth custom-scheme redirect, finishes the token exchange, then returns to the app. */
class CloudAuthRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent?.data
        val repo = (application as FileManagerApplication).connections
        if (data == null) {
            finish(); return
        }
        CoroutineScope(Dispatchers.Main).launch {
            val msg = withContext(Dispatchers.IO) { CloudOAuth.complete(data, repo) }
            Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
            finish()
        }
    }
}
