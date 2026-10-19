package ai.victorious.victorfiles.ui

import ai.victorious.victorfiles.core.syntax.FindOptions
import ai.victorious.victorfiles.core.syntax.FindReplace
import ai.victorious.victorfiles.core.syntax.Language
import ai.victorious.victorfiles.core.syntax.SyntaxHighlighter
import ai.victorious.victorfiles.core.syntax.TokenType
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/** Skip highlighting very large files to keep typing responsive. */
private const val HIGHLIGHT_LIMIT = 300_000

fun styleFor(type: TokenType, dark: Boolean): SpanStyle = when (type) {
    TokenType.KEYWORD -> SpanStyle(color = if (dark) Color(0xFFCC7832) else Color(0xFF0033B3), fontWeight = FontWeight.SemiBold)
    TokenType.STRING -> SpanStyle(color = if (dark) Color(0xFF6A8759) else Color(0xFF067D17))
    TokenType.COMMENT -> SpanStyle(color = Color(0xFF808080), fontStyle = FontStyle.Italic)
    TokenType.NUMBER -> SpanStyle(color = if (dark) Color(0xFF6897BB) else Color(0xFF1750EB))
    TokenType.TAG -> SpanStyle(color = if (dark) Color(0xFFE8BF6A) else Color(0xFF0033B3))
    TokenType.ATTRIBUTE -> SpanStyle(color = if (dark) Color(0xFFBABABA) else Color(0xFF871094))
    TokenType.ANNOTATION -> SpanStyle(color = if (dark) Color(0xFFBBB529) else Color(0xFF9E880D))
    TokenType.HEADING -> SpanStyle(color = if (dark) Color(0xFFCC7832) else Color(0xFF0033B3), fontWeight = FontWeight.Bold)
    TokenType.PUNCTUATION -> SpanStyle()
}

/** Identity-offset transformation adding syntax colors plus find-match backgrounds. */
class HighlightTransformation(
    private val language: Language,
    private val dark: Boolean,
    private val matches: List<IntRange>,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val s = text.text
        val out = buildAnnotatedString {
            append(s)
            if (s.length <= HIGHLIGHT_LIMIT && language != Language.PLAIN) {
                for (t in SyntaxHighlighter.tokenize(s, language)) addStyle(styleFor(t.type, dark), t.start, t.end)
            }
            val bg = SpanStyle(background = Color(0x66FFD54F))
            for (m in matches) if (m.last < s.length) addStyle(bg, m.first, m.last + 1)
        }
        return TransformedText(out, OffsetMapping.Identity)
    }
}

data class FindState(
    val query: String = "",
    val replacement: String = "",
    val options: FindOptions = FindOptions(),
)

/** Matches for [state] in [text]; returns (ranges, error message). */
fun findMatches(text: String, state: FindState): Pair<List<IntRange>, String?> =
    try { FindReplace.findAll(text, state.query, state.options) to null } catch (e: IllegalArgumentException) { emptyList<IntRange>() to "Invalid regex" }
    catch (e: java.util.regex.PatternSyntaxException) { emptyList<IntRange>() to "Invalid regex" }

@Composable
fun FindReplaceBar(
    state: FindState,
    matchCount: Int,
    error: String?,
    onChange: (FindState) -> Unit,
    onReplaceNext: () -> Unit,
    onReplaceAll: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.query, onValueChange = { onChange(state.copy(query = it)) },
                label = { Text("Find") }, singleLine = true, modifier = Modifier.weight(1f),
            )
            Text(
                error ?: "$matchCount match${if (matchCount == 1) "" else "es"}",
                style = MaterialTheme.typography.labelSmall,
                color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        OutlinedTextField(
            value = state.replacement, onValueChange = { onChange(state.copy(replacement = it)) },
            label = { Text("Replace with (\$1 for groups in regex mode)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val o = state.options
            FilterChip(o.regex, { onChange(state.copy(options = o.copy(regex = !o.regex))) }, label = { Text(".* Regex") })
            FilterChip(o.ignoreCase, { onChange(state.copy(options = o.copy(ignoreCase = !o.ignoreCase))) }, label = { Text("Aa Ignore case") })
            FilterChip(o.wholeWord, { onChange(state.copy(options = o.copy(wholeWord = !o.wholeWord))) }, label = { Text("Whole word") })
            TextButton(onClick = onReplaceNext, enabled = matchCount > 0) { Text("Replace") }
            TextButton(onClick = onReplaceAll, enabled = matchCount > 0) { Text("Replace all") }
        }
    }
}
