package ai.victorious.filemanager.ui

import ai.victorious.filemanager.apk.ApkInfo
import ai.victorious.filemanager.apk.ApkInstaller
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.io.File

fun isApk(path: String) = path.endsWith(".apk", ignoreCase = true)

/** Runs a context-bound APK action and reports failures to the snackbar. */
fun apkAction(context: Context, onEvent: (UiEvent) -> Unit, block: () -> String?) {
    runCatching(block).fold(
        onSuccess = { msg -> if (msg != null) onEvent(UiEvent.ShowMessage(msg)) },
        onFailure = { onEvent(UiEvent.ShowMessage(it.message ?: "Action failed")) },
    )
}

/** NP Manager–style long-press items for .apk files. */
@Composable
fun ApkMenuItems(path: String, onEvent: (UiEvent) -> Unit, dismiss: () -> Unit) {
    val context = LocalContext.current
    HorizontalDivider()
    DropdownMenuItem(text = { Text("APK info") }, onClick = { dismiss(); onEvent(UiEvent.ShowApkInfo(path)) })
    DropdownMenuItem(text = { Text("Install APK") }, onClick = {
        dismiss(); apkAction(context, onEvent) { ApkInstaller.install(context, listOf(File(path))) }
    })
    DropdownMenuItem(text = { Text("Extract APK") }, onClick = { dismiss(); onEvent(UiEvent.ExtractApk(path)) })
    DropdownMenuItem(text = { Text("Sign APK…") }, onClick = { dismiss(); onEvent(UiEvent.RequestSign(path)) })
    DropdownMenuItem(text = { Text("Uninstall app…") }, onClick = { dismiss(); onEvent(UiEvent.ShowApkInfo(path)) })
}

/** Long-press items for any file. */
@Composable
fun ShareMenuItem(path: String, onEvent: (UiEvent) -> Unit, dismiss: () -> Unit) {
    val context = LocalContext.current
    DropdownMenuItem(text = { Text("Share / export") }, onClick = {
        dismiss(); apkAction(context, onEvent) { ApkInstaller.share(context, listOf(File(path))); null }
    })
}

/** Long-press items for folders: rebuild an extracted APK folder. */
@Composable
fun FolderMenuItems(path: String, onEvent: (UiEvent) -> Unit, dismiss: () -> Unit) {
    val hasManifest = remember(path) { File(path, "AndroidManifest.xml").isFile }
    if (hasManifest) {
        DropdownMenuItem(text = { Text("Rebuild APK from folder") }, onClick = { dismiss(); onEvent(UiEvent.RebuildApk(path)) })
    } else {
        DropdownMenuItem(text = { Text("Rebuild APK (needs AndroidManifest.xml)") }, onClick = {}, enabled = false)
    }
}

@Composable
fun ApkInfoDialog(info: ApkInfo, onEvent: (UiEvent) -> Unit) {
    val context = LocalContext.current
    val dismiss = { onEvent(UiEvent.DismissApkInfo) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(info.label ?: File(info.path).name) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                InfoLine("Package", info.packageName ?: "unknown")
                InfoLine("Version", "${info.versionName ?: "?"} (${info.versionCode ?: "?"})")
                InfoLine("Min SDK", info.minSdk?.toString() ?: "?")
                InfoLine("Target SDK", info.targetSdk?.toString() ?: "?")
                InfoLine("Installed", info.installedVersion ?: "not installed / not visible")
                InfoLine("Signature", info.signature)
                InfoLine("Size", "%.2f MB".format(info.sizeBytes / 1_048_576.0))
                Text("Permissions (${info.permissions.size})", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                info.permissions.forEach {
                    Text(it.removePrefix("android.permission."), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { apkAction(context, onEvent) { ApkInstaller.install(context, listOf(File(info.path))) } }) { Text("Install") }
        },
        dismissButton = {
            Row {
                info.packageName?.let { pkg ->
                    TextButton(onClick = { apkAction(context, onEvent) { ApkInstaller.uninstall(context, pkg); null } }) { Text("Uninstall") }
                }
                TextButton(onClick = { apkAction(context, onEvent) { ApkInstaller.share(context, listOf(File(info.path))); null } }) { Text("Share") }
                TextButton(onClick = dismiss) { Text("Close") }
            }
        },
    )
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text("$label: ", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun SignDialog(path: String, onEvent: (UiEvent) -> Unit) {
    var useDebug by remember { mutableStateOf(true) }
    var keystore by remember { mutableStateOf("") }
    var storePass by remember { mutableStateOf("") }
    var alias by remember { mutableStateOf("") }
    var keyPass by remember { mutableStateOf("") }
    var v1 by remember { mutableStateOf(true) }
    var v2 by remember { mutableStateOf(true) }
    var v3 by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = { onEvent(UiEvent.DismissSign) },
        title = { Text("Sign ${File(path).name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = useDebug, onClick = { useDebug = true })
                    Text("Debug key (generated once on this device)")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = !useDebug, onClick = { useDebug = false })
                    Text("My keystore (.p12 / .jks)")
                }
                if (!useDebug) {
                    OutlinedTextField(keystore, { keystore = it }, label = { Text("Keystore path") }, singleLine = true)
                    OutlinedTextField(storePass, { storePass = it }, label = { Text("Store password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    OutlinedTextField(alias, { alias = it }, label = { Text("Key alias (blank = first)") }, singleLine = true)
                    OutlinedTextField(keyPass, { keyPass = it }, label = { Text("Key password (blank = store)") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(v1, { v1 = it }); Text("V1")
                    Checkbox(v2, { v2 = it }); Text("V2")
                    Checkbox(v3, { v3 = it }); Text("V3")
                }
                Text(
                    "Output is written next to the APK as *_signed.apk. Old v1 signature files are replaced.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = (v1 || v2 || v3) && (useDebug || keystore.isNotBlank()),
                onClick = { onEvent(UiEvent.ConfirmSign(path, SignOptions(useDebug, keystore, storePass, alias, keyPass, v1, v2, v3))) },
            ) { Text("Sign") }
        },
        dismissButton = { TextButton(onClick = { onEvent(UiEvent.DismissSign) }) { Text("Cancel") } },
    )
}

/** Batch helpers for the bottom bar. */
fun selectedApks(state: UiState): List<File> {
    val pane = if (state.focusedPane == 0) state.left else state.right
    return pane.selected.filter(::isApk).map(::File)
}

fun selectedFiles(state: UiState): List<File> {
    val pane = if (state.focusedPane == 0) state.left else state.right
    return pane.selected.map(::File).filter { it.isFile }
}

