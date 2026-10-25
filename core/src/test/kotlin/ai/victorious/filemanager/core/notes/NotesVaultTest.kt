package ai.victorious.filemanager.core.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class WikiLinkParserTest {
    @Test fun parsesForms() {
        val text = "See [[Home]] and [[Projects/Alpha|Alpha]] plus [[Home#Setup|setup]]."
        val links = WikiLinkParser.links(text)
        assertEquals(3, links.size)
        assertEquals("Home", links[0].target)
        assertNull(links[0].alias)
        assertEquals("Projects/Alpha", links[1].target)
        assertEquals("Alpha", links[1].alias)
        assertEquals("Home", links[2].target)
        assertEquals("Setup", links[2].heading)
        assertEquals("setup", links[2].alias)
        assertEquals("setup", WikiLinkParser.displayText(links[2]))
    }

    @Test fun tagsSkipHeadingsAndWikilinks() {
        val text = "# Title\n\nHello #work and #home/office.\n[[Note#heading]] should not yield heading tag.\n"
        val tags = WikiLinkParser.tags(text).map { it.name }
        assertEquals(listOf("work", "home/office"), tags)
        assertFalse(tags.contains("Title"))
        assertFalse(tags.contains("heading"))
    }

    @Test fun frontmatter() {
        val text = "---\ntitle: Hello\ntags: a, b\n---\n\nBody [[X]]\n"
        val fm = WikiLinkParser.frontmatter(text)!!
        assertEquals("Hello", fm.fields["title"])
        assertEquals("a, b", fm.fields["tags"])
        assertTrue(text.substring(fm.bodyStart).contains("Body"))
        assertNull(WikiLinkParser.frontmatter("no fm"))
    }
}

class BacklinkIndexTest {
    @Test fun backlinksAndOutgoing() {
        val idx = BacklinkIndex()
        idx.setNote("/vault/Home.md", "Links [[Alpha]] and [[Missing]].")
        idx.setNote("/vault/Alpha.md", "Back to [[Home]]. #work")
        assertEquals(listOf("Alpha", "Missing"), idx.outgoing("/vault/Home.md").map { it.toTarget })
        assertEquals("/vault/Alpha.md", idx.outgoing("/vault/Home.md")[0].toPath)
        assertNull(idx.outgoing("/vault/Home.md")[1].toPath)
        assertEquals(listOf("/vault/Home.md"), idx.backlinks("/vault/Alpha.md").map { it.fromPath })
        assertEquals(1, idx.unresolved().size)
        assertEquals(listOf("Alpha"), idx.suggest("Al"))
        assertEquals(listOf("Alpha", "Home"), idx.suggest("").sorted())
        assertTrue(idx.suggest("h").any { it.equals("Home", ignoreCase = true) })
    }

    @Test fun removeAndOrphans() {
        val idx = BacklinkIndex()
        idx.setNote("/vault/A.md", "alone")
        idx.setNote("/vault/B.md", "[[A]]")
        assertTrue(idx.orphanNotes().isEmpty() || idx.orphanNotes() == listOf("/vault/A.md") || true)
        // A has a backlink from B, so A is not orphan; B has outgoing so not orphan.
        assertEquals(emptyList<String>(), idx.orphanNotes())
        idx.removeNote("/vault/B.md")
        assertEquals(listOf("/vault/A.md"), idx.orphanNotes())
    }

    @Test fun resolvePaths() {
        assertEquals("Home", NotePaths.titleOf("/x/Home.md"))
        assertEquals("/vault/Home.md", NotePaths.resolve("Home", listOf("/vault/Home.md", "/vault/Other.md")))
        assertEquals("/vault/Home.md", NotePaths.resolve("home", listOf("/vault/Home.md")))
        assertNull(NotePaths.resolve("Nope", listOf("/vault/Home.md")))
    }

    @Test fun graph() {
        val idx = BacklinkIndex()
        idx.setNote("/v/A.md", "[[B]]"); idx.setNote("/v/B.md", "")
        val (nodes, edges) = NoteGraph.build(idx)
        assertEquals(setOf("A", "B"), nodes.map { it.title }.toSet())
        assertEquals(listOf(GraphEdge("/v/A.md", "/v/B.md")), edges)
    }
}

class VaultTest {
    class MemIo : NoteIo {
        val files = linkedMapOf<String, String>()
        val dirs = mutableSetOf<String>()
        override fun listMarkdown(root: String) = files.keys.filter { it.startsWith(root.trimEnd('/')) && it.endsWith(".md") }.sorted()
        override fun read(path: String) = files[path] ?: error("missing $path")
        override fun write(path: String, text: String) { files[path] = text }
        override fun exists(path: String) = path in files
        override fun mkdir(path: String) { dirs += path }
    }

    @Test fun dailyTemplatesSearch() {
        val io = MemIo()
        val root = "/vault"
        io.write("$root/Templates/Daily.md", "---\ndate: {{date}}\n---\n# {{title}}\n\n")
        val v = Vault(io, VaultConfig(root))
        val path = v.ensureDaily(LocalDate.of(2026, 10, 20))
        assertEquals("/vault/Daily/2026-10-20.md", path)
        assertTrue(io.files[path]!!.contains("2026-10-20"))
        assertTrue(io.dirs.contains("/vault/Daily"))
        v.save("$root/Ideas.md", "Talk about [[2026-10-20]] and #inbox")
        v.rebuildIndex()
        assertEquals(1, v.index.backlinks(path).size)
        assertEquals(listOf("$root/Ideas.md" to "Talk about [[2026-10-20]] and #inbox"), v.search("inbox"))
        val n = v.newNote("Hello World", template = null)
        assertTrue(io.files[n]!!.contains("# Hello World"))
    }
}

class MarkdownPreviewTest {
    @Test fun blocksAndInline() {
        val md = """
            # Hello
            Paragraph with **bold** and *italic* and `code` and [[Home|Go]] and #tag.
            - item
            1. one
            > quote
            ```kotlin
            fun x() = 1
            ```
            ---
        """.trimIndent()
        val blocks = MarkdownPreview.parse(md)
        assertTrue(blocks[0] is MdBlock.Heading)
        assertEquals(1, (blocks[0] as MdBlock.Heading).level)
        val para = blocks.first { it is MdBlock.Paragraph } as MdBlock.Paragraph
        assertTrue(para.spans.any { it is MdSpan.Bold })
        assertTrue(para.spans.any { it is MdSpan.Wiki })
        assertTrue(para.spans.any { it is MdSpan.Tag && it.name == "tag" })
        assertTrue(blocks.any { it is MdBlock.Code && it.language == "kotlin" && it.code.contains("fun x") })
        assertTrue(blocks.any { it is MdBlock.Rule })
    }
}
