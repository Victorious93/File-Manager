package ai.victorious.filemanager.ui

import ai.victorious.filemanager.ai.AssistAvailability
import ai.victorious.filemanager.ai.DroidCommandAssist
import ai.victorious.filemanager.ai.DroidCommandAssistContract
import ai.victorious.filemanager.core.ai.AssistTask
import ai.victorious.filemanager.core.ai.CodeAssistContract
import ai.victorious.filemanager.core.ai.EditHunk
import ai.victorious.filemanager.core.ai.EditHunks
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * "Ask Droidcommand AI" dialog for the code / smali editor. VictorFiles holds no API key:
 * the request goes to Droidcommand AI over the CODE_ASSIST intent contract (docs/PHASE17.md).
 */
@Composable
fun AiAssistDialog(
    fileName: String,
    language: String,
    code: String,
    onApply: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val availability = remember { DroidCommandAssist.availability(ctx) }
    var instruction by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var explanation by remember { mutableStateOf<String?>(null) }
    var hunks by remember { mutableStateOf<List<EditHunk>>(emptyList()) }
    var accepted by remember { mutableStateOf<Set<Int>>(emptySet()) }
    val launcher = rememberLauncherForActivityResult(DroidCommandAssistContract()) { raw ->
        if (raw == null) { status = "Cancelled in Droidcommand AI"; return@rememberLauncherForActivityResult }
        val r = CodeAssistContract.decode(raw)
        if (!r.ok) { status = r.error; return@rememberLauncherForActivityResult }
        explanation = r.explanation
        val rev = r.revisedCode
        hunks = if (rev != null) EditHunks.hunks(code, rev) else emptyList()
        accepted = hunks.map { it.id }.toSet()
        status = if (rev != null && hunks.isEmpty()) "Suggested edit is identical to the current file" else null
    }
    fun send(task: AssistTask) {
        status = "Waiting for Droidcommand AI…"; explanation = null; hunks = emptyList()
        val req = CodeAssistContract.request(task, fileName, language, code, instruction)
        runCatching { launcher.launch(CodeAssistContract.encode(req)) }.onFailure { status = "Could not open Droidcommand AI: ${it.message}" }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Droidcommand AI") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (availability) {
                    AssistAvailability.NOT_INSTALLED -> {
                        Text("Install Droidcommand AI to explain code and smali and suggest edits. VictorFiles never stores an AI key itself.")
                        OutlinedButton(onClick = { runCatching { ctx.startActivity(DroidCommandAssist.installIntent()) } }) { Text("Get Droidcommand AI") }
                    }
                    AssistAvailability.NEEDS_UPDATE -> {
                        Text("Droidcommand AI is installed but does not support code assist yet. Update Droidcommand AI.")
                        OutlinedButton(onClick = { runCatching { ctx.startActivity(DroidCommandAssist.installIntent()) } }) { Text("Update Droidcommand AI") }
                    }
                    AssistAvailability.READY -> {
                        OutlinedTextField(instruction, { instruction = it }, label = { Text("Edit instruction (for Suggest edit)") }, modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { send(AssistTask.EXPLAIN) }) { Text("Explain") }
                            Button(onClick = { send(AssistTask.SUGGEST_EDIT) }) { Text("Suggest edit") }
                        }
                    }
                }
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                explanation?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (hunks.isNotEmpty()) {
                    Text("Suggested changes (${hunks.size}) — tick the ones to apply", style = MaterialTheme.typography.titleSmall)
                    for (h in hunks) {
                        Row(verticalAlignment = Alignment.Top) {
                            Checkbox(h.id in accepted, { on -> accepted = if (on) accepted + h.id else accepted - h.id })
                            Column(Modifier.fillMaxWidth()) {
                                Text("Line ${h.oldStart + 1}", style = MaterialTheme.typography.labelSmall)
                                for (l in h.removed) Text("- $l", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.fillMaxWidth().background(Color(0x33E53935)))
                                for (l in h.added) Text("+ $l", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.fillMaxWidth().background(Color(0x3343A047)))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (hunks.isNotEmpty()) TextButton(enabled = accepted.isNotEmpty(), onClick = {
                onApply(EditHunks.apply(code, hunks, accepted)); onDismiss()
            }) { Text("Apply ${accepted.size}") }
            else TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = { if (hunks.isNotEmpty()) TextButton(onClick = { hunks = emptyList(); status = "Suggestion rejected" }) { Text("Reject") } },
    )
}
