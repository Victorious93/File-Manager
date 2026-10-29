package ai.victorious.filemanager.core.phase18

import ai.victorious.filemanager.core.palette.CommandPalette
import ai.victorious.filemanager.core.palette.PaletteCommand
import ai.victorious.filemanager.core.share.WifiQr
import ai.victorious.filemanager.core.snapshots.SnapshotStore
import ai.victorious.filemanager.core.tags.ColorTag
import ai.victorious.filemanager.core.tags.ColorTagStore
import ai.victorious.filemanager.core.tags.SmartCollections
import ai.victorious.filemanager.core.tags.SmartEntry
import ai.victorious.filemanager.core.tags.SmartRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Phase18FeaturesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun colorTagsToggleMoveAndPrune() {
        val s = ColorTagStore(tmp.newFolder().resolve("tags.json").path)
        s.toggle("/sd/a.txt", ColorTag.RED); s.toggle("/sd/a.txt", ColorTag.BLUE)
        s.toggle("/sd/dir/b.txt", ColorTag.RED)
        assertEquals(setOf(ColorTag.RED, ColorTag.BLUE), s.tagsOf("/sd/a.txt"))
        assertEquals(listOf("/sd/a.txt", "/sd/dir/b.txt"), s.pathsWith(ColorTag.RED))
        s.toggle("/sd/a.txt", ColorTag.BLUE)
        assertEquals(setOf(ColorTag.RED), s.tagsOf("/sd/a.txt"))
        s.onMoved("/sd/dir", "/sd/moved")
        assertEquals(setOf(ColorTag.RED), s.tagsOf("/sd/moved/b.txt"))
        assertEquals(1, s.prune { it == "/sd/a.txt" })
        assertEquals(listOf("/sd/a.txt"), s.pathsWith(ColorTag.RED))
    }

    @Test fun smartCollectionRules() {
        val now = 100L * 86_400_000L
        val entries = sequenceOf(
            SmartEntry("/sd/Docs/report.pdf", 6_000_000, now - 86_400_000L),
            SmartEntry("/sd/Docs/old.pdf", 6_000_000, now - 40 * 86_400_000L),
            SmartEntry("/sd/Docs/tiny.pdf", 10, now),
            SmartEntry("/sd/Docs", 0, now, isDirectory = true),
        )
        val rule = SmartRule("big recent pdf", extensions = setOf("PDF"), minBytes = 5_000_000, modifiedWithinDays = 7)
        assertEquals(listOf("/sd/Docs/report.pdf"), SmartCollections.evaluate(rule, entries, { emptySet() }, now).map { it.path })
        val red = SmartRule("red", colorTags = setOf("RED"), underPath = "/sd/Docs")
        val got = SmartCollections.evaluate(red, entries, { if (it.endsWith("tiny.pdf")) setOf(ColorTag.RED) else emptySet() }, now)
        assertEquals(listOf("/sd/Docs/tiny.pdf"), got.map { it.path })
    }

    @Test fun snapshotsDedupeRestoreAndPrune() {
        var t = 1000L
        val store = SnapshotStore(tmp.newFolder("snaps"), keepPerFile = 3) { t++ }
        val f = tmp.newFile("note.txt").apply { writeText("v1") }
        assertTrue(store.take(f) != null)
        assertNull(store.take(f)) // unchanged -> skipped
        f.writeText("v2"); store.take(f)
        f.writeText("v3"); store.take(f)
        f.writeText("v4"); store.take(f)
        val list = store.list(f.path)
        assertEquals(3, list.size)
        assertEquals("v4", list[0].file.readText())
        f.writeText("edited")
        val safety = store.restore(list.last())
        assertEquals("v2", f.readText())
        assertEquals("edited", safety!!.file.readText())
        assertEquals(mapOf(f.absolutePath to 3), store.trackedFiles())
    }

    @Test fun wifiQrPayloadEscapesAndRoundTrips() {
        val n = WifiQr.Network("My;Net", "pa:ss\\word", WifiQr.Security.WPA, hidden = true)
        val p = WifiQr.payload(n)
        assertEquals("WIFI:T:WPA;S:My\\;Net;P:pa\\:ss\\\\word;H:true;;", p)
        assertEquals(n, WifiQr.parse(p))
        assertEquals("WIFI:T:nopass;S:Cafe;;", WifiQr.payload(WifiQr.Network("Cafe", null, WifiQr.Security.OPEN)))
    }

    @Test(expected = IllegalArgumentException::class) fun wifiQrRejectsShortWpa() {
        WifiQr.payload(WifiQr.Network("x", "short", WifiQr.Security.WPA))
    }

    @Test fun paletteRanksWordStartsAndRecents() {
        val cmds = listOf(
            PaletteCommand("dup", "Find duplicates"),
            PaletteCommand("sync", "Folder sync", keywords = listOf("mirror", "backup")),
            PaletteCommand("snap", "File snapshots"),
            PaletteCommand("term", "Terminal"),
        )
        assertEquals("dup", CommandPalette.rank("fd", cmds).first().id)
        assertEquals("sync", CommandPalette.rank("mirror", cmds).first().id)
        assertTrue(CommandPalette.rank("zzz", cmds).isEmpty())
        assertEquals(listOf("term", "dup"), CommandPalette.rank("", cmds, recent = listOf("term", "dup")).take(2).map { it.id })
        assertEquals(listOf("a", "b", "c"), CommandPalette.pushRecent(listOf("b", "a", "c"), "a"))
    }
}
