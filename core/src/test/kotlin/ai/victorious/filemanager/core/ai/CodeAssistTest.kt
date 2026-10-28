package ai.victorious.filemanager.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeAssistTest {
    private val orig = "a\nb\nc\nd\ne\n"
    private val prop = "a\nB\nc\nd\ne\nf\n"

    @Test fun promptExplainSmali() {
        val p = PromptBuilder.build(CodeAssistContract.request(AssistTask.EXPLAIN, "A.smali", "SMALI", ".class LA;"))
        assertTrue(p.contains("Dalvik smali")); assertTrue(p.contains("```smali\n.class LA;\n```"))
    }

    @Test fun promptEditUsesInstructionAndDefault() {
        val p = PromptBuilder.build(CodeAssistContract.request(AssistTask.SUGGEST_EDIT, "x.kt", "KOTLIN", "val a=1\n", "  rename a  "))
        assertTrue(p.contains("Instruction: rename a\n")); assertTrue(p.contains("COMPLETE revised file"))
        val d = PromptBuilder.build(CodeAssistContract.request(AssistTask.SUGGEST_EDIT, "x.kt", "KOTLIN", "v"))
        assertTrue(d.contains("without changing behaviour"))
    }

    @Test fun requestTruncatesAndEncodes() {
        val r = CodeAssistContract.request(AssistTask.EXPLAIN, "f", "JAVA", "x".repeat(CodeAssistContract.MAX_CODE_CHARS + 5))
        assertTrue(r.truncated); assertEquals(CodeAssistContract.MAX_CODE_CHARS, r.code.length)
        val o = Json.parseToJsonElement(CodeAssistContract.encode(r)).jsonObject
        assertEquals("explain", o["task"]!!.jsonPrimitive.content)
        assertEquals("1", o["v"]!!.jsonPrimitive.content)
        assertTrue(o["prompt"]!!.jsonPrimitive.content.contains("truncated"))
    }

    @Test fun decodeVariants() {
        assertFalse(CodeAssistContract.decode(null).ok)
        assertFalse(CodeAssistContract.decode("not json").ok)
        assertEquals("boom", CodeAssistContract.decode("""{"ok":false,"error":"boom"}""").error)
        assertFalse(CodeAssistContract.decode("""{"v":9,"ok":true}""").ok)
        val r = CodeAssistContract.decode("""{"v":1,"ok":true,"explanation":"Here:\n```kotlin\nval b=2\n```\nok"}""")
        assertTrue(r.ok); assertEquals("val b=2\n", r.revisedCode)
        assertEquals("X", CodeAssistContract.decode("""{"ok":true,"revisedCode":"X"}""").revisedCode)
        assertNull(CodeAssistContract.decode("""{"ok":true,"explanation":"plain"}""").revisedCode)
    }

    @Test fun hunksAndApplyAll() {
        val h = EditHunks.hunks(orig, prop)
        assertEquals(2, h.size)
        assertEquals(prop, EditHunks.apply(orig, h, h.map { it.id }.toSet()))
    }

    @Test fun applyNoneAndPartial() {
        val h = EditHunks.hunks(orig, prop)
        assertEquals(orig, EditHunks.apply(orig, h, emptySet()))
        assertEquals("a\nB\nc\nd\ne\n", EditHunks.apply(orig, h, setOf(0)))
        assertEquals("a\nb\nc\nd\ne\nf\n", EditHunks.apply(orig, h, setOf(1)))
    }

    @Test fun applyDeletionAndCrlf() {
        val o = "x\r\ny\r\nz\r\n"
        val h = EditHunks.hunks(o, "x\nz\n")
        assertEquals("x\r\nz\r\n", EditHunks.apply(o, h, setOf(0)))
    }
}
