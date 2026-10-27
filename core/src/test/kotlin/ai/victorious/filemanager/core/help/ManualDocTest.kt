package ai.victorious.filemanager.core.help

import ai.victorious.filemanager.core.help.ManualDoc.Block
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualDocTest {
    private val sample = """
        # Title

        Intro with **bold** and `code`.

        ## 1. Getting started

        - First bullet
          continues here
        - Second bullet
        1. Step one
        2. Step two

        ```
        adb install app.apk
        ```

        ---

        ### Folder sync

        > Deletes go to trash.

        Schedule a [WorkManager](https://example.com) job.
    """.trimIndent()

    @Test fun parsesBlocks() {
        val blocks = ManualDoc.parse(sample)
        assertEquals(Block.Heading(1, "Title", "title"), blocks[0])
        assertEquals(Block.Paragraph("Intro with **bold** and `code`."), blocks[1])
        assertEquals("1-getting-started", (blocks[2] as Block.Heading).anchor)
        assertEquals(Block.ListItem("First bullet continues here", false, "•", 0), blocks[3])
        assertEquals(Block.ListItem("Second bullet", false, "•", 0), blocks[4])
        assertEquals(Block.ListItem("Step one", true, "1.", 0), blocks[5])
        assertEquals(Block.Code("adb install app.apk"), blocks[7])
        assertEquals(Block.Rule, blocks[8])
        assertEquals(Block.Quote("Deletes go to trash."), blocks[10])
    }

    @Test fun inlineSpans() {
        val spans = ManualDoc.inline("Use **bold**, *it*, `x` and [docs](http://a)")
        assertEquals(
            listOf(ManualDoc.Style.PLAIN, ManualDoc.Style.BOLD, ManualDoc.Style.PLAIN, ManualDoc.Style.ITALIC, ManualDoc.Style.PLAIN, ManualDoc.Style.CODE, ManualDoc.Style.PLAIN, ManualDoc.Style.LINK),
            spans.map { it.style },
        )
        assertEquals("http://a", spans.last().url)
        assertEquals("Use bold, it, x and docs", ManualDoc.stripInline("Use **bold**, *it*, `x` and [docs](http://a)"))
    }

    @Test fun searchMatchesAllWordsAndHeadingContext() {
        val blocks = ManualDoc.parse(sample)
        val hits = ManualDoc.search(blocks, "workmanager job")
        assertEquals(1, hits.size)
        assertEquals("Folder sync", hits[0].section)
        assertTrue(hits[0].snippet.contains("WorkManager"))
        // Heading words count, so "trash sync" finds the quote under "Folder sync".
        assertEquals(Block.Quote("Deletes go to trash."), blocks[ManualDoc.search(blocks, "trash sync").first().blockIndex])
        assertTrue(ManualDoc.search(blocks, "   ").isEmpty())
        assertTrue(ManualDoc.search(blocks, "nonexistentword").isEmpty())
    }

    @Test fun tocAndSections() {
        val blocks = ManualDoc.parse(sample)
        assertEquals(listOf("1. Getting started", "Folder sync"), ManualDoc.toc(blocks).map { it.second.text })
        val sections = ManualDoc.sections(blocks)
        assertEquals(3, sections.size)
        assertEquals("Folder sync", sections.last().heading?.text)
    }

    /** The real manual that ships in the APK parses into a usable TOC and is searchable. */
    @Test fun realManualParses() {
        val f = listOf(File("../docs/MANUAL.md"), File("docs/MANUAL.md")).firstOrNull { it.isFile }
            ?: throw AssertionError("docs/MANUAL.md not found from ${File(".").absolutePath}")
        val blocks = ManualDoc.parse(f.readText())
        assertTrue(ManualDoc.toc(blocks).size >= 7)
        assertTrue(ManualDoc.search(blocks, "trash").isNotEmpty())
        assertTrue(blocks.none { it is Block.Paragraph && it.text.startsWith("#") })
    }
}
