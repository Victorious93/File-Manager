package ai.victorious.filemanager.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Immutable
data class Crumb(val label: String, val path: String)

/**
 * Splits [path] into tappable crumbs. Paths under [storageRoot] start with a "Storage" crumb instead of
 * the /storage/emulated/0 segments nobody wants to see. Non-file locations (smb://, sftp://, content://,
 * archive views) collapse into a single crumb.
 */
fun breadcrumbs(path: String, storageRoot: String = "/storage/emulated/0"): List<Crumb> {
    if (path.isEmpty()) return emptyList()
    if (!path.startsWith("/")) return listOf(Crumb(path.substringAfterLast('/').ifEmpty { path }, path))
    val root = storageRoot.trimEnd('/')
    val (base, rest) = when {
        path == root || path == "$root/" -> return listOf(Crumb("Storage", root))
        path.startsWith("$root/") -> Crumb("Storage", root) to path.removePrefix("$root/")
        path == "/" -> return listOf(Crumb("/", "/"))
        else -> Crumb("/", "/") to path.removePrefix("/")
    }
    val out = mutableListOf(base)
    var acc = if (base.path == "/") "" else base.path
    for (seg in rest.split('/').filter { it.isNotEmpty() }) {
        acc = "$acc/$seg"
        out += Crumb(seg, acc)
    }
    return out
}

/** Horizontally scrolling path bar; scrolls to the end so the current folder is always in view. */
@Composable
fun BreadcrumbBar(path: String, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val crumbs = remember(path) { breadcrumbs(path) }
    val scroll = rememberScrollState()
    LaunchedEffect(path) { scroll.animateScrollTo(scroll.maxValue) }
    Row(
        modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 8.dp)
            .testTag("breadcrumbs").semantics { contentDescription = "Path $path" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        crumbs.forEachIndexed { i, c ->
            val last = i == crumbs.lastIndex
            if (i > 0) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val alpha = animateFloatAsState(if (last) 1f else 0.8f, label = "crumb").value
            AssistChip(
                onClick = { if (!last) onOpen(c.path) },
                label = {
                    Text(
                        c.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (last) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                    )
                },
                leadingIcon = if (i == 0 && c.label == "Storage") {
                    { Icon(Icons.Outlined.PhoneAndroid, null, Modifier.size(AssistChipDefaults.IconSize)) }
                } else {
                    null
                },
                border = if (last) AssistChipDefaults.assistChipBorder(true, borderColor = MaterialTheme.colorScheme.primary) else null,
            )
        }
    }
}
