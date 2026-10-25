package ai.victorious.filemanager

import ai.victorious.filemanager.ui.UiEvent
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The default layout: one file list. Copy and Move have no visible "other pane" to send to, so they
 * stage the selection and "Paste here" drops it into whatever folder is open.
 */
@RunWith(AndroidJUnit4::class)
class SinglePaneSmokeTest {
    companion object {
        @BeforeClass @JvmStatic
        fun grant() = Smoke.grantStorage()
    }

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private lateinit var root: File
    private val a get() = File(root, "a")
    private val b get() = File(root, "b")
    private val c get() = File(root, "c")

    @Before
    fun setUp() {
        Smoke.useDualPane(false) // the default, stated explicitly so another class's setting cannot leak in
        root = Smoke.fixtureRoot(javaClass.simpleName + "-" + System.nanoTime())
        listOf(a, b, c).forEach { it.mkdirs() }
        File(a, "note.txt").writeText("hi")
        Smoke.event(rule, UiEvent.Open(0, a.absolutePath))
        Smoke.waitFor(rule, "pane at $a") { Smoke.vm(rule).ui.value.left.active.path == a.absolutePath }
        Smoke.waitText(rule, "note.txt")
    }

    @After
    fun tearDown() = Smoke.useDualPane(false)

    private fun open(dir: File) {
        Smoke.event(rule, UiEvent.Open(0, dir.absolutePath))
        Smoke.waitFor(rule, "pane at $dir") { Smoke.vm(rule).ui.value.left.active.path == dir.absolutePath }
    }

    @Test
    fun oneFilePaneByDefaultAndTheTopBarSwitchesLayouts() {
        rule.onAllNodes(hasTestTag("listing-filter")).assertCountEquals(1)

        Smoke.clickAndExpect(rule, rule.onNodeWithContentDescription("Show two panes"), "two panes") {
            Smoke.app.themeSettings.state.value.dualPane
        }
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag("listing-filter")).fetchSemanticsNodes().size == 2 }

        Smoke.clickAndExpect(rule, rule.onNodeWithContentDescription("Show one pane"), "one pane") {
            !Smoke.app.themeSettings.state.value.dualPane
        }
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag("listing-filter")).fetchSemanticsNodes().size == 1 }
    }

    @Test
    fun copyThenPasteHereCopiesIntoTheFolderThatIsOpen() {
        Smoke.selectOnly(rule, 0, File(a, "note.txt").absolutePath)
        rule.onNodeWithContentDescription("Copy").performClick()
        Smoke.waitFor(rule, "staged") { Smoke.vm(rule).ui.value.clipboard != null }
        assertFalse("copy must wait for Paste", File(b, "note.txt").exists())
        assertTrue("selection is cleared once staged", Smoke.vm(rule).ui.value.left.selected.isEmpty())

        open(b)
        Smoke.clickAndExpect(rule, rule.onNode(Smoke.clickable("Paste here")), "pasted into b") {
            // A copy creates the destination before it writes it, so existence alone can catch it still empty.
            File(b, "note.txt").let { it.isFile && it.readText() == "hi" } && File(a, "note.txt").isFile
        }
        assertEquals("hi", File(b, "note.txt").readText())
        assertNotNull("a copy stays staged so it can be pasted again", Smoke.vm(rule).ui.value.clipboard)

        rule.onNodeWithContentDescription("Clear clipboard").performClick()
        Smoke.waitFor(rule, "clipboard cleared") { Smoke.vm(rule).ui.value.clipboard == null }
    }

    @Test
    fun moveThenPasteHereMovesAndEmptiesTheClipboard() {
        Smoke.selectOnly(rule, 0, File(a, "note.txt").absolutePath)
        rule.onNodeWithContentDescription("Move").performClick()
        Smoke.waitFor(rule, "staged") { Smoke.vm(rule).ui.value.clipboard?.move == true }
        assertTrue("move must wait for Paste", File(a, "note.txt").isFile)

        open(c)
        Smoke.clickAndExpect(rule, rule.onNode(Smoke.clickable("Paste here")), "moved into c") {
            File(c, "note.txt").isFile && !File(a, "note.txt").exists()
        }
        Smoke.waitFor(rule, "clipboard spent") { Smoke.vm(rule).ui.value.clipboard == null }
        rule.waitUntil(10_000) { rule.onAllNodesWithContentDescription("Clear clipboard").fetchSemanticsNodes().isEmpty() }
        assertNull(Smoke.vm(rule).ui.value.clipboard)

        Smoke.waitIdle(rule)
        rule.onNodeWithContentDescription("Undo").performClick()
        Smoke.waitFor(rule, "move undone") { File(a, "note.txt").isFile && !File(c, "note.txt").exists() }
    }

    @Test
    fun pastingAMoveBackIntoTheSameFolderIsRefusedAndKeepsTheClipboard() {
        Smoke.selectOnly(rule, 0, File(a, "note.txt").absolutePath)
        rule.onNodeWithContentDescription("Move").performClick()
        Smoke.waitFor(rule, "staged") { Smoke.vm(rule).ui.value.clipboard?.move == true }

        Smoke.clickAndExpect(rule, rule.onNode(Smoke.clickable("Paste here")), "refused with a message") {
            Smoke.vm(rule).ui.value.snackbar?.contains("Already in that folder") == true
        }
        assertEquals("hi", File(a, "note.txt").readText())
        assertFalse(File(a, "note (1).txt").exists())
        assertNotNull(Smoke.vm(rule).ui.value.clipboard)
    }
}
