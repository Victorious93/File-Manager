package ai.victorious.filemanager

import ai.victorious.filemanager.ui.UiEvent
import ai.victorious.filemanager.ui.ViewerMode
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.fragment.app.FragmentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end smoke tests on a real (emulated) device: real MainActivity, real Compose UI,
 * real filesystem under /sdcard/FMSmoke. Navigation that is a single VM event (e.g. "open
 * this folder in pane 1") is driven through the ViewModel; the user-facing actions under
 * test (buttons, dialogs, menus, editors) are clicked through the UI.
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {
    companion object {
        @BeforeClass @JvmStatic
        fun grant() = Smoke.grantStorage()
    }

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Smoke.fixtureRoot(javaClass.simpleName + "-" + System.nanoTime())
        File(root, "a").mkdirs()
        File(root, "b").mkdirs()
        File(root, "sub").mkdirs()
        File(root, "sub/inner.txt").writeText("inner")
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
        Smoke.app.wifiTransfer.stop()
    }

    private fun openBoth(left: File, right: File) {
        Smoke.event(rule, UiEvent.Open(1, right.absolutePath))
        Smoke.event(rule, UiEvent.Open(0, left.absolutePath))
        Smoke.waitFor(rule, "panes at $left / $right") {
            val s = Smoke.vm(rule).ui.value
            s.left.active.path == left.absolutePath && s.right.active.path == right.absolutePath
        }
    }

    private fun nav(label: String) {
        rule.onNode(Smoke.clickable(label) and !hasText("Victorious")).performClick()
        rule.waitForIdle()
    }

    private fun openTool(title: String) {
        nav("Tools")
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(title))
        rule.onNode(Smoke.clickable(title)).performClick()
        rule.waitForIdle()
    }

    private fun selectViaMenu(name: String) {
        rule.onAllNodesWithText(name)[0].performTouchInput { longClick() }
        rule.onNode(Smoke.clickable("Select")).performClick()
        rule.waitForIdle()
    }

    @Test
    fun launchesFragmentActivityWithStorageAccess() {
        assertTrue("MainActivity must be a FragmentActivity (BiometricPrompt)", rule.activity is FragmentActivity)
        rule.onNodeWithText("Victorious").assertExists()
        for (label in listOf("Files", "Search", "Apps", "Tools", "Trash", "Settings")) rule.onNode(Smoke.clickable(label)).assertExists()
        Smoke.waitFor(rule, "storage access") { Smoke.vm(rule).ui.value.hasStorageAccess }
        rule.onAllNodesWithText("Storage access needed").assertCountEquals(0)
    }

    @Test
    fun browseFoldersDualPaneAndTabs() {
        openBoth(root, root)
        Smoke.waitText(rule, "sub")
        rule.onAllNodesWithText("sub")[0].performClick() // tap a folder row in the left pane
        Smoke.waitFor(rule, "left pane entered sub") { Smoke.vm(rule).ui.value.left.active.path == File(root, "sub").absolutePath }
        Smoke.waitText(rule, "inner.txt")
        assertEquals(root.absolutePath, Smoke.vm(rule).ui.value.right.active.path)

        rule.onAllNodesWithContentDescription("Up")[0].performClick()
        Smoke.waitFor(rule, "up to root") { Smoke.vm(rule).ui.value.left.active.path == root.absolutePath }

        rule.onAllNodesWithContentDescription("New tab")[0].performClick()
        Smoke.waitFor(rule, "second tab") { Smoke.vm(rule).ui.value.left.tabs.size == 2 }
        rule.onAllNodesWithContentDescription("Close tab").assertCountEquals(3)
        val close = rule.onAllNodesWithContentDescription("Close tab")[1].performScrollTo()
        Smoke.clickAndExpect(rule, close, "tab closed (left tabs=${Smoke.vm(rule).ui.value.left.tabs.size})") {
            Smoke.vm(rule).ui.value.left.tabs.size == 1
        }
    }

    @Test
    fun createRenameCopyMoveDeleteWithUndo() {
        val a = File(root, "a"); val b = File(root, "b")
        openBoth(a, b)
        Smoke.event(rule, UiEvent.FocusPane(0))

        // Create file → undo → create again
        rule.onAllNodesWithContentDescription("New folder or file")[0].performClick()
        rule.onNode(Smoke.input()).performTextInput("note.txt")
        rule.onNode(Smoke.clickable("New file")).performClick()
        Smoke.waitFor(rule, "note.txt created") { File(a, "note.txt").isFile }
        Smoke.waitFor(rule, "undo enabled") { Smoke.vm(rule).ui.value.canUndo }
        rule.onNodeWithContentDescription("Undo").performClick()
        Smoke.waitFor(rule, "create undone") { !File(a, "note.txt").exists() }

        rule.onAllNodesWithContentDescription("New folder or file")[0].performClick()
        rule.onNode(Smoke.input()).performTextInput("made")
        rule.onNode(Smoke.clickable("New folder")).performClick()
        Smoke.waitFor(rule, "folder created") { File(a, "made").isDirectory }
        File(a, "note.txt").writeText("hello")
        Smoke.event(rule, UiEvent.Refresh)
        Smoke.waitText(rule, "note.txt")

        // Rename via long-press menu → Select → Rename dialog
        selectViaMenu("note.txt")
        rule.onNodeWithContentDescription("Rename").performClick()
        rule.onNode(Smoke.input()).performTextClearance()
        rule.onNode(Smoke.input()).performTextInput("renamed.txt")
        rule.onNode(Smoke.clickable("Save")).performClick()
        Smoke.waitFor(rule, "renamed") { File(a, "renamed.txt").isFile && !File(a, "note.txt").exists() }
        Smoke.waitIdle(rule)

        // Copy to other pane
        val renamed = File(a, "renamed.txt").absolutePath
        Smoke.selectOnly(rule, 0, renamed)
        rule.onNodeWithContentDescription("Copy").performClick()
        Smoke.waitFor(rule, "copied") { File(b, "renamed.txt").isFile && File(a, "renamed.txt").isFile }
        Smoke.waitIdle(rule)

        // Move to other pane, then undo
        Smoke.selectOnly(rule, 0, renamed)
        rule.onNodeWithContentDescription("Move").performClick()
        Smoke.waitFor(rule, "moved") { !File(a, "renamed.txt").exists() && File(b, "renamed (1).txt").isFile }
        Smoke.waitIdle(rule)
        rule.onNodeWithContentDescription("Undo").performClick()
        Smoke.waitFor(rule, "move undone") { File(a, "renamed.txt").isFile && !File(b, "renamed (1).txt").exists() }

        // Delete (to trash), then undo
        Smoke.event(rule, UiEvent.Refresh)
        Smoke.selectOnly(rule, 0, renamed)
        rule.onNodeWithContentDescription("Delete").performClick()
        Smoke.waitFor(rule, "deleted") { !File(a, "renamed.txt").exists() }
        Smoke.waitIdle(rule)
        Smoke.waitFor(rule, "in trash") { Smoke.app.trash.list().any { it.originalPath == renamed } }
        rule.onNodeWithContentDescription("Undo").performClick()
        Smoke.waitFor(rule, "delete undone") { File(a, "renamed.txt").isFile }
        assertEquals("hello", File(a, "renamed.txt").readText())
    }

    @Test
    fun textEditorEditsAndSaves() {
        val f = File(root, "edit.txt").apply { writeText("before") }
        Smoke.event(rule, UiEvent.OpenFile(f.absolutePath, ViewerMode.TEXT))
        Smoke.waitText(rule, "edit.txt")
        rule.waitUntil(15_000) { rule.onAllNodes(Smoke.input()).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(Smoke.input()).performTextReplacement("after smoke edit")
        rule.onNodeWithContentDescription("Save").performClick()
        Smoke.waitFor(rule, "file saved") { f.readText() == "after smoke edit" }
        rule.onNodeWithContentDescription("Close").performClick()
        Smoke.waitFor(rule, "viewer closed") { Smoke.vm(rule).ui.value.viewer == null }
    }

    @Test
    fun hexViewShowsBytes() {
        val f = File(root, "data.bin").apply { writeBytes(byteArrayOf(0x41, 0x42, 0x43, 0x00, 0x7F)) }
        Smoke.event(rule, UiEvent.OpenFile(f.absolutePath, ViewerMode.HEX))
        Smoke.waitText(rule, "41 42 43 00 7F", substring = true)
        Smoke.waitText(rule, "00000000", substring = true)
    }

    @Test
    fun zipArchiveListsAndExtractsToOtherPane() {
        val a = File(root, "a"); val b = File(root, "b")
        val zip = File(a, "bundle.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("hello.txt")); z.write("hi from zip".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("dir/deep.txt")); z.write("deep".toByteArray()); z.closeEntry()
        }
        openBoth(a, b)
        Smoke.event(rule, UiEvent.FocusPane(0))
        Smoke.event(rule, UiEvent.OpenFile(zip.absolutePath, ViewerMode.ARCHIVE))
        Smoke.waitText(rule, "hello.txt", substring = true)
        rule.onNodeWithContentDescription("Extract to other pane").performClick()
        Smoke.waitFor(rule, "extracted") { File(b, "bundle/hello.txt").isFile && File(b, "bundle/dir/deep.txt").isFile }
        assertEquals("hi from zip", File(b, "bundle/hello.txt").readText())
    }

    @Test
    fun corruptArchiveShowsErrorInsteadOfCrashing() {
        // Regression: the encryption probe ran outside runCatching and crashed the screen.
        val bad = File(root, "broken.zip").apply { writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 1, 2, 3)) }
        Smoke.event(rule, UiEvent.OpenFile(bad.absolutePath, ViewerMode.ARCHIVE))
        Smoke.waitText(rule, "broken.zip")
        rule.waitForIdle()
        Thread.sleep(1_000)
        rule.onNodeWithContentDescription("Close").assertExists()
    }

    @Test
    fun apkInfoForSampleApk() {
        val apk = File(root, "sample.apk")
        File(Smoke.app.applicationInfo.sourceDir).copyTo(apk)
        Smoke.event(rule, UiEvent.ShowApkInfo(apk.absolutePath))
        Smoke.waitText(rule, Smoke.app.packageName, substring = true, timeoutMs = 30_000)
        Smoke.waitText(rule, "Permissions (", substring = true)
        rule.onNode(Smoke.clickable("Close")).performClick()
        Smoke.waitFor(rule, "dialog closed") { Smoke.vm(rule).ui.value.apkInfo == null }
    }

    @Test
    fun settingsScreenOpens() {
        nav("Settings")
        Smoke.waitText(rule, "Privilege")
        rule.onNodeWithText("Root (su / Magisk)").assertExists()
        rule.onNodeWithText("AI assistant").assertExists()
        rule.onNodeWithText("Enable AI assistant").assertExists()
        rule.onNodeWithText("Secure folder").assertExists()
    }

    @Test
    fun terminalRunsEchoHi() {
        openTool("Terminal")
        // sum=42 only appears if the shell really evaluated the line (not just echoed input).
        val line = "echo hi; echo sum=\$((40+2))"
        if (ai.victorious.filemanager.terminal.PtySupport.available) {
            // Real PTY: a native TerminalView (no Compose text field). Type into its session.
            var view: com.termux.view.TerminalView? = null
            Smoke.waitFor(rule, "PTY TerminalView with a running session", timeoutMs = 20_000) {
                rule.runOnUiThread { view = findView(rule.activity.window.decorView) }
                view?.currentSession?.let { it.pid > 0 } == true
            }
            val session = view!!.currentSession!!
            val bytes = (line + "\r").toByteArray()
            session.write(bytes, 0, bytes.size)
            Smoke.waitFor(rule, "PTY printed sum=42", timeoutMs = 20_000) {
                session.emulator?.screen?.transcriptText?.contains("sum=42") == true
            }
        } else {
            rule.waitUntil(15_000) { rule.onAllNodes(Smoke.input()).fetchSemanticsNodes().isNotEmpty() }
            rule.onNode(Smoke.input()).performTextInput(line)
            rule.onNode(Smoke.clickable("Send")).performClick()
            Smoke.waitText(rule, "sum=42", substring = true, timeoutMs = 20_000)
        }
        rule.onNodeWithContentDescription("Close").performClick()
        Smoke.waitFor(rule, "terminal closed") { !Smoke.vm(rule).ui.value.showTerminal }
    }

    private fun findView(v: android.view.View): com.termux.view.TerminalView? = when (v) {
        is com.termux.view.TerminalView -> v
        is android.view.ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { findView(v.getChildAt(it)) }
        else -> null
    }

    @Test
    fun appManagerListsApps() {
        nav("Apps")
        // App Manager Pro opens on its own "Apps" tab; the installed-app list with User/System/All chips is under "Backup".
        rule.onNode(Smoke.clickable("Backup")).performClick()
        Smoke.waitText(rule, Smoke.app.packageName, substring = true, timeoutMs = 30_000)
        rule.onNode(Smoke.clickable("All")).performClick()
        Smoke.waitText(rule, " · system", substring = true, timeoutMs = 30_000)
    }

    @Test
    fun wifiTransferStartsServesAndStops() {
        Smoke.event(rule, UiEvent.Open(0, root.absolutePath))
        openTool("Wi-Fi file transfer")
        rule.onNode(Smoke.clickable("Start")).performClick()
        Smoke.waitText(rule, "URL: http://", substring = true)
        val ctrl = Smoke.app.wifiTransfer
        assertTrue(ctrl.isRunning)
        val url = ctrl.url!!
        val pin = ctrl.pin
        assertFalse("must bind a LAN address, not loopback: $url", url.contains("127.0.0.1"))
        val (code, body) = Smoke.httpGet(url + (pin?.let { "?pin=$it" } ?: ""))
        assertEquals(200, code)
        assertTrue("listing should include fixture folder", body.contains("sub"))
        if (pin != null) {
            val (denied, _) = Smoke.httpGet(url)
            assertTrue("no PIN must be rejected, got $denied", denied in 401..403)
        }
        rule.onNode(Smoke.clickable("Stop")).performClick()
        Smoke.waitFor(rule, "server stopped") { !ctrl.isRunning }
        val after = runCatching { Smoke.httpGet(url, timeoutMs = 2_000) }
        assertTrue("server must refuse connections after Stop", after.isFailure)
    }

    @Test
    fun secureFolderScreenOpensLocked() {
        openTool("Secure folder")
        Smoke.waitText(rule, "AES-256-GCM", substring = true)
        rule.onNode(Smoke.clickable("Unlock")).assertExists()
        assertFalse(Smoke.app.secureFolder.lock.isUnlocked)
    }

    @Test
    fun pluginsScreenOpensAndInstallsSamples() {
        openTool("Plugins")
        // Not the description text: the Tools list subtitle also says "Declarative JSON plugins",
        // so that wait passed before this screen had opened.
        Smoke.waitText(rule, "Add samples")
        Smoke.clickAndExpect(rule, rule.onNode(Smoke.clickable("Add samples")), "samples installed") {
            Smoke.app.plugins.list().isNotEmpty()
        }
        Smoke.waitText(rule, "Capabilities:", substring = true)
        assertTrue(Smoke.app.plugins.list().isNotEmpty())
        assertTrue("samples start disabled", Smoke.app.plugins.list().none { it.enabled })
    }

    @Test
    fun scriptsScreenOpens() {
        openTool("Scripts")
        rule.onNode(Smoke.clickable("New script")).assertExists()
    }
}
