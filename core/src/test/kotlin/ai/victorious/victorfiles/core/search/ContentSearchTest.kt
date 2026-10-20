package ai.victorious.victorfiles.core.search

import ai.victorious.victorfiles.core.props.FileMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ContentSearchTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun tree(): File {
        val r = tmp.newFolder("r")
        File(r, "a.smali").writeText("line one\n.method foo()V\nconst-string v0, \"Hello\"\n")
        File(r, "b.xml").writeText("<a>hello</a>\n")
        File(r, "sub").mkdir()
        File(r, "sub/c.smali").writeText("HELLO again\n")
        File(r, "bin.dat").writeBytes(byteArrayOf(0, 1, 2) + "hello".toByteArray())
        return r
    }

    @Test
    fun literalCaseInsensitiveWithLineNumbers() {
        val hits = ContentSearch.search(tree(), ContentQuery("hello"))
        assertEquals(setOf("a.smali:3", "b.xml:1", "c.smali:1"), hits.map { File(it.path).name + ":" + it.line }.toSet())
    }

    @Test
    fun caseSensitiveGlobAndBinarySkipped() {
        val r = tree()
        val hits = ContentSearch.search(r, ContentQuery("Hello", caseSensitive = true, nameGlobs = listOf("*.smali")))
        assertEquals(listOf("a.smali"), hits.map { File(it.path).name })
        assertTrue(ContentSearch.search(r, ContentQuery("hello")).none { it.path.endsWith("bin.dat") })
    }

    @Test
    fun regexAndInvalidRegexAndMaxResults() {
        val r = tree()
        assertEquals(1, ContentSearch.search(r, ContentQuery("\\.method\\s+\\w+", regex = true)).size)
        val e = runCatching { ContentSearch.search(r, ContentQuery("(", regex = true)) }.exceptionOrNull()
        assertTrue(e is ContentSearch.InvalidQuery)
        assertEquals(1, ContentSearch.search(r, ContentQuery("hello", maxResults = 1)).size)
    }

    @Test
    fun literalSpecialCharsAreNotRegex() {
        val r = tmp.newFolder("s")
        File(r, "x.txt").writeText("a.b\naxb\n")
        assertEquals(1, ContentSearch.search(r, ContentQuery("a.b")).size)
    }

    @Test
    fun fileModeParseFormatAndApply() {
        assertEquals("755", FileMode.parse("0755").octal)
        assertEquals("rwxr-xr--", FileMode.parse("754").symbolic)
        assertEquals(FileMode.parse("640"), FileMode.parse("rw-r-----"))
        assertTrue(runCatching { FileMode.parse("999") }.isFailure)
        val f = tmp.newFile("m.sh")
        val before = FileMode.read(f)
        if (before != null) {
            FileMode.apply(f, FileMode.parse("750"))
            assertEquals("750", FileMode.read(f)!!.octal)
            assertFalse(FileMode.read(f)!!.octal == "644")
        }
    }
}
