package ai.victorious.filemanager.ui

import ai.victorious.filemanager.ui.theme.FileCategory
import ai.victorious.filemanager.ui.theme.IconStyle
import ai.victorious.filemanager.ui.theme.ThemePrefs
import ai.victorious.filemanager.ui.theme.effectiveDualPane
import ai.victorious.filemanager.ui.theme.fileCategory
import ai.victorious.filemanager.ui.theme.icon
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiOverhaulTest {
    @Test fun breadcrumbsCollapseTheStorageRoot() {
        assertEquals(
            listOf(
                Crumb("Storage", "/storage/emulated/0"),
                Crumb("Download", "/storage/emulated/0/Download"),
                Crumb("apks", "/storage/emulated/0/Download/apks"),
            ),
            breadcrumbs("/storage/emulated/0/Download/apks/"),
        )
        assertEquals(listOf(Crumb("Storage", "/storage/emulated/0")), breadcrumbs("/storage/emulated/0"))
    }

    @Test fun breadcrumbsForOtherPaths() {
        assertEquals(listOf(Crumb("/", "/"), Crumb("data", "/data"), Crumb("app", "/data/app")), breadcrumbs("/data/app"))
        assertEquals(listOf(Crumb("/", "/")), breadcrumbs("/"))
        assertEquals(1, breadcrumbs("smb://nas/share/dir").size)
        assertTrue(breadcrumbs("").isEmpty())
        // A sibling that merely starts with the root's name is not under it.
        assertEquals("/", breadcrumbs("/storage/emulated/01/x").first().label)
    }

    @Test fun fileCategoriesByExtension() {
        assertEquals(FileCategory.FOLDER, fileCategory("Pictures.jpg", isDirectory = true))
        assertEquals(FileCategory.IMAGE, fileCategory("a.JPG", false))
        assertEquals(FileCategory.VIDEO, fileCategory("clip.mkv", false))
        assertEquals(FileCategory.AUDIO, fileCategory("song.flac", false))
        assertEquals(FileCategory.ARCHIVE, fileCategory("x.tar.gz", false))
        assertEquals(FileCategory.APK, fileCategory("app.apks", false))
        assertEquals(FileCategory.PDF, fileCategory("doc.pdf", false))
        assertEquals(FileCategory.CODE, fileCategory("Main.kt", false))
        assertEquals(FileCategory.DOCUMENT, fileCategory("notes.txt", false))
        assertEquals(FileCategory.OTHER, fileCategory("Makefile", false))
        assertEquals(FileCategory.OTHER, fileCategory(".bashrc", false))
    }

    @Test fun everyIconStyleHasAnIconForEveryCategory() {
        for (st in IconStyle.entries) for (c in FileCategory.entries) assertNotNull(c.icon(st))
        assertEquals(listOf("OUTLINED", "ROUNDED", "SHARP", "FILLED", "COLOURFUL"), IconStyle.entries.map { it.name })
    }

    @Test fun windowSizeClasses() {
        assertEquals(WidthClass.COMPACT, widthClassFor(411))
        assertEquals(WidthClass.MEDIUM, widthClassFor(600))
        assertEquals(WidthClass.MEDIUM, widthClassFor(839))
        assertEquals(WidthClass.EXPANDED, widthClassFor(840))
        assertEquals(WidthClass.EXPANDED, widthClassFor(1280, 800)) // tablet landscape
        assertEquals(WidthClass.MEDIUM, widthClassFor(914, 411)) // phone landscape
        assertTrue(WindowLayout(WidthClass.EXPANDED, null, false).expanded)
        assertFalse(WindowLayout(WidthClass.MEDIUM, null, false).expanded)
        assertTrue(WindowLayout(WidthClass.MEDIUM, HingeSplit(0.5f, 0.dp), true).expanded)
        assertFalse(WindowLayout(WidthClass.COMPACT, null, false).useRail)
    }

    @Test fun hingeSplitsAtTheFoldOnlyWhenUsable() {
        val split = hingeSplitFor(1000, 1040, 2080, density = 2f)!!
        assertEquals(1000f / 2080f, split.leftFraction, 0.001f)
        assertEquals(20f, split.gap.value, 0.001f)
        assertNull(hingeSplitFor(100, 100, 2000, 2f)) // far off-centre
        assertNull(hingeSplitFor(10, 5, 2000, 2f))
        assertNull(hingeSplitFor(0, 0, 0, 2f))
    }

    @Test fun twoPanesAutomaticallyOnlyOnExpandedWindows() {
        val d = ThemePrefs()
        assertFalse(d.effectiveDualPane(expandedWindow = false))
        assertTrue(d.effectiveDualPane(expandedWindow = true))
        assertFalse(d.copy(autoDualOnWide = false).effectiveDualPane(true))
        assertTrue(d.copy(dualPane = true).effectiveDualPane(false))
    }

    @Test fun newPrefsDefaultsKeepTheOldBehaviour() {
        val d = ThemePrefs()
        assertFalse(d.gridView)
        assertFalse(d.startOnHome)
        assertEquals(IconStyle.OUTLINED, d.iconStyle)
    }

    @Test fun recentFilesNewestFirstSkippingHidden() {
        val files = sequenceOf(
            RecentFile("/s/Download/a.txt", "a.txt", 10, 1),
            RecentFile("/s/Download/.hidden", ".hidden", 99, 1),
            RecentFile("/s/DCIM/.thumbnails/t.jpg", "t.jpg", 98, 1),
            RecentFile("/s/DCIM/b.jpg", "b.jpg", 30, 1),
            RecentFile("/s/Documents/c.pdf", "c.pdf", 20, 1),
        )
        assertEquals(listOf("b.jpg", "c.pdf"), pickRecent(files, limit = 2).map { it.name })
    }

    @Test fun storageFractions() {
        val v = StorageVolumeInfo("x", "/x", totalBytes = 100, freeBytes = 25)
        assertEquals(75L, v.usedBytes)
        assertEquals(0.75f, v.usedFraction, 0.001f)
        assertEquals(0f, StorageVolumeInfo("y", "/y", 0, 0).usedFraction, 0f)
    }
}
