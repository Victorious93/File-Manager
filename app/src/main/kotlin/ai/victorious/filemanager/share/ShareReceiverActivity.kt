package ai.victorious.filemanager.share

import ai.victorious.filemanager.core.share.ShareIntake
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope

/**
 * "Share to File Manager": accepts ACTION_SEND / ACTION_SEND_MULTIPLE, asks the user to pick a
 * destination folder (Storage Access Framework tree), then copies each shared item into it with a
 * sanitised, collision-free name (the naming logic is the unit-tested [ShareIntake] in :core).
 * No UI of its own beyond the system folder picker and a result toast.
 */
class ShareReceiverActivity : ComponentActivity() {
    private var pending: List<Uri> = emptyList()

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree == null) { finish(); return@registerForActivityResult }
        runCatching {
            contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        copyInto(tree)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pending = extractUris(intent)
        if (pending.isEmpty()) {
            Toast.makeText(this, "Nothing to save", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        pickFolder.launch(null)
    }

    private fun extractUris(intent: Intent?): List<Uri> {
        intent ?: return emptyList()
        return when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.parcelableExtra<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.parcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else -> emptyList()
        }
    }

    private fun copyInto(treeUri: Uri) {
        val dir = DocumentFile.fromTreeUri(this, treeUri)
        if (dir == null || !dir.canWrite()) {
            Toast.makeText(this, "Cannot write to that folder", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                var ok = 0
                val taken = dir.listFiles().mapNotNull { it.name }.toMutableSet()
                for (uri in pending) {
                    val suggested = displayName(uri)
                    val name = ShareIntake.resolve(suggested, taken)
                    val mime = contentResolver.getType(uri) ?: "application/octet-stream"
                    val target = dir.createFile(mime, name) ?: continue
                    val wrote = runCatching {
                        contentResolver.openInputStream(uri)?.use { input ->
                            contentResolver.openOutputStream(target.uri)?.use { output -> input.copyTo(output) }
                        } != null
                    }.getOrDefault(false)
                    if (wrote) { taken.add(name); ok++ } else { target.delete() }
                }
                ok
            }
            Toast.makeText(this@ShareReceiverActivity, "Saved $saved of ${pending.size} item(s)", Toast.LENGTH_LONG).show()
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    private fun displayName(uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx) else null
                } else null
            }
        }.getOrNull() ?: uri.lastPathSegment
    }
}

@Suppress("DEPRECATION")
private inline fun <reified T : android.os.Parcelable> Intent.parcelableExtra(key: String): T? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getParcelableExtra(key, T::class.java)
    else getParcelableExtra(key) as? T

@Suppress("DEPRECATION")
private inline fun <reified T : android.os.Parcelable> Intent.parcelableArrayListExtra(key: String): ArrayList<T>? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getParcelableArrayListExtra(key, T::class.java)
    else getParcelableArrayListExtra(key)
