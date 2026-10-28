package ai.victorious.filemanager.core.translate

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StringsXmlTranslateTest {
    private val src = """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name" translatable="false">VictorFiles</string>
    <string name="hello">Hello, <xliff:g id="n">%1${'$'}s</xliff:g>!</string>
    <string name="bye">Bye</string>
    <string name="empty"/>
    <plurals name="p"><item quantity="one">x</item></plurals>
</resources>"""
    private val dst = """<resources><string name="bye">Adiós</string></resources>"""

    @Test fun parseKeepsMarkupAndFlags() {
        val e = StringsXmlTranslate.parse(src)
        assertEquals(listOf("app_name", "hello", "bye", "empty"), e.map { it.name })
        assertFalse(e[0].translatable)
        assertEquals("Hello, <xliff:g id=\"n\">%1\$s</xliff:g>!", e[1].value)
    }

    @Test fun sideBySideAndMissing() {
        val rows = StringsXmlTranslate.sideBySide(StringsXmlTranslate.parse(src), StringsXmlTranslate.parse(dst))
        assertEquals(3, rows.size)
        assertEquals("Adiós", rows.first { it.name == "bye" }.target)
        assertNull(rows.first { it.name == "hello" }.target)
        assertEquals(listOf("hello", "empty"), StringsXmlTranslate.missing(rows).map { it.name })
    }

    @Test fun csvRoundTripAndWrite() {
        val source = StringsXmlTranslate.parse(src)
        val rows = StringsXmlTranslate.sideBySide(source, StringsXmlTranslate.parse(dst))
        val csv = StringsXmlTranslate.exportCsv(rows).replace("hello,\"Hello, <xliff:g id=\"\"n\"\">%1\$s</xliff:g>!\",", "hello,\"Hello, <xliff:g id=\"\"n\"\">%1\$s</xliff:g>!\",\"Hola, <xliff:g id=\"\"n\"\">%1\$s</xliff:g>!\"")
        val imp = StringsXmlTranslate.importCsv(csv + "ghost,a,b\n", source)
        assertEquals("Hola, <xliff:g id=\"n\">%1\$s</xliff:g>!", imp.values["hello"])
        assertEquals(listOf("unknown name ghost"), imp.skipped)
        val out = StringsXmlTranslate.write(source, imp.values)
        assertTrue(out.contains("<string name=\"hello\">Hola, <xliff:g id=\"n\">%1\$s</xliff:g>!</string>"))
        assertTrue(out.contains("<string name=\"bye\">Adiós</string>"))
        assertFalse(out.contains("app_name"))
        assertEquals(imp.values, StringsXmlTranslate.parse(out).associate { it.name to it.value })
    }

    @Test fun escaping() {
        assertEquals("Tom &amp; Jerry\\'s &lt;3", StringsXmlTranslate.escapeIfPlain("Tom & Jerry's <3"))
        assertEquals("<b>x</b> &amp; y", StringsXmlTranslate.escapeIfPlain("<b>x</b> & y"))
        assertEquals("it\\'s", StringsXmlTranslate.escapeIfPlain("it\\'s"))
    }

    @Test fun multilineCsvAndTargetPath() {
        assertEquals(listOf(listOf("a", "b\nc", "d")), StringsXmlTranslate.parseCsv("a,\"b\nc\",d\n"))
        assertEquals(File("/x/res/values-es/strings.xml"), StringsXmlTranslate.targetFile(File("/x/res/values/strings.xml"), "es"))
    }
}
