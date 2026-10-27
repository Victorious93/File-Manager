package ai.victorious.filemanager

import ai.victorious.filemanager.core.listing.ListingOptions
import ai.victorious.filemanager.ui.UiEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.compose.ui.test.junit4.createAndroidComposeRule

/** Main-flow checks added by the bug-hunt pass: huge folders and state across a configuration change. */
@RunWith(AndroidJUnit4::class)
class BugHuntSmokeTest {
    companion object {
        @BeforeClass @JvmStatic
        fun grant() = Smoke.grantStorage()
    }

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private lateinit var root: File

    @Before fun setUp() {
        Smoke.useDualPane(false)
        root = Smoke.fixtureRoot(javaClass.simpleName + "-" + System.nanoTime())
    }

    @After fun tearDown() {
        Smoke.useDualPane(false)
        Smoke.event(rule, UiEvent.SetListing(ListingOptions()))
        runCatching { root.deleteRecursively() }
    }

    @Test fun tenThousandFileFolderOpensSortsAndFilters() {
        val big = File(root, "big").apply { mkdirs() }
        repeat(10_000) { File(big, "f%05d.txt".format(it)).createNewFile() }
        Smoke.event(rule, UiEvent.Open(0, big.absolutePath))
        Smoke.waitFor(rule, "10k entries listed", timeoutMs = 60_000) {
            Smoke.vm(rule).ui.value.left.active.let { it.path == big.absolutePath && it.entries.size == 10_000 }
        }
        Smoke.waitText(rule, "f00000.txt", timeoutMs = 30_000)
        Smoke.event(rule, UiEvent.SetListing(ListingOptions(filter = "f09999")))
        Smoke.waitText(rule, "f09999.txt", timeoutMs = 30_000)
    }

    @Test fun folderAndSelectionSurviveRotationAndTwoPaneSwitch() {
        val a = File(root, "a").apply { mkdirs() }
        File(a, "keep.txt").writeText("x")
        Smoke.event(rule, UiEvent.Open(0, a.absolutePath))
        Smoke.waitText(rule, "keep.txt")
        Smoke.selectOnly(rule, 0, File(a, "keep.txt").absolutePath)

        rule.activityRule.scenario.recreate() // same path as a rotation or fold/unfold
        Smoke.waitFor(rule, "folder kept after recreate") { Smoke.vm(rule).ui.value.left.active.path == a.absolutePath }
        assertEquals(setOf(File(a, "keep.txt").absolutePath), Smoke.vm(rule).ui.value.left.selected)
        Smoke.waitText(rule, "keep.txt")

        Smoke.useDualPane(true)
        rule.waitForIdle()
        Smoke.waitFor(rule, "left pane unchanged in two-pane mode") { Smoke.vm(rule).ui.value.left.active.path == a.absolutePath }
        Smoke.waitText(rule, "keep.txt")
    }
}
