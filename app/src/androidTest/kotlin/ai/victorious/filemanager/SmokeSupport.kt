package ai.victorious.filemanager

import ai.victorious.filemanager.ui.FileManagerViewModel
import ai.victorious.filemanager.ui.UiEvent
import android.os.Environment
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileInputStream

typealias MainRule = AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>

object Smoke {
    val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    val app: FileManagerApplication
        get() = instrumentation.targetContext.applicationContext as FileManagerApplication

    /** Runs a shell command as the `shell` user (appops, pm). Returns stdout. */
    fun shell(cmd: String): String {
        val pfd = instrumentation.uiAutomation.executeShellCommand(cmd)
        return FileInputStream(pfd.fileDescriptor).use { it.readBytes().decodeToString() }.also { pfd.close() }
    }

    /** All-files access so the panes can list /sdcard (what a user grants in Settings). */
    fun grantStorage() {
        val pkg = instrumentation.targetContext.packageName
        shell("appops set $pkg MANAGE_EXTERNAL_STORAGE allow")
    }

    fun fixtureRoot(name: String): File {
        val root = File(Environment.getExternalStorageDirectory(), "FMSmoke/$name")
        root.deleteRecursively()
        check(root.mkdirs()) { "cannot create $root (storage access not granted?)" }
        return root
    }

    fun vm(rule: MainRule): FileManagerViewModel =
        ViewModelProvider(rule.activity, FileManagerViewModel.factory(app))[FileManagerViewModel::class.java]

    fun event(rule: MainRule, e: UiEvent) {
        rule.runOnUiThread { vm(rule).onEvent(e) }
        rule.waitForIdle()
    }

    fun waitText(rule: MainRule, text: String, substring: Boolean = false, timeoutMs: Long = 15_000) {
        try {
            rule.waitUntil(timeoutMs) {
                rule.onAllNodesWithText(text, substring = substring, ignoreCase = substring).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: Throwable) {
            throw AssertionError("Timed out waiting for text '$text'. On screen: ${screenTexts(rule)}", e)
        }
    }

    /** Every text on screen, so a CI failure log says what was showing instead of just "timed out". */
    fun screenTexts(rule: MainRule): String = runCatching {
        rule.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { n ->
                n.config.getOrElseNullable(SemanticsProperties.Text) { null }.orEmpty().map { it.text } +
                    n.config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }.orEmpty().map { "[$it]" }
            }
            .filter { it.isNotBlank() }
            .joinToString(" | ") { it.take(60) }
            .take(3_000)
    }.getOrElse { "<unavailable: ${it.message}>" }

    /** Waits until no file operation is running in the ViewModel. */
    fun waitIdle(rule: MainRule) = waitFor(rule, "operation finished") { !vm(rule).ui.value.busy }

    /** Makes [path] the only selection in [pane], deterministically (Select toggles). */
    fun selectOnly(rule: MainRule, pane: Int, path: String) {
        event(rule, UiEvent.ClearSelection(pane))
        event(rule, UiEvent.Select(pane, path, additive = true))
        waitFor(rule, "selection == $path") {
            val s = vm(rule).ui.value
            (if (pane == 0) s.left else s.right).selected == setOf(path)
        }
    }

    fun waitFor(rule: MainRule, what: String, timeoutMs: Long = 15_000, cond: () -> Boolean) {
        try {
            rule.waitUntil(timeoutMs) { cond() }
        } catch (e: Throwable) {
            val snack = runCatching { vm(rule).ui.value.snackbar }.getOrNull()
            throw AssertionError("Timed out waiting for: $what (snackbar: $snack). On screen: ${screenTexts(rule)}", e)
        }
    }

    /**
     * Minimal HTTP/1.0 GET over a raw socket. The app's network security config (rightly)
     * forbids cleartext HttpURLConnection, and this test runs inside the app process.
     */
    fun httpGet(url: String, timeoutMs: Int = 5_000): Pair<Int, String> {
        val u = java.net.URI(url)
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress(u.host, u.port), timeoutMs)
            s.soTimeout = timeoutMs
            val path = (u.rawPath.ifEmpty { "/" }) + (u.rawQuery?.let { "?$it" } ?: "")
            s.getOutputStream().write("GET $path HTTP/1.0\r\nHost: ${u.host}\r\nConnection: close\r\n\r\n".toByteArray())
            s.getOutputStream().flush()
            val raw = s.getInputStream().readBytes().decodeToString()
            val code = raw.lineSequence().first().split(' ').getOrNull(1)?.toIntOrNull() ?: -1
            return code to raw.substringAfter("\r\n\r\n", "")
        }
    }

    fun clickable(text: String): SemanticsMatcher = hasText(text) and hasClickAction()
}
