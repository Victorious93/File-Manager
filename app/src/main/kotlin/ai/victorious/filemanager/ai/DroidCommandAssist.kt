package ai.victorious.filemanager.ai

import ai.victorious.filemanager.core.ai.CodeAssistContract
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

/** Whether Droidcommand AI is installed and whether it handles the code-assist action. */
enum class AssistAvailability { READY, NEEDS_UPDATE, NOT_INSTALLED }

object DroidCommandAssist {
    fun availability(ctx: Context): AssistAvailability {
        val pm = ctx.packageManager
        val installed = runCatching { pm.getPackageInfo(CodeAssistContract.PROVIDER_PACKAGE, 0); true }
            .getOrElse { it !is PackageManager.NameNotFoundException && false }
        if (!installed) return AssistAvailability.NOT_INSTALLED
        val handles = pm.queryIntentActivities(baseIntent(), 0).isNotEmpty()
        return if (handles) AssistAvailability.READY else AssistAvailability.NEEDS_UPDATE
    }

    fun baseIntent(): Intent = Intent(CodeAssistContract.ACTION).setPackage(CodeAssistContract.PROVIDER_PACKAGE)

    /** Opens the Droidcommand AI releases page (it is sideloaded, not on Play). */
    fun installIntent(): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Victorious93/droidcommand-AI/releases"))
}

/** Sends the request JSON to Droidcommand AI and returns its result JSON (or null if cancelled). */
class DroidCommandAssistContract : ActivityResultContract<String, String?>() {
    override fun createIntent(context: Context, input: String): Intent =
        DroidCommandAssist.baseIntent().putExtra(CodeAssistContract.EXTRA_REQUEST, input)

    override fun parseResult(resultCode: Int, intent: Intent?): String? =
        if (resultCode == Activity.RESULT_OK) intent?.getStringExtra(CodeAssistContract.EXTRA_RESULT) else null
}
