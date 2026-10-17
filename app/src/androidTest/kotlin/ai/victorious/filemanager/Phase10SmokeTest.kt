package ai.victorious.filemanager

import ai.victorious.filemanager.ui.Dest
import ai.victorious.filemanager.ui.ToolRequest
import ai.victorious.filemanager.ui.UiEvent
import ai.victorious.filemanager.ui.theme.ThemeMode
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Phase 10 features on a real (emulated) device. */
@RunWith(AndroidJUnit4::class)
class Phase10SmokeTest {
    companion object {
        @BeforeClass @JvmStatic
        fun grant() = Smoke.grantStorage()
    }

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()
    private lateinit var root: File

    @Before fun setUp() { root = Smoke.fixtureRoot("P10-" + System.nanoTime()) }

    @After fun tearDown() {
        root.deleteRecursively()
        Smoke.app.themeSettings.update { it.copy(mode = ThemeMode.SYSTEM) }
    }

    @Test fun appearanceSettingsSwitchToAmoled() {
        Smoke.event(rule, UiEvent.Navigate(Dest.SETTINGS))
        Smoke.waitText(rule, "Appearance")
        rule.onNode(Smoke.clickable("AMOLED black")).performClick()
        Smoke.waitFor(rule, "theme persisted") { Smoke.app.themeSettings.state.value.mode == ThemeMode.AMOLED }
    }

    @Test fun indexedSearchFindsNewFile() {
        File(root, "deep/er").mkdirs()
        File(root, "deep/er/zebra-unique-p10.txt").writeText("z")
        Smoke.app.index.invalidate()
        Smoke.event(rule, UiEvent.Navigate(Dest.SEARCH))
        rule.onNode(hasSetTextAction()).performTextInput("zebra-unique-p10 *.txt")
        Smoke.waitText(rule, "files indexed", substring = true, timeoutMs = 60_000)
        Smoke.waitFor(rule, "hit") { Smoke.vm(rule).ui.value.searchHits.any { it.entry.name == "zebra-unique-p10.txt" } }
    }

    @Test fun deleteGoesToSameVolumeTrashAndRestores() {
        val f = File(root, "trash-me.txt").apply { writeText("bye") }
        Smoke.event(rule, UiEvent.Open(0, root.absolutePath))
        Smoke.waitText(rule, "trash-me.txt")
        Smoke.event(rule, UiEvent.Select(0, f.absolutePath, additive = false))
        Smoke.event(rule, UiEvent.DeleteSelected)
        Smoke.waitFor(rule, "deleted") { !f.exists() }
        val entry = Smoke.app.trash.list().first { it.originalPath == f.absolutePath }
        assertTrue("payload on shared storage: ${entry.trashPayloadPath}", entry.trashPayloadPath.contains("/.victorious-trash/items/"))
        Smoke.event(rule, UiEvent.RestoreTrash(entry.id))
        Smoke.waitFor(rule, "restored") { f.exists() }
        assertEquals("bye", f.readText())
    }

    @Test fun compareTwoApksShowsNoDifferencesForCopies() {
        val src = File(Smoke.app.applicationInfo.sourceDir)
        val a = File(root, "a.apk").also { src.copyTo(it) }
        val b = File(root, "b.apk").also { src.copyTo(it) }
        Smoke.event(rule, UiEvent.OpenTool(ToolRequest.CompareApks(a.path, b.path, root.path)))
        Smoke.waitText(rule, "No differences", timeoutMs = 60_000)
        rule.onNode(Smoke.clickable("Save report")).performClick()
        Smoke.waitFor(rule, "report saved", timeoutMs = 20_000) { root.listFiles()!!.any { it.name.startsWith("apk-compare-") } }
    }

    @Test fun smaliToolsSearchesOwnApk() {
        val apk = File(root, "self.apk").also { File(Smoke.app.applicationInfo.sourceDir).copyTo(it) }
        Smoke.event(rule, UiEvent.OpenTool(ToolRequest.SmaliTools(apk.path, null, root.path)))
        Smoke.waitText(rule, "classes ·", substring = true, timeoutMs = 120_000)
        rule.onNode(hasSetTextAction()).performTextInput("FileManagerViewModel")
        Smoke.waitText(rule, "match(es)", substring = true, timeoutMs = 60_000)
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("ai.victorious.filemanager.ui.FileManagerViewModel", substring = true))
    }
}
