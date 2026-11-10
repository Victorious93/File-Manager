package ai.victorious.filemanager.core.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownPreviewExtrasTest {
    @Test fun taskLinesPointIntoTheWholeTextIncludingFrontmatter() {
        val md = "---\ntitle: T\n---\n# Todo\n- [ ] buy milk\n- [x] done thing\n  - [ ] nested\n"
        val tasks = MarkdownPreview.parse(md).filterIsInstance<MdBlock.Task>()
        assertEquals(3, tasks.size)
        assertFalse(tasks[0].checked); assertTrue(tasks[1].checked)
        assertEquals(1, tasks[2].depth)
        assertEquals("- [ ] buy milk", md.split('\n')[tasks[0].line])
        val toggled = NoteOps.toggleTask(md, tasks[0].line)
        assertTrue(toggled.contains("- [x] buy milk"))
        assertTrue(MarkdownPreview.parse(toggled).filterIsInstance<MdBlock.Task>()[0].checked)
    }

    @Test fun pipeTables() {
        val md = "Intro\n\n| Name | Qty | Note |\n|:-----|----:|:----:|\n| **apple** | 3 | a \\| b |\n| pear | 10 |\n\nafter"
        val blocks = MarkdownPreview.parse(md)
        val t = blocks.filterIsInstance<MdBlock.Table>().single()
        assertEquals(3, t.header.size)
        assertEquals(listOf(TableAlign.LEFT, TableAlign.RIGHT, TableAlign.CENTER), t.align)
        assertEquals(2, t.rows.size)
        assertTrue(t.rows[0][0].single() is MdSpan.Bold)
        assertEquals("a | b", (t.rows[0][2].single() as MdSpan.Text).text)
        assertTrue(t.rows[1][2].isEmpty()) // short row padded
        assertTrue(blocks.last() is MdBlock.Paragraph)
        // A lone pipe without a separator row is just text.
        assertTrue(MarkdownPreview.parse("a | b\nc").single() is MdBlock.Paragraph)
    }

    @Test fun inlineExtras() {
        val spans = MarkdownPreview.inline("See [docs](https://example.com/a) and <https://x.org>, ~~old~~ ==key== __b__ _i_ snake_case_name")
        assertEquals(MdSpan.Link("docs", "https://example.com/a"), spans.first { it is MdSpan.Link })
        assertEquals(2, spans.count { it is MdSpan.Link })
        assertTrue(spans.contains(MdSpan.Strike("old")))
        assertTrue(spans.contains(MdSpan.Highlight("key")))
        assertTrue(spans.contains(MdSpan.Bold("b")))
        assertTrue(spans.contains(MdSpan.Italic("i")))
        assertTrue(spans.filterIsInstance<MdSpan.Text>().joinToString("") { it.text }.contains("snake_case_name"))
    }

    @Test fun unsafeLinksStayText() {
        val spans = MarkdownPreview.inline("[click](javascript:alert(1)) [f](file:///sdcard/x)")
        assertTrue(spans.none { it is MdSpan.Link })
        assertFalse(MarkdownPreview.isSafeUrl("intent://x"))
        assertTrue(MarkdownPreview.isSafeUrl("mailto:a@b.c"))
    }

    @Test fun nestedListsAndParagraphJoin() {
        val md = "- top\n    - deeper\n1. one\n  2) two\nline one\nline two\n# H #"
        val b = MarkdownPreview.parse(md)
        assertEquals(0, (b[0] as MdBlock.Bullet).depth)
        assertEquals(2, (b[1] as MdBlock.Bullet).depth)
        assertEquals(1, (b[2] as MdBlock.Numbered).n)
        assertEquals(1, (b[3] as MdBlock.Numbered).depth)
        val p = b[4] as MdBlock.Paragraph
        assertEquals("line one line two", (p.spans.single() as MdSpan.Text).text)
        assertEquals("H", (b[5] as MdBlock.Heading).text)
    }
}
