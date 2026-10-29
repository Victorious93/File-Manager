package ai.victorious.filemanager.core.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteOpsTest {
    class RenIo : NoteIo {
        val files = linkedMapOf<String, String>()
        override fun listMarkdown(root: String) = files.keys.filter { it.startsWith(root) }.sorted()
        override fun read(path: String) = files[path] ?: error("missing $path")
        override fun write(path: String, text: String) { files[path] = text }
        override fun exists(path: String) = path in files
        override fun mkdir(path: String) {}
        override fun rename(from: String, to: String): Boolean {
            val t = files.remove(from) ?: return false
            files[to] = t; return true
        }
    }

    @Test fun rewriteKeepsHeadingAliasAndEmbeds() {
        val (out, n) = NoteOps.rewriteLinks("a [[Old]] b [[old#Sec|x]] c ![[Old]] d [[Older]]", "Old", "New")
        assertEquals("a [[New]] b [[New#Sec|x]] c ![[New]] d [[Older]]", out)
        assertEquals(3, n)
    }

    @Test fun renameNoteUpdatesBacklinks() {
        val io = RenIo()
        io.files["/v/Old.md"] = "self [[Old]]"
        io.files["/v/A.md"] = "see [[Old|o]]"
        io.files["/v/B.md"] = "nothing"
        val v = Vault(io, VaultConfig("/v")); v.rebuildIndex()
        val r = v.renameNote("/v/Old.md", "Fresh")
        assertEquals("/v/Fresh.md", r.newPath)
        assertEquals("see [[Fresh|o]]", io.files["/v/A.md"])
        assertEquals("self [[Fresh]]", io.files["/v/Fresh.md"])
        assertEquals("nothing", io.files["/v/B.md"])
        assertEquals(2, r.linksRewritten)
        assertEquals(2, v.index.backlinks("/v/Fresh.md").size)
        assertFalse("/v/Old.md" in io.files)
    }

    @Test(expected = IllegalArgumentException::class) fun renameRefusesClash() {
        val io = RenIo(); io.files["/v/A.md"] = ""; io.files["/v/B.md"] = ""
        Vault(io, VaultConfig("/v")).apply { rebuildIndex() }.renameNote("/v/A.md", "B")
    }

    @Test(expected = IllegalArgumentException::class) fun renameRefusesBadChars() {
        val io = RenIo(); io.files["/v/A.md"] = ""
        Vault(io, VaultConfig("/v")).renameNote("/v/A.md", "a/b")
    }

    @Test fun unlinkedMentionsSkipLinksCodeAndPartialWords() {
        val notes = mapOf(
            "/v/X.md" to "Talked about project alpha today.\n[[Project Alpha]] already linked\n`project alpha` in code\nproject alphabet no",
            "/v/Project Alpha.md" to "project alpha self",
        )
        val m = NoteOps.unlinkedMentions("Project Alpha", notes, "/v/Project Alpha.md")
        assertEquals(1, m.size)
        assertEquals(0, m[0].line)
        val linked = NoteOps.linkMention(notes["/v/X.md"]!!, m[0], "Project Alpha")
        assertTrue(linked.startsWith("Talked about [[Project Alpha|project alpha]] today."))
    }

    @Test fun tagTreeNests() {
        val tree = NoteOps.tagTree(mapOf("a.md" to "#work/client #home", "b.md" to "#work"))
        assertEquals(listOf("home", "work"), tree.map { it.name })
        val work = tree.first { it.name == "work" }
        assertEquals(listOf("a.md", "b.md"), work.notes)
        assertEquals("work/client", work.children.single().fullName)
        assertEquals(listOf("a.md"), work.children.single().notes)
        assertEquals(3, NoteOps.flatten(tree).size)
    }

    @Test fun outlineSkipsFencesAndFrontmatter() {
        val body = "---\ntitle: t\n---\n# One\ntext\n```\n# not\n```\n## Two ##\n"
        val o = NoteOps.outline(body)
        assertEquals(listOf("One" to 1, "Two" to 2), o.map { it.text to it.level })
        assertEquals(3, o[0].line)
    }

    @Test fun toggleTasks() {
        val b = "- [ ] a\n- [x] b\nplain"
        assertEquals("- [x] a\n- [x] b\nplain", NoteOps.toggleTask(b, 0))
        assertEquals("- [ ] a\n- [ ] b\nplain", NoteOps.toggleTask(b, 1))
        assertEquals(b, NoteOps.toggleTask(b, 2))
        assertEquals(NoteOps.TaskStats(1, 1), NoteOps.taskStats(b))
    }

    @Test fun localGraphDepth() {
        val idx = BacklinkIndex()
        idx.setNote("A.md", "[[B]]"); idx.setNote("B.md", "[[C]]"); idx.setNote("C.md", ""); idx.setNote("D.md", "")
        assertEquals(setOf("A.md", "B.md"), NoteOps.localGraph(idx, "A.md", 1).first.map { it.path }.toSet())
        assertEquals(setOf("A.md", "B.md", "C.md"), NoteOps.localGraph(idx, "A.md", 2).first.map { it.path }.toSet())
    }
}
