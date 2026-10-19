package ai.victorious.victorfiles.core.syntax

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntaxAndFindReplaceTest {
    private fun spans(text: String, lang: Language) =
        SyntaxHighlighter.tokenize(text, lang).map { text.substring(it.start, it.end) to it.type }

    @Test fun kotlinKeywordsStringsComments() {
        val s = spans("val x = \"hi // no\" // yes\nfun f() = 42", Language.KOTLIN)
        assertTrue(("val" to TokenType.KEYWORD) in s)
        assertTrue(("\"hi // no\"" to TokenType.STRING) in s)
        assertTrue(("// yes" to TokenType.COMMENT) in s)
        assertTrue(("fun" to TokenType.KEYWORD) in s)
        assertTrue(("42" to TokenType.NUMBER) in s)
    }

    @Test fun tokensOrderedAndNonOverlapping() {
        for (lang in Language.values()) {
            val text = "<a b=\"c\">/* x */ 'q' # z \n.method v0 Lcom/x; `t` **b** {\"k\": true}"
            var last = 0
            for (t in SyntaxHighlighter.tokenize(text, lang)) {
                assertTrue("$lang $t", t.start >= last && t.end > t.start && t.end <= text.length)
                last = t.end
            }
        }
    }

    @Test fun smaliXmlJsonPythonShellMarkdown() {
        assertTrue((".method" to TokenType.KEYWORD) in spans(".method public f()V\n    const/4 v0, 0x1 # c", Language.SMALI))
        assertTrue(("Lcom/a/B;" to TokenType.TAG) in spans("    invoke-static {v0}, Lcom/a/B;->f()V", Language.SMALI))
        val x = spans("<manifest package=\"a.b\"><!-- c --></manifest>", Language.XML)
        assertTrue(("<manifest" to TokenType.TAG) in x)
        assertTrue(("package" to TokenType.ATTRIBUTE) in x)
        assertTrue(("\"a.b\"" to TokenType.STRING) in x)
        assertTrue(("<!-- c -->" to TokenType.COMMENT) in x)
        assertTrue(("true" to TokenType.KEYWORD) in spans("{\"a\": true}", Language.JSON))
        assertTrue(("def" to TokenType.KEYWORD) in spans("def f():\n  # c\n  return 1", Language.PYTHON))
        assertTrue(("\$HOME" to TokenType.ATTRIBUTE) in spans("echo \$HOME # c", Language.SHELL))
        assertTrue(("# Title" to TokenType.HEADING) in spans("# Title\ntext `code`", Language.MARKDOWN))
        assertEquals(Language.KOTLIN, SyntaxHighlighter.languageFor("A.kt"))
        assertEquals(Language.SMALI, SyntaxHighlighter.languageFor("x.smali"))
    }

    @Test fun regexReplaceWithGroupsAndCount() {
        val r = FindReplace.replaceAll("a1 b22 c333", "([a-z])(\\d+)", "$2$1", FindOptions(regex = true))
        assertEquals("1a 22b 333c", r.text)
        assertEquals(3, r.count)
    }

    @Test fun literalModeEscapesSpecials() {
        assertEquals(2, FindReplace.count("a.b a.b axb", "a.b"))
        assertEquals("$ $ axb", FindReplace.replaceAll("a.b a.b axb", "a.b", "$").text)
    }

    @Test fun ignoreCaseWholeWordAndNext() {
        val o = FindOptions(ignoreCase = true, wholeWord = true)
        assertEquals(2, FindReplace.count("Cat cat concat", "cat", o))
        val r = FindReplace.replaceNext("x x x", "x", "y", from = 1)
        assertEquals("x y x", r.text)
        assertEquals(0, FindReplace.count("abc", ""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidRegexThrows() { FindReplace.count("abc", "(", FindOptions(regex = true)) }
}
