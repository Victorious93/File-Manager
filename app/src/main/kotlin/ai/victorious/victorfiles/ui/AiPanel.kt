package ai.victorious.victorfiles.ui

import ai.victorious.victorfiles.FileManagerApplication
import ai.victorious.victorfiles.core.ai.AiClient
import ai.victorious.victorfiles.core.ai.AiMode
import ai.victorious.victorfiles.core.ai.AiResult
import ai.victorious.victorfiles.core.ai.PromptBuilder
import ai.victorious.victorfiles.core.ai.SecretRedactor
import ai.victorious.victorfiles.core.diff.DiffOp
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Explain / Suggest edit panel for the code editor. Nothing is sent until the user confirms
 * the destination host and size; suggested edits are shown as a diff and only applied on Accept.
 */
@Composable
fun AiPanel(
    fileName: String,
    language: String,
    fullText: String,
    selection: IntRange?,
    onApply: (String) -> Unit,
    onClose: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as FileManagerApplication
    val settings = app.aiSettings
    val scope = rememberCoroutineScope()
    val snippet = remember(fullText, selection) {
        if (selection == null || selection.isEmpty()) fullText
        else fullText.substring(selection.first.coerceIn(0, fullText.length), (selection.last + 1).coerceIn(0, fullText.length))
    }
    var instruction by remember { mutableStateOf("") }
    var pendingMode by remember { mutableStateOf<AiMode?>(null) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<AiResult?>(null) }
    val endpoint = remember { runCatching { settings.endpoint() } }

    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp).heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("AI assistant", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("Close") }
            }
            val ep = endpoint.getOrNull()
            when {
                endpoint.isFailure -> Text("AI settings invalid: ${endpoint.exceptionOrNull()?.message}", color = MaterialTheme.colorScheme.error)
                ep == null -> Text("Turn on and configure the AI assistant in Settings first (base URL, model, API key).")
                !settings.hasApiKey -> Text("Add an API key in Settings → AI assistant.")
                else -> {
                    Text(
                        (if (selection == null || selection.isEmpty()) "Whole file" else "Selection") +
                            " · ${snippet.length} chars → ${ep.host} (${ep.model})",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        instruction, { instruction = it },
                        label = { Text("Optional instruction (for Suggest edit)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !busy && snippet.isNotBlank(), onClick = { pendingMode = AiMode.EXPLAIN }) { Text("Explain") }
                        OutlinedButton(enabled = !busy && snippet.isNotBlank(), onClick = { pendingMode = AiMode.SUGGEST_EDIT }) { Text("Suggest edit") }
                        if (busy) CircularProgressIndicator(Modifier.padding(start = 8.dp))
                    }
                }
            }
            when (val r = result) {
                null -> Unit
                is AiResult.Failure -> Text(r.message, color = MaterialTheme.colorScheme.error)
                is AiResult.Explanation -> SelectionContainer {
                    Text(r.text, modifier = Modifier.verticalScroll(rememberScrollState()))
                }
                is AiResult.Edit -> Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (r.notes.isNotBlank()) Text(r.notes, style = MaterialTheme.typography.bodySmall)
                    Column(Modifier.horizontalScroll(rememberScrollState())) {
                        for (line in r.diff) {
                            val (prefix, bg) = when (line.op) {
                                DiffOp.INSERT -> "+ " to Color(0x3326A69A)
                                DiffOp.DELETE -> "- " to Color(0x33E57373)
                                DiffOp.EQUAL -> "  " to Color.Transparent
                            }
                            Text(prefix + line.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.background(bg))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        Button(onClick = { onApply(r.replacement); result = null }) { Text("Accept") }
                        OutlinedButton(onClick = { result = null }) { Text("Reject") }
                    }
                }
            }
        }
    }

    val mode = pendingMode
    val ep = endpoint.getOrNull()
    if (mode != null && ep != null) {
        val prompt = remember(mode, snippet, instruction) { PromptBuilder.build(mode, fileName, language, snippet, instruction) }
        AlertDialog(
            onDismissRequest = { pendingMode = null },
            title = { Text("Send code to ${ep.host}?") },
            text = {
                Text(
                    "${snippet.length} characters will be sent to ${ep.chatCompletionsUrl} (model ${ep.model})." +
                        (if (prompt.redactions > 0) " ${prompt.redactions} likely secret(s) will be masked first." else "") +
                        (if (prompt.truncated) " The snippet will be truncated to ${PromptBuilder.MAX_CODE_CHARS} chars." else "") +
                        " The provider's own data policy applies.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingMode = null
                    busy = true
                    result = null
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            runCatching { AiClient().complete(ep, settings.apiKey(), mode, snippet, prompt) }
                                .getOrElse { AiResult.Failure(SecretRedactor.scrub(it.message ?: "Request failed", null)) }
                        }
                        busy = false
                        result = r
                    }
                }) { Text("Send") }
            },
            dismissButton = { TextButton(onClick = { pendingMode = null }) { Text("Cancel") } },
        )
    }
}
