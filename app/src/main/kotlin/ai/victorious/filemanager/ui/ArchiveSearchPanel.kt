package ai.victorious.filemanager.ui

import ai.victorious.filemanager.core.archive.ArchivePasswordException
import ai.victorious.filemanager.core.archive.ArchiveQuery
import ai.victorious.filemanager.core.archive.ArchiveSearch
import ai.victorious.filemanager.core.archive.ArchiveSearchResult
import ai.victorious.filemanager.core.search.ContentSearch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Search inside one archive by entry name and/or text content, without extracting anything.
 * The search itself, with its size and time limits, is [ArchiveSearch]; this is only the screen.
 * Results are read-only. Password-protected archives are not searched (the screen says so).
 */
@Composable
fun ArchiveSearchPanel(archive: File, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var names by remember { mutableStateOf(true) }
    var content by remember { mutableStateOf(false) }
    var regex by remember { mutableStateOf(false) }
    var caseSensitive by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ArchiveSearchResult?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun start() {
        if (text.isBlank() || running || (!names && !content)) return
        running = true
        message = null
        result = null
        val query = ArchiveQuery(
            text = text,
            searchNames = names,
            searchContent = content,
            regex = regex,
            caseSensitive = caseSensitive,
        )
        job = scope.launch {
            try {
                result = withContext(Dispatchers.IO) {
                    val context = currentCoroutineContext()
                    ArchiveSearch.search(archive, query, isCancelled = { !context.isActive })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ArchivePasswordException) {
                message = "This archive is password protected, so it can't be searched. Extract it first."
            } catch (e: ContentSearch.InvalidQuery) {
                message = e.message
            } catch (e: Exception) {
                message = "Search failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                running = false
            }
        }
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("Search in ${archive.name}") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { start() }),
            )
            if (running) {
                IconButton(onClick = { job?.cancel(); message = "Search cancelled" }) {
                    Icon(Icons.Outlined.Close, contentDescription = "Cancel search")
                }
            } else {
                IconButton(onClick = { start() }, enabled = text.isNotBlank() && (names || content)) {
                    Icon(Icons.Outlined.Search, contentDescription = "Search")
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Option("Names", names) { names = it }
            Option("Contents", content) { content = it }
            Option("Regex", regex) { regex = it }
            Option("Aa", caseSensitive) { caseSensitive = it }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                summary(result, message, running),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
            )
            if (running) CircularProgressIndicator(Modifier.padding(end = 8.dp))
            TextButton(onClick = { job?.cancel(); onClose() }) { Text("Back to list") }
        }
        val hits = result?.hits.orEmpty()
        LazyColumn(Modifier.fillMaxSize()) {
            items(hits) { hit ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text(hit.entry, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (hit.line != null) {
                        Text(
                            "${hit.line}: ${hit.text.orEmpty().trim()}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Option(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

/** One status line: the message if any, else counts plus anything the search had to leave out. */
private fun summary(result: ArchiveSearchResult?, message: String?, running: Boolean): String {
    if (message != null) return message
    if (running) return "Searching…"
    if (result == null) return "Names are searched by default; turn on Contents to look inside text files."
    val parts = mutableListOf("${result.hits.size} match(es)")
    if (result.skippedEncrypted > 0) parts += "encrypted entries skipped: ${result.skippedEncrypted}"
    if (result.skippedTooLarge > 0) parts += "large entries skipped: ${result.skippedTooLarge}"
    if (result.truncated) parts += "stopped early, narrow the search"
    return parts.joinToString(" · ")
}
