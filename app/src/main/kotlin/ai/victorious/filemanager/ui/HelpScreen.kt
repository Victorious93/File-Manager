package ai.victorious.filemanager.ui

import ai.victorious.filemanager.core.help.ManualDoc
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val MANUAL_ASSET = "help/MANUAL.md"

/**
 * In-app manual: renders docs/MANUAL.md (bundled as assets/help/MANUAL.md at build time) with a
 * table of contents and full-text search. Works offline; links open in the browser.
 */
@Composable
internal fun HelpScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val blocks by produceState<List<ManualDoc.Block>?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.assets.open(MANUAL_ASSET).use { it.readBytes().decodeToString() } }
                .map(ManualDoc::parse)
                .getOrElse { listOf(ManualDoc.Block.Paragraph("The manual could not be loaded: ${it.message}")) }
        }
    }
    var query by remember { mutableStateOf("") }
    var showToc by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBg = MaterialTheme.colorScheme.surfaceVariant

    fun openLink(url: String) {
        if (url.startsWith("#")) {
            val target = blocks?.indexOfFirst { it is ManualDoc.Block.Heading && it.anchor == url.drop(1) } ?: -1
            if (target >= 0) scope.launch { query = ""; listState.scrollToItem(target) }
        } else if (url.startsWith("http://") || url.startsWith("https://")) {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }
    }

    ToolScaffold("Help & manual", onBack = onClose, actions = {
        IconButton(onClick = { showToc = !showToc }) {
            Icon(Icons.AutoMirrored.Outlined.List, contentDescription = "Contents")
        }
    }) { modifier ->
        Column(modifier) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; showToc = false },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, contentDescription = "Clear search") }
                },
                placeholder = { Text("Search the manual") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            )
            val all = blocks
            when {
                all == null -> Text("Loading…", Modifier.padding(16.dp))
                query.isNotBlank() -> {
                    val hits = remember(all, query) { ManualDoc.search(all, query) }
                    LazyColumn(Modifier.fillMaxWidth()) {
                        item {
                            Text(
                                if (hits.isEmpty()) "No matches for “$query”" else "${hits.size} matches",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                        itemsIndexed(hits) { _, hit ->
                            Column(
                                Modifier.fillMaxWidth().clickable {
                                    query = ""
                                    scope.launch { listState.scrollToItem(hit.blockIndex) }
                                }.padding(horizontal = 16.dp, vertical = 8.dp),
                            ) {
                                Text(hit.section.ifBlank { "Manual" }, style = MaterialTheme.typography.titleSmall)
                                Text(highlight(hit.snippet, query, linkColor), style = MaterialTheme.typography.bodySmall)
                            }
                            HorizontalDivider()
                        }
                    }
                }
                showToc -> LazyColumn(Modifier.fillMaxWidth()) {
                    itemsIndexed(ManualDoc.toc(all)) { _, (index, h) ->
                        Text(
                            h.text,
                            style = if (h.level <= 2) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth().clickable {
                                showToc = false
                                scope.launch { listState.scrollToItem(index) }
                            }.padding(start = (16 + (h.level - 2) * 16).dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
                        )
                    }
                }
                else -> LazyColumn(Modifier.fillMaxWidth(), state = listState) {
                    itemsIndexed(all) { _, block ->
                        BlockView(block, linkColor, codeBg, ::openLink)
                    }
                }
            }
        }
    }
}

@Composable
private fun BlockView(
    block: ManualDoc.Block,
    linkColor: androidx.compose.ui.graphics.Color,
    codeBg: androidx.compose.ui.graphics.Color,
    onLink: (String) -> Unit,
) {
    val pad = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    when (block) {
        is ManualDoc.Block.Heading -> Text(
            block.text,
            style = when (block.level) {
                1 -> MaterialTheme.typography.headlineSmall
                2 -> MaterialTheme.typography.titleLarge
                3 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            },
            modifier = Modifier.semantics { heading() }.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        )
        is ManualDoc.Block.Paragraph -> InlineText(block.text, linkColor, codeBg, onLink, pad)
        is ManualDoc.Block.ListItem -> Row(Modifier.padding(start = (16 + block.depth * 16).dp, end = 16.dp, top = 2.dp, bottom = 2.dp)) {
            Text(block.marker + " ", style = MaterialTheme.typography.bodyMedium)
            InlineText(block.text, linkColor, codeBg, onLink, Modifier)
        }
        is ManualDoc.Block.Code -> Text(
            block.text,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = pad.fillMaxWidth().background(codeBg, RoundedCornerShape(6.dp))
                .horizontalScroll(rememberScrollState()).padding(8.dp),
        )
        is ManualDoc.Block.Quote -> Row(pad) {
            Text("▌", color = linkColor)
            InlineText(block.text, linkColor, codeBg, onLink, Modifier.padding(start = 4.dp))
        }
        ManualDoc.Block.Rule -> HorizontalDivider(Modifier.padding(vertical = 8.dp))
    }
}

@Composable
private fun InlineText(
    text: String,
    linkColor: androidx.compose.ui.graphics.Color,
    codeBg: androidx.compose.ui.graphics.Color,
    onLink: (String) -> Unit,
    modifier: Modifier,
) {
    val spans = remember(text) { ManualDoc.inline(text) }
    val links = spans.mapNotNull { it.url }
    val annotated = remember(text, linkColor, codeBg) {
        buildAnnotatedString {
            spans.forEach { s ->
                val style = when (s.style) {
                    ManualDoc.Style.PLAIN -> SpanStyle()
                    ManualDoc.Style.BOLD -> SpanStyle(fontWeight = FontWeight.SemiBold)
                    ManualDoc.Style.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                    ManualDoc.Style.CODE -> SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)
                    ManualDoc.Style.LINK -> SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
                }
                withStyle(style) { append(s.text) }
            }
        }
    }
    Column(modifier) {
        Text(annotated, style = MaterialTheme.typography.bodyMedium)
        // Links as explicit tap targets (accessible, and avoids fragile offset hit-testing).
        if (links.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                spans.filter { it.url != null }.forEach { s ->
                    Text(
                        "↗ ${s.text}",
                        color = linkColor,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.clickable { onLink(s.url!!) }.padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
}

private fun highlight(snippet: String, query: String, color: androidx.compose.ui.graphics.Color): AnnotatedString {
    val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
    return buildAnnotatedString {
        append(snippet)
        val lower = snippet.lowercase()
        for (w in words) {
            var i = lower.indexOf(w)
            while (i >= 0) {
                addStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold), i, i + w.length)
                i = lower.indexOf(w, i + w.length)
            }
        }
    }
}
